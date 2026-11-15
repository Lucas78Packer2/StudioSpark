package com.studiospark.app.importer

import ai.onnxruntime.*
import android.content.Context
import com.studiospark.app.stems.StemDsp
import java.nio.FloatBuffer
import java.nio.channels.FileChannel
import kotlin.math.max

/**
 * Offline fallback for the Import tab when HT-Demucs is not installed: the bundled Open-Unmix UMX-HQ graphs (MIT weights) on ONNX Runtime.
 * Execution provider: NNAPI first (with ORT's per-op CPU fallback), plain CPU if NNAPI cannot create a session.
 * Every chunk is exactly [CHUNK] frames (the last one zero-padded) so the "frames" dimension is fixed at [SPEC_FRAMES]
 * — NNAPI cannot take nodes with dynamic shapes. Created only by [open], from the first import that needs a split.
 */
class UmxStemModel private constructor(private val env: OrtEnvironment, private val sessions: Map<String, OrtSession>, override val provider: String) : StemSplitEngine {
    companion object {
        const val MODEL_ID = "umxhq-onnx-v1"; const val LABEL = "Open-Unmix UMX-HQ (bundled fallback)"
        const val SR = 44100
        const val CHUNK = SR * 4
        const val OVERLAP = SR
        val SPEC_FRAMES = CHUNK / StemDsp.HOP + 1
        val NAMES = listOf("vocals", "drums", "bass", "other")

        fun open(ctx: Context, preferNnapi: Boolean = true): UmxStemModel {
            ModelGate.record("ORT environment + UMX-HQ sessions")
            val env = OrtEnvironment.getEnvironment()
            fun build(nnapi: Boolean): Map<String, OrtSession> {
                val made = LinkedHashMap<String, OrtSession>()
                try {
                    for (n in NAMES) {
                        val o = OrtSession.SessionOptions().apply {
                            setIntraOpNumThreads(max(2, Runtime.getRuntime().availableProcessors() / 2)); setInterOpNumThreads(1)
                            setSymbolicDimensionValue("frames", SPEC_FRAMES.toLong())
                            if (nnapi) addNnapi()
                        }
                        made[n] = ctx.assets.openFd("separation_models/$n.onnx").use { fd -> fd.createInputStream().use { s ->
                            env.createSession(s.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength), o) } }
                    }
                    return made
                } catch (t: Throwable) { made.values.forEach { runCatching { it.close() } }; throw t }
            }
            if (preferNnapi) {
                val r = runCatching { build(true) }
                r.getOrNull()?.let { ModelGate.note("UMX-HQ sessions on NNAPI (+CPU fallback)"); return UmxStemModel(env, it, "NNAPI + CPU") }
                ModelGate.note("NNAPI unavailable (${r.exceptionOrNull()?.javaClass?.simpleName}: ${r.exceptionOrNull()?.message}); using CPU")
            }
            return UmxStemModel(env, build(false), "CPU")
        }
    }

    private fun estimate(name: String, s: StemDsp.Spectrum): FloatArray =
        OnnxTensor.createTensor(env, FloatBuffer.wrap(s.magnitude), longArrayOf(1, 2, StemDsp.BINS.toLong(), s.count.toLong())).use { t ->
            sessions.getValue(name).run(mapOf("mag" to t)).use { out -> val v = out[0] as OnnxTensor; FloatArray(s.magnitude.size).also { v.floatBuffer.get(it) } } }

    override val stems get() = NAMES
    /** Mono stems in [NAMES] order, written chunk by chunk into [output]. */
    override fun separate(input: StereoSource, output: StemSink, cancel: () -> Boolean, progress: (String, Float) -> Unit) {
        val w = OverlapAdd.Writer(input.frames, CHUNK, OVERLAP, 4) { o, f, v -> output.write(o, f, v) }
        val total = w.chunks.size; val x = FloatArray(CHUNK * 2)
        for (c in w.chunks) {
            if (cancel()) throw InterruptedException("Import cancelled")
            input.read(c.start, CHUNK, x)   // zero-padded fixed shape
            val spec = StemDsp.stft(x, cancel)
            val est = NAMES.mapIndexed { i, n -> progress("Splitting chunk ${c.index + 1}/$total · $n", (c.index + i / 4f) / total); estimate(n, spec) }
            val masks = StemDsp.masks(est)
            val mono = Array(4) { s -> val y = StemDsp.inverse(spec, masks[s], cancel); FloatArray(c.length) { k -> (y[k * 2] + y[k * 2 + 1]) * 0.5f } }
            w.push(c, mono)
        }
        check(w.done)
    }

    override fun close() { sessions.values.forEach { runCatching { it.close() } } }
}
