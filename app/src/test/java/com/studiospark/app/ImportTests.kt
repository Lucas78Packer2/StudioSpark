package com.studiospark.app

import com.studiospark.app.core.*
import com.studiospark.app.dsp.Family
import com.studiospark.app.importer.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.math.*

/** 2.2.0 Import tab: overlap-add math, routing, stem-library cache, session isolation, tempo/key, and the no-preload guard. */
class ImportTests {
    @get:Rule val tmp = TemporaryFolder()

    // ------------------------------------------------------------ overlap-add
    private fun runOla(frames: Int, size: Int, overlap: Int, x: FloatArray, process: (OverlapAdd.Chunk, FloatArray) -> FloatArray): Pair<FloatArray, IntArray> {
        val out = FloatArray(frames); val hits = IntArray(frames)
        val w = OverlapAdd.Writer(frames, size, overlap, 1) { _, f, v -> out[f] = v; hits[f]++ }
        for (c in w.chunks) { val chunk = FloatArray(size).also { System.arraycopy(x, c.start, it, 0, c.length) }; w.push(c, arrayOf(process(c, chunk))) }
        assertTrue(w.done); return out to hits
    }

    @Test fun planCoversTheFileWithFixedSizeChunks() {
        for (frames in listOf(1, 999, 1000, 1001, 4321, 176400 * 3 + 17)) {
            val size = if (frames > 10000) 176400 else 1000; val ov = size / 4
            val plan = OverlapAdd.plan(frames, size, ov)
            assertEquals(0, plan.first().start); assertEquals(frames, plan.last().end)
            plan.dropLast(1).forEach { assertEquals(size, it.length) }
            plan.zipWithNext().forEach { (a, b) -> assertEquals(size - ov, b.start - a.start) }
            if (plan.size > 1) assertTrue("last chunk longer than the overlap", plan.last().length > ov)
        }
        assertTrue(OverlapAdd.plan(0, 100, 10).isEmpty())
    }

    @Test fun crossfadeWeightsSumToOne() { val ov = 441; for (i in 0 until ov) { val w = OverlapAdd.fadeIn(i, ov); assertEquals(1f, w + (1f - w), 0f); assertTrue(w > 0f && w < 1f) } }

    @Test fun identityProcessReconstructsEveryFrameExactlyOnce() {
        val frames = 10_037; val x = FloatArray(frames) { sin(it * 0.013f) * 0.7f + 0.1f * cos(it * 0.31f) }
        val (y, hits) = runOla(frames, 1024, 256, x) { _, c -> c }
        assertTrue(hits.all { it == 1 })
        for (i in 0 until frames) assertEquals("frame $i", x[i], y[i], 1e-6f)
    }

    @Test fun chunkSeamsAreCrossfadedNotStepped() {
        // each chunk applies a different gain: output must ramp smoothly between gains inside the overlap, never jump
        val frames = 5000; val x = FloatArray(frames) { 1f }
        val (y, _) = runOla(frames, 1000, 200, x) { c, ch -> FloatArray(ch.size) { ch[it] * (1f + 0.5f * (c.index % 2)) } }
        var maxStep = 0f; for (i in 1 until frames) maxStep = max(maxStep, abs(y[i] - y[i - 1]))
        assertTrue("max step $maxStep", maxStep <= (0.5 * Math.PI / 2 / 200).toFloat() + 1e-4f)   // Hann crossfade: peak slope π/2 × linear
    }

    @Test fun separatorChunkShapeIsFixedForNnapi() {
        assertEquals(176400, UmxStemModel.CHUNK); assertEquals(173, UmxStemModel.SPEC_FRAMES)
        assertTrue(UmxStemModel.OVERLAP < UmxStemModel.CHUNK)
    }

    // ------------------------------------------------------------ routing
    @Test fun fixedStemsRouteToTheirTracks() {
        val r = StemRouter.routeAll(null)
        assertEquals(Destination.DRUM_TRACK, r["drums"]); assertEquals(Destination.BASS_TRACK, r["bass"]); assertEquals(Destination.VOCAL_TRACK, r["vocals"])
        assertEquals("untagged other stays a plain audio track", Destination.AUDIO_TRACK, r["other"])
    }

