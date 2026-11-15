package com.studiospark.app.importer.demucs

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.max

/**
 * HT-Demucs 4-stem ONNX (StemSplitio/htdemucs-onnx, MIT) on ONNX Runtime Android.
 *
 * I/O (model card): input `mix` float32 [1, 2, 343980] (stereo, 44.1 kHz, 7.8 s, [-1, 1]);
 * output `stems` float32 [1, 4, 2, 343980] in order drums, bass, other, vocals. The segment length is read from the
 * session's input shape when it is static, so a re-export with another segment still works.
 *
 * Execution provider: NNAPI first (ORT falls back to CPU per unsupported op); if NNAPI can't be added or the
 * session fails to build with it, a plain CPU session is created. Created only via [open] — never at startup.
 */
class HtDemucsOrtModel private constructor(
    private val env: OrtEnvironment,
    private val session: OrtSession,
    private val inputName: String,
    override val segmentFrames: Int,
    /** "NNAPI + CPU" or "CPU". */
    val provider: String,
    override val stemOrder: List<Stem>,
) : SegmentModel, AutoCloseable {

    /** Where the .onnx file comes from: always a downloaded file (ModelStore); nothing is bundled in the APK. */
    sealed class Source(val file: File) { class Path(file: File) : Source(file) }

    companion object {
        const val DEFAULT_SEGMENT = 343_980

        /** Blocking (hundreds of ms to seconds); call from a background dispatcher. */
        fun open(source: Source, preferNnapi: Boolean = true, stemOrder: List<Stem> = Stem.HTDEMUCS_ORDER): HtDemucsOrtModel {
            val env = OrtEnvironment.getEnvironment()
            fun build(nnapi: Boolean): OrtSession {
                val opts = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(max(2, Runtime.getRuntime().availableProcessors() / 2))
                    setInterOpNumThreads(1)
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                    if (nnapi) addNnapi()   // throws OrtException if the NNAPI EP isn't in this build / device
                }
                return try { require(source.file.isFile) { "model file missing: ${source.file.path}" }; env.createSession(source.file.path, opts) } finally { opts.close() }
            }
            var provider = "CPU"
            val session = if (preferNnapi) runCatching { build(true) }.onSuccess { provider = "NNAPI + CPU" }.getOrNull() ?: build(false)
                          else build(false)
            val inputName = session.inputNames.let { names -> if ("mix" in names) "mix" else names.first() }
            val shape = (session.inputInfo.getValue(inputName).info as? TensorInfo)?.shape
            val seg = shape?.lastOrNull()?.takeIf { it > 0 }?.toInt() ?: DEFAULT_SEGMENT
            return HtDemucsOrtModel(env, session, inputName, seg, provider, stemOrder)
        }
    }

    /** [input] planar [2][segmentFrames] -> [stems][2][segmentFrames] in [stemOrder]. */
    override fun run(input: Array<FloatArray>): Array<Array<FloatArray>> {
        val n = segmentFrames
        require(input.size == 2 && input.all { it.size == n }) { "expected planar stereo [2][$n]" }
        val flat = FloatArray(2 * n).also { System.arraycopy(input[0], 0, it, 0, n); System.arraycopy(input[1], 0, it, n, n) }
        return OnnxTensor.createTensor(env, FloatBuffer.wrap(flat), longArrayOf(1, 2, n.toLong())).use { x ->
            session.run(mapOf(inputName to x)).use { result ->
                val out = (result.get("stems").orElse(result.get(0))) as OnnxTensor
                val shape = out.info.shape
                check(shape.size == 4 && shape[1] == stemOrder.size.toLong() && shape[2] == 2L && shape[3] == n.toLong()) {
                    "unexpected HT-Demucs output shape ${shape.contentToString()}"
                }
                val fb = out.floatBuffer
                Array(stemOrder.size) { s -> Array(2) { c -> FloatArray(n).also { fb.position((s * 2 + c) * n); fb.get(it) } } }
            }
        }
    }

    override fun close() { runCatching { session.close() } }
}
