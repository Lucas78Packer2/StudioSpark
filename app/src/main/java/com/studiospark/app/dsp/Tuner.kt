package com.studiospark.app.dsp

import kotlin.math.*

/** YIN-style pitch detector for the rig tuner. */
object Tuner {
    data class Reading(val hz: Float, val note: String, val cents: Float, val midi: Int)
    private val names = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    fun noteName(midi: Int) = names[((midi % 12) + 12) % 12] + (midi / 12 - 1)
    fun midiToHz(m: Float, a4: Float = 440f) = a4 * 2f.pow((m - 69f) / 12f)

    fun detect(x: FloatArray, n: Int, sr: Float, a4: Float = 440f): Reading? {
        var rms = 0f; for (i in 0 until n) rms += x[i] * x[i]; if (sqrt(rms / n) < 0.01f) return null
        val maxTau = min(n / 2, (sr / 35f).toInt()); val minTau = (sr / 1400f).toInt().coerceAtLeast(2)
        val d = FloatArray(maxTau + 1)
        for (tau in 1..maxTau) { var s = 0f; for (i in 0 until n - maxTau) { val diff = x[i] - x[i + tau]; s += diff * diff }; d[tau] = s }
        var run = 0f; var best = -1
        for (tau in 1..maxTau) { run += d[tau]; val cm = if (run > 0) d[tau] * tau / run else 1f; d[tau] = cm }
        for (tau in minTau until maxTau) if (d[tau] < 0.15f && d[tau] <= d[tau + 1]) { best = tau; break }
        if (best < 0) { var m = Float.MAX_VALUE; for (tau in minTau until maxTau) if (d[tau] < m) { m = d[tau]; best = tau }; if (m > 0.4f) return null }
        val t = if (best in 1 until maxTau) { val a = d[best - 1]; val b = d[best]; val c = d[best + 1]; val den = a - 2 * b + c; best + if (abs(den) > 1e-9) 0.5f * (a - c) / den else 0f } else best.toFloat()
        val hz = sr / t
        val m = 69f + 12f * log2(hz / a4); val nearest = m.roundToInt()
        return Reading(hz, noteName(nearest), (m - nearest) * 100f, nearest)
    }
}