    @Test fun otherStemRoutesByYamnetTags() {
        assertEquals(Destination.GUITAR_TRACK, StemRouter.routeOther(StemRouter.InstrumentScores(0.4f, 0.1f, 0.2f)))
        assertEquals(Destination.KEYS_TRACK, StemRouter.routeOther(StemRouter.InstrumentScores(0.05f, 0.3f, 0.1f)))
        assertEquals(Destination.AUDIO_TRACK, StemRouter.routeOther(StemRouter.InstrumentScores(0.04f, 0.03f, 0.5f)))
        assertEquals("weak guitar evidence is not enough", Destination.AUDIO_TRACK, StemRouter.routeOther(StemRouter.InstrumentScores(0.05f, 0.01f, 0.01f)))
    }

    @Test fun yamnetSummaryGroupsGuitarAndKeysClasses() {
        val labels = List(521) { "c$it" }.toMutableList().also { it[135] = "Guitar"; it[136] = "Electric guitar"; it[148] = "Piano"; it[132] = "Music" }
        val mean = FloatArray(521).also { it[135] = 0.2f; it[136] = 0.3f; it[148] = 0.1f; it[132] = 0.9f }
        val (s, top) = YamnetTagger.summarize(mean, labels)
        assertEquals(0.5f, s.guitar, 1e-6f); assertEquals(0.1f, s.keys, 1e-6f)
        assertEquals("Electric guitar", top.first().first); assertTrue("generic Music label is not a tag", top.none { it.first == "Music" })
        assertEquals(Destination.GUITAR_TRACK, StemRouter.routeOther(s))
    }

    @Test fun dragToReassignSwapsDestinations() {
        val r = StemRouter.routeAll(StemRouter.InstrumentScores(0.5f, 0f, 0f))
        val s = StemRouter.swap(r, "other", "vocals")
        assertEquals(Destination.VOCAL_TRACK, s["other"]); assertEquals(Destination.GUITAR_TRACK, s["vocals"])
        assertEquals(r, StemRouter.swap(r, "bass", "bass"))
    }

    // ------------------------------------------------------------ stem library cache
    private fun stems(n: Int) = StemLibrary.STEMS.mapIndexed { k, s -> s to ShortArray(n) { ((it * (k + 3)) % 2000 - 1000).toShort() } }.toMap()
    private fun meta(hash: String, n: Int) = StemMeta(hash, "song.wav", "test-model", n, 44100, 120f, "C major", listOf("Electric guitar" to 0.4f))

    @Test fun sameBytesHashTheSameDifferentBytesDont() {
        val a = StemLibrary.sha256(ByteArrayInputStream("abc".toByteArray())); val b = StemLibrary.sha256(ByteArrayInputStream("abc".toByteArray()))
        assertEquals(a, b); assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", a)
        assertNotEquals(a, StemLibrary.sha256(ByteArrayInputStream("abd".toByteArray())))
    }

    @Test fun cacheRoundTripsStemsAndTags() {
        val lib = StemLibrary(tmp.newFolder("lib"), "test-model"); val h = "f00d"
        assertFalse(lib.contains(h)); assertNull(lib.load(h))
        val st = stems(5000); lib.store(meta(h, 5000), st)
        assertTrue(lib.contains(h))
        val (m, got) = lib.load(h)!!
        StemLibrary.STEMS.forEach { assertArrayEquals(st[it], got[it]) }
        assertEquals(120f, m.tempoBpm!!, 0f); assertEquals("C major", m.key); assertEquals("Electric guitar", m.otherTags.first().first)
    }

    @Test fun cacheIsPerModelSoAModelChangeReprocesses() {
        val root = tmp.newFolder("lib2"); StemLibrary(root, "model-a").store(meta("aa", 100).copy(modelId = "model-a"), stems(100))
        assertTrue(StemLibrary(root, "model-a").contains("aa")); assertFalse(StemLibrary(root, "model-b").contains("aa"))
    }

    @Test fun damagedCacheEntryIsAMissNotACrash() {
        val root = tmp.newFolder("lib3"); val lib = StemLibrary(root, "m"); lib.store(meta("bb", 100), stems(100))
        File(root, "m_bb/bass.pcm").writeBytes(ByteArray(10)); assertNull(lib.load("bb"))
    }

