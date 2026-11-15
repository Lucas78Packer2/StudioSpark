package com.studiospark.app.importer

import android.app.ActivityManager
import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * The Import tab's separation engine contract (named StemSplitEngine so it does not clash with 2.1.0's
 * com.studiospark.app.stems.StemSeparator). Chunked I/O keeps peak memory bounded: the engine pulls fixed-size chunks
 * from [input] and pushes mono stem samples into [output]; neither side needs the whole song in the Java heap.
 * Input and output are 44.1 kHz; output frame count == input.frames; stem index i is [stems][i].
 */
interface StemSplitEngine : AutoCloseable {
    val provider: String
    val stems: List<String>
    fun separate(input: StereoSource, output: StemSink, cancel: () -> Boolean, progress: (String, Float) -> Unit)
}

/** 44.1 kHz stereo input. [read] fills [dst] with [frames] interleaved L,R frames from [startFrame]; past the end is zero. */
interface StereoSource { val frames: Int; fun read(startFrame: Int, frames: Int, dst: FloatArray) }
/** Receives each output sample exactly once, frames in increasing order per stem. */
fun interface StemSink { fun write(stem: Int, frame: Int, value: Float) }

/** In-memory source (tests, short clips). */
class ArrayStereoSource(private val interleaved: FloatArray) : StereoSource {
    override val frames = interleaved.size / 2
    override fun read(startFrame: Int, frames: Int, dst: FloatArray) { java.util.Arrays.fill(dst, 0, frames * 2, 0f)
        val n = minOf(frames, this.frames - startFrame); if (n > 0) System.arraycopy(interleaved, startFrame * 2, dst, 0, n * 2) }
}

/** One pinned model file: URL at a fixed repo revision, exact byte size and SHA-256 (from Hugging Face LFS metadata). */
data class ModelFile(val name: String, val url: String, val sha256: String, val bytes: Long)

/**
 * HT-Demucs ONNX exports published by StemSplit (huggingface.co/StemSplitio, MIT; original weights Meta AI, MIT; trained
 * on MUSDB18-HQ, a research-licensed dataset — flagged to Lucas). Never bundled in the APK: files are fetched on the
 * user's first split, pinned to a repo revision, verified by size + SHA-256, then kept in app storage.
 * All are fp16-weight exports (same networks, half the download of the fp32 files).
 */
enum class DemucsVariant(val id: String, val label: String, val files: List<ModelFile>, val stems: List<String>, val minRamBytes: Long) {
    /** 2.2.0 default (Lucas): the largest model, htdemucs_ft — 4 fine-tuned specialists (bag), 4x the compute of FOUR. */
    FT("htdemucs-ft-fp16", "HT-Demucs FT (4 specialists, best quality)", listOf(
        ModelFile("htdemucs_ft_drums_fp16weights.onnx", "https://huggingface.co/StemSplitio/htdemucs-ft-onnx/resolve/8616370ed541bc183dbe15fb0d54d5f49918f47e/htdemucs_ft_drums_fp16weights.onnx", "047764dff888cfb87da917013377d4ec7a134f7419cbe486d9c339aa17975ddd", 165_612_636L),
        ModelFile("htdemucs_ft_bass_fp16weights.onnx", "https://huggingface.co/StemSplitio/htdemucs-ft-onnx/resolve/8616370ed541bc183dbe15fb0d54d5f49918f47e/htdemucs_ft_bass_fp16weights.onnx", "b533037176b14b2df31c92a5d5b3d5660d0811b9b360d3db761964768b079961", 165_612_636L),
        ModelFile("htdemucs_ft_other_fp16weights.onnx", "https://huggingface.co/StemSplitio/htdemucs-ft-onnx/resolve/8616370ed541bc183dbe15fb0d54d5f49918f47e/htdemucs_ft_other_fp16weights.onnx", "b739171a7057b3107bb0711c6222d4a619b41b13a8f04026431d30f32ad2bd71", 165_612_636L),
        ModelFile("htdemucs_ft_vocals_fp16weights.onnx", "https://huggingface.co/StemSplitio/htdemucs-ft-onnx/resolve/8616370ed541bc183dbe15fb0d54d5f49918f47e/htdemucs_ft_vocals_fp16weights.onnx", "0cbe651f535415c9d26a7bb614f7d322dd5a080fa0298f2e50f478030a994dce", 165_612_636L),
    ), listOf("drums", "bass", "other", "vocals"), 0L),
    FOUR("htdemucs-4s-fp16", "HT-Demucs 4-stem (single model, faster)", listOf(ModelFile("htdemucs_fp16weights.onnx",
        "https://huggingface.co/StemSplitio/htdemucs-onnx/resolve/d54ed9eb60e258ea82131c6ee14578628816456a/htdemucs_fp16weights.onnx",
        "d05c269d0178d2a72ad484b10b11dd370193fc923201c3b27a99f848745db70a", 165_612_636L)), listOf("drums", "bass", "other", "vocals"), 0L),
    /** Opt-in only, offered when the phone reports >= 4 GB RAM. */
    SIX("htdemucs-6s-fp16", "HT-Demucs 6-stem (guitar + piano)", listOf(ModelFile("htdemucs_6s_fp16weights.onnx",
        "https://huggingface.co/StemSplitio/htdemucs-6s-onnx/resolve/49df9b6989cf2150840ea65b0bef77a2e471b678/htdemucs_6s_fp16weights.onnx",
        "7ce55792e2231c93fbf92de95f5fd5b3a5e6c89f7db690dfd693e8f1dce56869", 136_428_532L)), listOf("drums", "bass", "other", "vocals", "guitar", "piano"), 4L shl 30);
    val bytes get() = files.sumOf { it.bytes }
}

