package com.studiospark.app.stems

import ai.onnxruntime.*
import android.content.Context
import java.nio.FloatBuffer
import java.nio.channels.FileChannel
import kotlin.math.*

/**
 * Real on-device four-stem separation (vocals, drums, bass, other) with the Open-Unmix UMX-HQ estimators (MIT weights) exported to ONNX,
 * run on CPU via ONNX Runtime. 4 s chunks with 1 s cross-fade; learned ratio masks; no audio leaves the phone.
 */
class StemSeparator(private val ctx: Context) {
    companion object { val names = listOf("vocals", "drums", "bass", "other"); const val SR = 44100 }
    private val env by lazy { OrtEnvironment.getEnvironment() }
    private val sessions = HashMap<String, OrtSession>()
    private fun session(name: String): OrtSession = sessions.getOrPut(name) {
        val o = OrtSession.SessionOptions().apply { setIntraOpNumThreads(max(2, Runtime.getRuntime().availableProcessors() / 2)); setInterOpNumThreads(1) }
        ctx.assets.openFd("separation_models/$name.onnx").use { fd -> fd.createInputStream().use { s -> env.createSession(s.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength), o) } }
    }
    private fun estimate(name: String, s: StemDsp.Spectrum): FloatArray =
        OnnxTensor.createTensor(env, FloatBuffer.wrap(s.magnitude), longArrayOf(1, 2, StemDsp.BINS.toLong(), s.count.toLong())).use { t ->
            session(name).run(mapOf("mag" to t)).use { out -> val v = out[0] as OnnxTensor; FloatArray(s.magnitude.size).also { v.floatBuffer.get(it) } } }

    /** [stereo] interleaved at 44.1 kHz. Returns four time-aligned mono PCM16 stems (44.1 kHz) of the same length. */
    fun separate(stereo: FloatArray, cancel: () -> Boolean, progress: (String, Float) -> Unit): Array<ShortArray> {
        val frames = stereo.size / 2; val out = Array(4) { ShortArray(frames) }
        val size = SR * 4; val overlap = SR; val step = size - overlap
        val total = max(1, ceil(max(0, frames - size).toDouble() / step).toInt() + 1)
        var start = 0; var chunk = 0; var prev: Array<FloatArray>? = null
        while (start < frames) {
            if (cancel()) throw InterruptedException("Separation cancelled")
            val len = min(size, frames - start); val x = stereo.copyOfRange(start * 2, (start + len) * 2)
            val spec = StemDsp.stft(x, cancel)
            val est = names.mapIndexed { i, n -> progress("Chunk ${chunk + 1}/$total · $n", (chunk + i / 4f) / total); estimate(n, spec) }
            val masks = StemDsp.masks(est); val ys = Array(4) { StemDsp.inverse(spec, masks[it], cancel) }
            prev?.let { pv -> val n = min(overlap, min(len, pv[0].size / 2)); for (i in 0 until n) { val w = i.toFloat() / overlap; for (s in 0..3) for (c in 0..1) { val k = i * 2 + c; ys[s][k] = pv[s][k] * (1 - w) + ys[s][k] * w } } }
            val final = start + len >= frames; val emit = if (final) len else step
            for (s in 0..3) for (i in 0 until emit) out[s][start + i] = (((ys[s][i * 2] + ys[s][i * 2 + 1]) * 0.5f).coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            prev = if (final) null else Array(4) { s -> ys[s].copyOfRange(step * 2, len * 2) }
            chunk++; progress("Separated $chunk/$total", chunk.toFloat() / total); if (final) break; start += step
        }
        return out
    }
    fun close() { sessions.values.forEach { runCatching { it.close() } }; sessions.clear() }
}