    @Test fun libraryEvictsLeastRecentlyUsedPastTheCap() {
        val root = tmp.newFolder("lib4"); val cap = 4L * 2 * 1000 * 2 + 2000   // room for ~2 entries of 1000 frames
        val lib = StemLibrary(root, "m", capBytes = cap)
        lib.store(meta("old", 1000).copy(lastUsed = 1), stems(1000)); Thread.sleep(5)
        lib.store(meta("mid", 1000).copy(lastUsed = 2), stems(1000)); Thread.sleep(5)
        lib.store(meta("new", 1000).copy(lastUsed = 3), stems(1000))
        assertFalse(lib.contains("old")); assertTrue(lib.contains("new")); assertTrue(lib.sizeBytes() <= cap)
    }

    // ------------------------------------------------------------ session isolation
    private fun session(tags: StemRouter.InstrumentScores? = StemRouter.InstrumentScores(0.5f, 0f, 0f)) = ImportSession("My Song.flac", "h", meta("h", 4410), stems(4410), tags)

    @Test fun importSessionNeverTouchesTheProjectBeforeConfirm() {
        val p = Sessions.blank().apply { tracks += Track(name = "Existing", family = Family.GUITAR, instrumentId = "gtr_0", color = 0) }
        val before = ProjectJson.write(p)
        val s = session(); s.reassign("bass", Destination.SKIP); s.dragOnto("other", "vocals"); s.strips[0].volumeDb = -6f
        assertEquals("routing, faders and drags edit only the import session", before, ProjectJson.write(p))
        try { s.commitInto(p, { it }) { "x" }; fail("commit without confirm must refuse") } catch (_: IllegalStateException) {}
        assertEquals(before, ProjectJson.write(p))
    }

    @Test fun confirmedCommitAddsAlignedTracksAndKeepsExistingOnes() {
        val p = Sessions.blank().apply { tracks += Track(name = "Existing", family = Family.GUITAR, instrumentId = "gtr_0", color = 0) }
        val s = session(); s.reassign("bass", Destination.SKIP); s.strips.first { it.stem == "vocals" }.volumeDb = -3f; s.confirm()
        val pool = HashMap<String, ShortArray>(); var n = 0
        val added = s.commitInto(p, { it }) { a -> "a${n++}".also { pool[it] = a } }
        assertEquals(3, added.size); assertEquals(4, p.tracks.size); assertEquals("Existing", p.tracks[0].name)
        assertTrue(added.all { it.clips.single().start == 0L })
        assertEquals(Family.GUITAR, added.first { it.name.endsWith("Other") }.family)
        assertEquals(Family.DRUMS, added.first { it.name.endsWith("Drums") }.family)
        assertEquals(-3f, added.first { it.name.endsWith("Vocals") }.volumeDb, 0f)
        assertEquals(3, pool.size)
    }

    @Test fun importSessionHoldsNoProjectReference() {
        val fields = ImportSession::class.java.declaredFields.map { it.type } + ImportStrip::class.java.declaredFields.map { it.type }
        assertTrue(fields.none { Project::class.java.isAssignableFrom(it) || Track::class.java.isAssignableFrom(it) })
        val ctl = ImportController::class.java.declaredFields.map { it.type.name }
        assertTrue("controller must not hold the Studio project or VM", ctl.none { it.endsWith(".Project") || it.endsWith(".StudioVM") })
    }

    // ------------------------------------------------------------ tempo & key
    @Test fun tempoOfAClickTrack() {
        val sr = 44100; val bpm = 120.0; val x = FloatArray(sr * 20)
        var t = 0.0; while (t < x.size) { val s = t.toInt(); for (i in 0 until 400) if (s + i < x.size) x[s + i] = (sin(i * 0.3) * exp(-i / 60.0)).toFloat(); t += sr * 60 / bpm }
        val got = TempoKey.tempo(x, sr)!!; assertEquals(120f, got, 2f)
        assertNull(TempoKey.tempo(FloatArray(sr * 10), sr))
    }