object ModelStore {
    fun dir(ctx: Context) = File(ctx.filesDir, "import_models").apply { mkdirs() }
    fun file(ctx: Context, f: ModelFile) = File(dir(ctx), f.name)
    /** Single-file variants (FOUR, SIX). */
    fun file(ctx: Context, v: DemucsVariant) = file(ctx, v.files.single())
    /** A file with no pinned size/hash is never considered installed or downloadable. */
    fun pinned(f: ModelFile) = f.bytes > 0L && f.sha256.matches(Regex("[0-9a-f]{64}"))
    fun pinned(v: DemucsVariant) = v.files.isNotEmpty() && v.files.all { pinned(it) }
    fun installed(ctx: Context, v: DemucsVariant) = v.files.all { f -> pinned(f) && file(ctx, f).let { it.exists() && it.length() == f.bytes } }
    fun totalRam(ctx: Context): Long = runCatching { ActivityManager.MemoryInfo().also { (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it) }.totalMem }.getOrDefault(0L)
    /** Six-stem is only offered when RAM >= 4 GB (pure, unit-tested). */
    fun sixStemAllowed(totalRam: Long) = totalRam >= DemucsVariant.SIX.minRamBytes

    /** Downloads every missing file of [v] (resumable .part, size + SHA-256 verified). Call off the main thread. */
    fun download(ctx: Context, v: DemucsVariant, cancel: () -> Boolean, progress: (Float) -> Unit) {
        check(pinned(v)) { "${v.id} has no pinned size/SHA-256; refusing to download" }
        var done = 0L; val total = v.bytes
        for (f in v.files) { if (!(file(ctx, f).let { it.exists() && it.length() == f.bytes })) downloadFile(ctx, f, cancel) { have -> progress((done + have).toFloat() / total) }; done += f.bytes }
    }

    private fun downloadFile(ctx: Context, f: ModelFile, cancel: () -> Boolean, progress: (Long) -> Unit) {
        val out = file(ctx, f); val part = File(out.path + ".part")
        ModelGate.note("downloading ${f.name} on user request")
        var url = URL(f.url); var conn: HttpURLConnection; var redirects = 0
        while (true) {
            conn = url.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false; conn.connectTimeout = 15000; conn.readTimeout = 30000
            if (part.exists()) conn.setRequestProperty("Range", "bytes=${part.length()}-")
            val code = conn.responseCode
            if (code in 300..399 && redirects++ < 5) { url = URL(url, conn.getHeaderField("Location")); conn.disconnect(); continue }
            if (code !in 200..299) error("Model download failed (HTTP $code)")
            break
        }
        val append = conn.responseCode == 206
        var have = if (append) part.length() else 0L
        conn.inputStream.use { inp -> java.io.FileOutputStream(part, append).use { o ->
            val buf = ByteArray(1 shl 16)
            while (true) { if (cancel()) throw InterruptedException("Download cancelled"); val n = inp.read(buf); if (n < 0) break; o.write(buf, 0, n); have += n; progress(have) } } }
        if (part.length() != f.bytes) { part.delete(); error("Model download was incomplete (${part.length()} of ${f.bytes} bytes); deleted. Try again.") }
        val got = part.inputStream().use { StemLibrary.sha256(it) }
        if (got != f.sha256) { part.delete(); error("Model file failed its SHA-256 check; deleted. Try again.") }
        check(part.renameTo(out)) { "Could not install model" }
    }
}
