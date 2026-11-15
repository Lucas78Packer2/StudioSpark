package com.studiospark.app.importer

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.compose.runtime.*
import com.studiospark.app.audio.Decoder
import com.studiospark.app.core.ImportPolicy
import kotlinx.coroutines.*
import java.io.File

/** One queued file. */
class ImportJob(val uri: Uri, val name: String) {
    var state by mutableStateOf("Queued")
    var progress by mutableFloatStateOf(0f)
    /** Loaded only for the selected job (bounded memory in batch imports); other ready jobs keep just [hash]/[modelId]. */
    var session by mutableStateOf<ImportSession?>(null)
    var error by mutableStateOf<String?>(null)
    var fromCache by mutableStateOf(false)
    var ready by mutableStateOf(false)
    var hash: String? = null; var modelId: String? = null
}

/**
 * Import tab controller. Created lazily by StudioVM the first time the Import tab asks for it, and even then it
 * loads nothing heavy: the ORT sessions and YAMNet are opened only when the first queued file actually needs a split
 * (a stem-library hit never loads them). Jobs run one at a time on a single worker — no parallel splits.
 */
class ImportController(private val ctx: Context, private val scope: CoroutineScope) {
    companion object { const val MAX_SECONDS = 360; val ACCEPTED = setOf("mp3", "wav", "flac", "aac", "m4a") }
    private val libRoot by lazy { File(ctx.filesDir, "stem_library").apply { mkdirs() } }
    /** One library per model so a file split by a better model is never confused with a fallback split. */
    fun libraryFor(id: String) = StemLibrary(libRoot, id)
    val library get() = libraryFor(activeModelId)
    /** All libraries, best model first (used for cache lookups and by Ear-training). */
    val libraries get() = (DemucsVariant.values().map { modelIdFor(it) } + UmxStemModel.MODEL_ID).map { libraryFor(it) }
    /**
     * Model + hop. 2.2.0 default (Lucas): the largest model (HT-Demucs FT bag) and the shortest hop, 0.25 s.
     * Both stay configurable. A shorter hop means more model calls (slower), not faster splits.
     */
    var demucsChoice by mutableStateOf(DemucsVariant.FT)
    var hopFrames by mutableIntStateOf(com.studiospark.app.importer.demucs.DemucsHop.DEFAULT)
    var useFallback by mutableStateOf(false)
    var download by mutableFloatStateOf(-1f); private set
    val totalRam by lazy { ModelStore.totalRam(ctx) }
    val sixAllowed get() = ModelStore.sixStemAllowed(totalRam)
    val variant get() = if (demucsChoice == DemucsVariant.SIX && !sixAllowed) DemucsVariant.FT else demucsChoice
    fun demucsInstalled() = ModelStore.installed(ctx, variant)
    /** Cache key includes the hop, so changing the hop re-splits instead of serving stems made with another hop. */
    fun modelIdFor(v: DemucsVariant) = "${v.id}-hop$hopFrames"
    val activeModelId get() = if (useFallback) UmxStemModel.MODEL_ID else modelIdFor(variant)
    val activeLabel get() = if (useFallback) UmxStemModel.LABEL else variant.label
    val jobs = mutableStateListOf<ImportJob>()
    var selected by mutableStateOf<ImportJob?>(null)
    var rightsConfirmed by mutableStateOf(false)
    var message by mutableStateOf<String?>(null)
    var provider by mutableStateOf<String?>(null); private set
    var running by mutableStateOf(false); private set
    var rev by mutableIntStateOf(0)
    @Volatile private var cancel = false
    private var model: StemSplitEngine? = null; private var modelId: String? = null
    private var tagger: YamnetTagger? = null
    private var worker: Job? = null