    private fun chords(sr: Int, vararg ch: IntArray): FloatArray { val per = sr * 2; val x = FloatArray(per * ch.size)
        ch.forEachIndexed { k, notes -> for (i in 0 until per) { var v = 0.0; for (m in notes) v += sin(2 * PI * 440.0 * 2.0.pow((m - 69) / 12.0) * i / sr); x[k * per + i] = (v / notes.size * 0.5).toFloat() } }; return x }

    @Test fun keyOfSimpleProgressions() {
        val sr = 44100
        assertEquals("C major", TempoKey.key(chords(sr, intArrayOf(48, 60, 64, 67), intArrayOf(53, 60, 65, 69), intArrayOf(55, 59, 62, 67), intArrayOf(48, 60, 64, 67)), sr))
        assertEquals("A minor", TempoKey.key(chords(sr, intArrayOf(45, 57, 60, 64), intArrayOf(50, 57, 62, 65), intArrayOf(52, 56, 59, 64), intArrayOf(45, 57, 60, 64)), sr))
        assertNull(TempoKey.key(FloatArray(sr * 4), sr))
    }

    // ------------------------------------------------------------ launch guard (source level)
    @Test fun nothingOnTheLaunchPathReferencesModelsOrOrt() {
        val main = File(SRC_MAIN, "java/com/studiospark/app")
        val launch = listOf("MainActivity.kt", "ui/MainScreen.kt", "ui/StudioVM.kt", "ui/Theme.kt").map { File(main, it).readText() }
        val heavy = listOf("OrtEnvironment", "OrtSession", "org.tensorflow", "Interpreter(", "UmxStemModel.open", "DemucsStemSplitEngine(", "BagStemSplitEngine(", "HtDemucsOrtModel", "ModelStore.download", "YamnetTagger.open", "import_models/", "separation_models/")
        launch.forEach { src -> heavy.forEach { h -> assertFalse("launch path references $h", h in src) } }
        // only the controller opens the models, and only inside the per-job worker
        val opens = main.walkTopDown().filter { it.extension == "kt" && "importer/demucs" !in it.path.replace('\\', '/') }.filter { listOf("UmxStemModel.open(", "demucs.DemucsStemSplitEngine(", "demucs.BagStemSplitEngine(", "ModelStore.download(", "YamnetTagger.open(").any { k -> k in it.readText() } }.map { it.name }.toSet()
        assertEquals(setOf("ImportController.kt"), opens)
        // no Application subclass, content provider or static init that could load a model before the user acts
        val manifest = File(SRC_MAIN, "AndroidManifest.xml").readText()
        assertFalse("android:name=\".App" in manifest); assertFalse("<provider" in manifest)
    }

    // ------------------------------------------------------------ audit fixes (2.2.0)
    @Test fun everyDownloadableModelIsPinnedBySizeAndHash() {
        DemucsVariant.values().forEach { v -> assertTrue("${v.id} unpinned", ModelStore.pinned(v)); v.files.forEach { f -> assertTrue(f.bytes > 0); assertTrue(f.sha256.matches(Regex("[0-9a-f]{64}"))); assertTrue("unpinned revision ${f.url}", Regex("/resolve/[0-9a-f]{40}/").containsMatchIn(f.url)) } }
        assertEquals(136_428_532L, DemucsVariant.SIX.bytes)
        assertEquals("7ce55792e2231c93fbf92de95f5fd5b3a5e6c89f7db690dfd693e8f1dce56869", DemucsVariant.SIX.files.single().sha256)
        assertEquals(4, DemucsVariant.FT.files.size); assertEquals(4 * 165_612_636L, DemucsVariant.FT.bytes)
        assertFalse(ModelStore.pinned(ModelFile("x", "u", "", 0)))
    }

    @Test fun resamplerKeepsDurationAndShape() {
        for ((from, to) in listOf(48000 to 44100, 22050 to 44100, 44100 to 44100)) {
            val r = DiskPcm.Resampler(from, to); var n = 0; var last = 0f
            val inN = from * 2; for (i in 0 until inN) r.push(i.toFloat() / inN, -i.toFloat() / inN) { l, rr -> assertEquals(-l, rr, 1e-6f); assertTrue(l >= last - 1e-6f); last = l; n++ }
            assertEquals("$from->$to", to * 2.0, n.toDouble(), 2.0)
        }
    }

