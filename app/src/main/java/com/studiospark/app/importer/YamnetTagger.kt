package com.studiospark.app.importer

import android.content.Context
import com.studiospark.app.audio.Wav
import org.tensorflow.lite.Interpreter
import java.nio.channels.FileChannel

/**
 * Tags the "other" stem with YAMNet (TFLite, 521 AudioSet classes, Apache-2.0). Loaded only by [open], from the
 * first import that needs tagging. Scores are averaged over up to [MAX_WINDOWS] 0.975 s windows spread across the stem.
 */
class YamnetTagger private constructor(private val interp: Interpreter, private val labels: List<String>) : AutoCloseable {
    companion object {
        const val MAX_WINDOWS = 40
        val GUITAR = setOf(134, 135, 136, 138, 139, 140)
        val KEYS = setOf(147, 148, 149, 150, 151, 152, 153)
        private val IGNORE = setOf("Music", "Musical instrument", "Silence", "Speech", "Inside, small room", "Inside, large room or hall")
        fun open(ctx: Context): YamnetTagger {
            ModelGate.record("YAMNet TFLite interpreter")
            val buf = ctx.assets.openFd("import_models/yamnet.tflite").use { fd -> fd.createInputStream().use { s -> s.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength) } }
            val labels = ctx.assets.open("import_models/yamnet_labels.txt").bufferedReader().readLines()
            return YamnetTagger(Interpreter(buf, Interpreter.Options().setNumThreads(2)), labels)
        }

        /** Pure scoring step, unit-tested: mean scores -> routing scores + top tags. */
        fun summarize(mean: FloatArray, labels: List<String>): Pair<StemRouter.InstrumentScores, List<Pair<String, Float>>> {
            val g = GUITAR.sumOf { mean.getOrElse(it) { 0f }.toDouble() }.toFloat()
            val k = KEYS.sumOf { mean.getOrElse(it) { 0f }.toDouble() }.toFloat()
            val instrumentRange = 134..275
            val o = instrumentRange.filter { it !in GUITAR && it !in KEYS && it < mean.size }.maxOfOrNull { mean[it] } ?: 0f
            val top = mean.indices.filter { labels.getOrNull(it) !in IGNORE }.sortedByDescending { mean[it] }.take(5).map { (labels.getOrNull(it) ?: "#$it") to mean[it] }
            return StemRouter.InstrumentScores(g, k, o) to top
        }
    }

    /** [mono] PCM16 at [sr]. */
    fun tag(mono: ShortArray, sr: Int): Pair<StemRouter.InstrumentScores, List<Pair<String, Float>>> {
        val x16 = Wav.resample(FloatArray(mono.size) { mono[it] / 32768f }, sr, 16000)
        val inShape = interp.getInputTensor(0).shape(); val win = inShape.last()
        val outShape = interp.getOutputTensor(0).shape(); val classes = outShape.last()
        val mean = FloatArray(classes)
        val n = if (x16.size <= win) 1 else minOf(MAX_WINDOWS, x16.size / win)
        val stride = if (n <= 1) 0 else (x16.size - win) / (n - 1)
        for (i in 0 until n) {
            val o = i * stride; val input = FloatArray(win) { j -> x16.getOrElse(o + j) { 0f } }
            val out = Array(1) { FloatArray(classes) }
            interp.run(if (inShape.size == 1) input else arrayOf(input), out)
            for (c in 0 until classes) mean[c] += out[0][c] / n
        }
        return summarize(mean, labels)
    }

    override fun close() { interp.close() }
}