    private fun displayName(uri: Uri): String = runCatching { ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } }.getOrNull() ?: uri.lastPathSegment ?: "audio"

    /** Checks the file type and rights rules; returns an error or null. */
    fun accept(uri: Uri, name: String, mime: String?): String? {
        if (!rightsConfirmed) return "Confirm you have the rights to these files first."
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (val d = ImportPolicy.classify(uri.toString(), name, mime)) {
            is ImportPolicy.Decision.Reject -> d.reason
            is ImportPolicy.Decision.Accept -> if (d.kind != "audio" || ext !in ACCEPTED) "$name: Import takes MP3, WAV, FLAC or AAC files." else null
        }
    }

    fun enqueueFiles(uris: List<Uri>) {
        val errs = ArrayList<String>()
        for (u in uris) { val n = displayName(u); val e = accept(u, n, ctx.contentResolver.getType(u)); if (e != null) errs += e else jobs += ImportJob(u, n) }
        message = errs.firstOrNull()?.let { if (errs.size > 1) "$it (+${errs.size - 1} more skipped)" else it }
        kick()
    }

    /** Batch import: every MP3/WAV/FLAC/AAC file directly inside the picked folder is queued. */
    fun enqueueFolder(tree: Uri) {
        val kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val found = ArrayList<Uri>()
        runCatching { ctx.contentResolver.query(kids, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            while (c.moveToNext()) { val name = c.getString(1) ?: continue; if (name.substringAfterLast('.', "").lowercase() in ACCEPTED) found += DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)) } } }
            .onFailure { message = "Could not read that folder: ${it.message}"; return }
        if (found.isEmpty()) { message = "No MP3, WAV, FLAC or AAC files in that folder."; return }
        enqueueFiles(found)
    }

    fun cancelAll() { cancel = true; jobs.filter { it.state == "Queued" }.forEach { it.state = "Cancelled" } }

    /** Stem-library size, measured off the main thread and cached (never computed during composition). */
    var librarySizeBytes by mutableLongStateOf(-1L); private set
    fun refreshLibrarySize() { scope.launch { librarySizeBytes = withContext(Dispatchers.IO) { runCatching { library.sizeBytes() }.getOrDefault(-1L) } } }

    /**
     * Shows [j]'s stems. Only the selected job's stems are held in memory; the previously selected job drops its copy
     * (it stays in the stem library and reloads on the next tap).
     */
    fun select(j: ImportJob) {
        if (!j.ready) return
        if (selected === j && j.session != null) return
        selected?.takeIf { it !== j }?.session = null
        selected = j
        val h = j.hash ?: return; val id = j.modelId ?: return
        scope.launch {
            val loaded = withContext(Dispatchers.IO) { runCatching { libraryFor(id).load(h) }.getOrNull() }
            if (loaded == null) { j.error = "Stems missing from the library; import the file again."; j.ready = false; j.state = "Failed"; return@launch }
            val (meta, stems) = loaded
            val scores = meta.otherTags.toMap().let { m -> m["route:guitar"]?.let { StemRouter.InstrumentScores(it, m["route:keys"] ?: 0f, m["route:other"] ?: 0f) } }
            val shown = meta.copy(otherTags = meta.otherTags.filterNot { it.first.startsWith("route:") })
            if (selected === j) { j.session = ImportSession(j.name, meta.hash, shown, stems, scores); rev++ }
        }
    }

    private fun kick() {
        if (worker?.isActive == true) return
        cancel = false
        worker = scope.launch {
            running = true
            try { while (true) { val j = jobs.firstOrNull { it.state == "Queued" } ?: break; if (cancel) break; process(j) } }
            finally { running = false; releaseModels() }
            // Auto-open the first finished job only after the models are released, so stems and model never coexist.
            if (selected?.session == null) jobs.firstOrNull { it.ready }?.let { select(it) }
            refreshLibrarySize()
        }
    }

    private val workRoot by lazy { File(ctx.cacheDir, "import_work").apply { mkdirs() } }

    /** Splits one file with bounded heap: decode → disk, engine reads/writes memory-mapped files, stems land in the library. */
    private suspend fun process(j: ImportJob) {
        j.state = "Hashing"; j.error = null
        val decoded = File(workRoot, "decoded.f32")
        try {
            val (meta, hit) = withContext(Dispatchers.Default) {
                val hash = ctx.contentResolver.openInputStream(j.uri)?.let { StemLibrary.sha256(it) } ?: error("Cannot open file")
                libraries.firstOrNull { it.contains(hash) }?.meta(hash)?.let {
                    ModelGate.note("stem library hit ${hash.take(12)} — split skipped")
                    return@withContext it to true }
                j.state = "Decoding"
                val frames = runCatching { DiskPcm.decodeToFile(ctx, j.uri, decoded, MAX_SECONDS) { j.progress = it * 0.1f } }
                    .getOrElse { e -> if (e is CancellationException) throw e
                        // WAV variants some platform extractors reject: 2.1.0's in-memory decoder, then straight to disk
                        val dec = Decoder.decode(ctx, j.uri, maxSeconds = MAX_SECONDS); val st = Decoder.toStereo44k(dec)
                        DiskPcm.StereoWriter(decoded).use { w -> for (i in 0 until st.size / 2) w.frame(st[i * 2].takeIf { it.isFinite() } ?: 0f, st[i * 2 + 1].takeIf { it.isFinite() } ?: 0f); w.frames } }
                if (frames == 0) error("No audio decoded")
                val wantId = activeModelId
                if (!useFallback && !ModelStore.installed(ctx, variant)) {
                    j.state = "Downloading ${variant.label} model (one time)"
                    ModelStore.download(ctx, variant, { cancel }) { download = it; j.progress = 0.1f * it }
                    download = -1f
                }
                j.state = "Loading model"
                if (modelId != wantId) { model?.close(); model = null }
                val m = model ?: (if (useFallback) UmxStemModel.open(ctx) else openDemucs(variant)).also { model = it; modelId = wantId; provider = it.provider }
                val lib = libraryFor(wantId); val work = lib.workDir(hash)
                m.separate(DiskPcm.MappedStereoSource(decoded), DiskPcm.MappedStemSink(work, m.stems, frames), { cancel }) { s, f -> j.state = s; j.progress = 0.1f + f * 0.8f }
                decoded.delete()
                val sr = DiskPcm.SR; val win = sr * 120   // tags, tempo and key from a centred 2-minute window
                j.state = "Tagging · tempo · key"
                val t = tagger ?: YamnetTagger.open(ctx).also { tagger = it }
                val (scores, tags) = t.tag(DiskPcm.excerpt(File(work, "other.pcm"), win), sr)
                val drums = DiskPcm.excerpt(File(work, "drums.pcm"), win)
                val tempo = TempoKey.tempo(FloatArray(drums.size) { drums[it] / 32768f }, sr)
                val pitched = m.stems.filter { it != "drums" }.map { DiskPcm.excerpt(File(work, "$it.pcm"), win) }
                val harm = FloatArray(drums.size) { i -> pitched.sumOf { it[i].toInt() } / 32768f / pitched.size }
                val key = TempoKey.key(harm, sr)
                val meta = StemMeta(hash, j.name, wantId, frames, sr, tempo, key,
                    tags + listOf("route:guitar" to scores.guitar, "route:keys" to scores.keys, "route:other" to scores.other), stems = m.stems)
                lib.commit(meta, work)
                meta to false
            }
            j.hash = meta.hash; j.modelId = meta.modelId; j.fromCache = hit; j.ready = true
            j.state = if (hit) "Ready (from stem library)" else "Ready"; j.progress = 1f
        } catch (e: CancellationException) {
            j.state = "Cancelled"; j.error = "Cancelled"; throw e   // the VM shut down: stop the queue, don't swallow it
        } catch (e: Throwable) {
            j.state = if (e is InterruptedException) "Cancelled" else "Failed"
            j.error = when (e) { is InterruptedException -> "Cancelled"; is OutOfMemoryError -> "Not enough memory to split this file (${e.message})"; else -> e.message ?: e.javaClass.simpleName }
        } finally { decoded.delete(); download = -1f }
        rev++
    }

    /** Builds the HT-Demucs engine on the import worker (blocking ONNX loads; FT loads one specialist at a time). */
    private fun openDemucs(v: DemucsVariant): StemSplitEngine {
        val (DR, BA, OT, VO) = listOf(com.studiospark.app.importer.demucs.Stem.DRUMS, com.studiospark.app.importer.demucs.Stem.BASS, com.studiospark.app.importer.demucs.Stem.OTHER, com.studiospark.app.importer.demucs.Stem.VOCALS)
        return when (v) {
            DemucsVariant.FT -> com.studiospark.app.importer.demucs.BagStemSplitEngine(listOf(DR, BA, OT, VO).zip(v.files).associate { (st, f) -> st to ModelStore.file(ctx, f) }, hopFrames = hopFrames)
            DemucsVariant.FOUR -> com.studiospark.app.importer.demucs.DemucsStemSplitEngine(ModelStore.file(ctx, v), hopFrames = hopFrames)
            DemucsVariant.SIX -> com.studiospark.app.importer.demucs.DemucsStemSplitEngine(ModelStore.file(ctx, v), hopFrames = hopFrames, stemOrder = com.studiospark.app.importer.demucs.Stem.HTDEMUCS_6S_ORDER)
        }
    }

    /** The split models are released when the queue drains, so they never sit in memory after an import. */
    private fun releaseModels() { model?.close(); model = null; tagger?.close(); tagger = null }
}