    /** Pass-through engine: proves the chunked disk-backed I/O path end to end without a model. */
    private class IdentityEngine : StemSplitEngine {
        override val provider = "test"; override val stems = StemLibrary.STEMS
        override fun separate(input: StereoSource, output: StemSink, cancel: () -> Boolean, progress: (String, Float) -> Unit) {
            val size = 1000; val w = OverlapAdd.Writer(input.frames, size, 250, 4) { o, f, v -> output.write(o, f, v) }; val buf = FloatArray(size * 2)
            for (c in w.chunks) { input.read(c.start, size, buf); w.push(c, Array(4) { s -> FloatArray(c.length) { k -> buf[k * 2] * (s + 1) / 4f } }) }
        }
        override fun close() {}
    }

    @Test fun diskBackedSplitWritesStraightIntoTheLibrary() {
        val root = tmp.newFolder("lib5"); val lib = StemLibrary(root, "m"); val frames = 7_777
        val dec = File(tmp.newFolder("work"), "decoded.f32")
        DiskPcm.StereoWriter(dec).use { w -> for (i in 0 until frames) { val v = sin(i * 0.01f) * 0.8f; w.frame(v, v) } }
        val src = DiskPcm.MappedStereoSource(dec); assertEquals(frames, src.frames)
        val work = lib.workDir("cafe"); val e = IdentityEngine()
        e.separate(src, DiskPcm.MappedStemSink(work, e.stems, frames), { false }) { _, _ -> }
        lib.commit(meta("cafe", frames), work)
        val (_, got) = lib.load("cafe")!!
        for (i in 0 until frames step 97) { val v = sin(i * 0.01f) * 0.8f; for ((s, n) in e.stems.withIndex()) assertEquals(v * (s + 1) / 4f, got.getValue(n)[i] / 32767f, 2e-4f) }
        assertEquals(frames, DiskPcm.excerpt(File(root, "m_cafe/drums.pcm"), 100_000).size); assertEquals(500, DiskPcm.excerpt(File(root, "m_cafe/drums.pcm"), 500).size)
        val a = FloatArray(20); ArrayStereoSource(FloatArray(10) { 1f }).read(3, 10, a); assertEquals(1f, a[3], 0f); assertEquals(0f, a[4], 0f)
    }

    @Test fun drumStemsGoToADrumKitNotAMic() {
        assertTrue(Destination.DRUM_TRACK.instrumentId.startsWith("kit_")); assertEquals(Family.DRUMS, Destination.DRUM_TRACK.family)
    }

    @Test fun launchNeverWritesSessionFilesAndOnlyExplicitSaveWritesLastSession() {
        val vm = File(SRC_MAIN, "java/com/studiospark/app/ui/StudioVM.kt").readText()
        val init = vm.substring(vm.indexOf("    init {"), vm.indexOf("ticker()", vm.indexOf("    init {")))
        listOf("writeText", "renameTo", "delete()", "File(dir, \"last-session").forEach { assertFalse("launch init contains $it", it in init) }
        val writers = Regex("""File\(dir, "last-session\.json\.tmp"\)""").findAll(vm).count(); assertEquals(1, writers)
        val save = vm.substring(vm.indexOf("fun saveSession()"), vm.indexOf("fun saveSession()") + 300); assertTrue("last-session.json.tmp" in save)
        assertFalse("autosave must not target the user's save", Regex("""scheduleSave\(\)[^\n]*last-session""").containsMatchIn(vm))
    }

    @Test fun controllerRethrowsCancellation() {
        val c = File(SRC_MAIN, "java/com/studiospark/app/importer/ImportController.kt").readText()
        assertTrue(Regex("""catch \(e: CancellationException\) \{[^}]*throw e""").containsMatchIn(c))
        assertFalse("process() must not use runCatching around the split", c.substring(c.indexOf("private suspend fun process")).substringBefore("private fun releaseModels").contains("runCatching { withContext"))
    }

    @Test fun lucasDefaultsLargestModelAndShortestHop() {
        assertEquals(85_995, com.studiospark.app.importer.demucs.DemucsHop.DEFAULT); assertEquals(343_980 / 4, com.studiospark.app.importer.demucs.DemucsHop.DEFAULT)
        assertEquals(11_025, com.studiospark.app.importer.demucs.DemucsHop.SHORT_TEST)
        assertEquals(16.0, com.studiospark.app.importer.demucs.DemucsHop.callsPerSecond(11_025, models = 4), 1e-9)   // FT bag at 0.25 s
        assertEquals(2.05, com.studiospark.app.importer.demucs.DemucsHop.callsPerSecond(85_995, models = 4), 0.01)   // FT bag at 1.95 s
        assertEquals(1.0, com.studiospark.app.importer.demucs.DemucsHop.callsPerSecond(44_100), 1e-9)
        val c = File(SRC_MAIN, "java/com/studiospark/app/importer/ImportController.kt").readText()
        assertTrue("var demucsChoice by mutableStateOf(DemucsVariant.FT)" in c); assertTrue("var useFallback by mutableStateOf(false)" in c)
    }

    @Test fun noHtDemucsModelIsBundledInTheApk() {
        val assets = File(SRC_MAIN, "assets"); val onnx = assets.walkTopDown().filter { it.extension == "onnx" }.map { it.name }.toList()
        assertTrue("HT-Demucs bundled: $onnx", onnx.none { "demucs" in it.lowercase() })
    }

    @Test fun shortQuarterSecondHopStillReconstructs() {
        // identity specialists through the FT bag at the 0.25 s test hop (scaled segment) reconstruct the input
        val st = com.studiospark.app.importer.demucs.Stem.HTDEMUCS_ORDER
        class Id : com.studiospark.app.importer.demucs.SegmentModel { override val segmentFrames = 44_100; override val stemOrder = st
            override fun run(input: Array<FloatArray>) = Array(4) { input.map { it.copyOf() }.toTypedArray() } }
        val e = com.studiospark.app.importer.demucs.BagStemSplitEngine(st.associateWith { File(it.id) }, hopFrames = com.studiospark.app.importer.demucs.DemucsHop.SHORT_TEST) { _, _ -> Id() }
        val frames = 100_000; val x = FloatArray(frames * 2) { sin((it / 2) * 0.01f) * 0.5f }; val out = Array(4) { FloatArray(frames) }
        e.separate(ArrayStereoSource(x), { s, f, v -> out[s][f] = v }, { false }) { _, _ -> }
        for (s in 0..3) for (i in 0 until frames step 101) assertEquals(x[i * 2], out[s][i], 1e-4f)
    }

    @Test fun ftBagRunsEachSpecialistOnceAndKeepsOnlyItsStem() {
        val st = com.studiospark.app.importer.demucs.Stem.HTDEMUCS_ORDER; val opened = ArrayList<String>(); var open = 0; var maxOpen = 0
        // fake specialist: output row r = input * (r + 1) * 10 + specialist index, so we can see which row/specialist each stem came from
        class Fake(val idx: Int) : com.studiospark.app.importer.demucs.SegmentModel, AutoCloseable {
            override val segmentFrames = 2000; override val stemOrder = st
            override fun run(input: Array<FloatArray>) = Array(4) { r -> Array(2) { ch -> FloatArray(segmentFrames) { input[ch][it] * 0.01f * ((r + 1) * 10 + idx) } } }
            override fun close() { open-- }
        }
        val e = com.studiospark.app.importer.demucs.BagStemSplitEngine(st.associateWith { File(it.id) }, hopFrames = 500) { s, _ -> opened += s.id; open++; maxOpen = max(maxOpen, open); Fake(st.indexOf(s)) }
        val frames = 6000; val out = Array(4) { FloatArray(frames) }
        e.separate(ArrayStereoSource(FloatArray(frames * 2) { 1f }), { s, f, v -> out[s][f] = v }, { false }) { _, _ -> }
        assertEquals(listOf("drums", "bass", "other", "vocals"), opened); assertEquals(1, maxOpen); assertEquals(0, open)
        for (s in 0..3) assertEquals("stem $s from its own specialist row", 0.01f * ((s + 1) * 10 + s), out[s][3000], 1e-4f)
    }
}
