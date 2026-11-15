package com.studiospark.app.importer

import kotlin.math.*

/**
 * Tempo and key estimates computed during the split pass and stored with the stems.
 * Tempo: onset-strength autocorrelation (prefers 70–180 BPM; half/double-time errors are possible).
 * Key: pitch-class chroma matched against Krumhansl–Kessler major/minor profiles. Both are estimates and are labelled so.
 */
object TempoKey {
    private val NAMES = listOf("C", "C#", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B")
    private val MAJOR = doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88)
    private val MINOR = doubleArrayOf(6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17)

    /** BPM of mono [x] at [sr], or null if there is no rhythmic content. Analyses at most [maxSeconds]. */
    fun tempo(x: FloatArray, sr: Int, maxSeconds: Int = 90): Float? {
        val n = min(x.size, sr * maxSeconds); val hop = 512; val win = 1024
        if (n < win * 8) return null
        val frames = (n - win) / hop + 1; val env = FloatArray(frames)
        var prev = 0.0
        for (f in 0 until frames) { var e = 0.0; val o = f * hop; for (i in 0 until win) { val v = x[o + i].toDouble(); e += v * v }
            val le = ln(1e-9 + e / win); env[f] = max(0.0, le - prev).toFloat(); prev = le }
        val mean = env.average().toFloat(); for (i in env.indices) env[i] = max(0f, env[i] - mean)
        if (env.all { it == 0f }) return null
        val fps = sr.toDouble() / hop
        val minLag = floor(fps * 60 / 200).toInt(); val maxLag = ceil(fps * 60 / 50).toInt().coerceAtMost(frames - 1)
        if (maxLag <= minLag + 2) return null
        val ac = DoubleArray(maxLag + 2)
        for (lag in minLag..maxLag + 1) { if (lag >= frames) break; var s = 0.0; for (i in 0 until frames - lag) s += env[i] * env[i + lag]; ac[lag] = s / (frames - lag) }
        var best = -1; var bestScore = 0.0
        for (lag in minLag..maxLag) { val bpm = 60 * fps / lag
            val prior = exp(-0.5 * (log2(bpm / 120.0) / 0.9).pow(2))        // gentle preference for common tempos
            val score = ac[lag] * prior; if (score > bestScore) { bestScore = score; best = lag } }
        if (best < 0 || bestScore <= 0) return null
        val a = ac[best - 1]; val b = ac[best]; val c = ac[best + 1]; val d = a - 2 * b + c
        val refined = if (d < 0) best + 0.5 * (a - c) / d else best.toDouble()
        return (60 * fps / refined).toFloat()
    }

    /** "C major", "A minor" … or null for silence. [x] mono at [sr]. */
    fun key(x: FloatArray, sr: Int, maxSeconds: Int = 120): String? {
        val c = chroma(x, sr, maxSeconds) ?: return null
        var best = ""; var bestR = -2.0
        for (root in 0 until 12) for ((mode, prof) in listOf("major" to MAJOR, "minor" to MINOR)) {
            val r = corr(c, DoubleArray(12) { prof[(it - root + 12) % 12] }); if (r > bestR) { bestR = r; best = "${NAMES[root]} $mode" } }
        return best
    }

    fun chroma(x: FloatArray, sr: Int, maxSeconds: Int = 120): DoubleArray? {
        val n = min(x.size, sr * maxSeconds); val size = 8192; val hop = 4096
        if (n < size) return null
        val win = DoubleArray(size) { 0.5 - 0.5 * cos(2 * PI * it / size) }
        val chroma = DoubleArray(12); val re = DoubleArray(size); val im = DoubleArray(size)
        val lo = (55.0 * size / sr).toInt().coerceAtLeast(1); val hi = min(size / 2, (2000.0 * size / sr).toInt())
        val pc = IntArray(size / 2 + 1) { b -> if (b < lo || b > hi) -1 else { val f = b.toDouble() * sr / size; ((round(12 * log2(f / 440.0)).toInt() + 9) % 12 + 12) % 12 } }
        var o = 0
        while (o + size <= n) {
            for (i in 0 until size) { re[i] = x[o + i] * win[i]; im[i] = 0.0 }; fft(re, im)
            for (b in lo..hi) if (pc[b] >= 0) chroma[pc[b]] += sqrt(re[b] * re[b] + im[b] * im[b])
            o += hop
        }
        return if (chroma.sum() < 1e-6) null else chroma
    }

    private fun corr(a: DoubleArray, b: DoubleArray): Double { val ma = a.average(); val mb = b.average(); var s = 0.0; var sa = 0.0; var sb = 0.0
        for (i in a.indices) { val da = a[i] - ma; val db = b[i] - mb; s += da * db; sa += da * da; sb += db * db }; return s / sqrt(sa * sb + 1e-12) }

    internal fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size; var j = 0
        for (i in 1 until n) { var bit = n shr 1; while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }; j = j xor bit; if (i < j) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t } }
        var len = 2
        while (len <= n) { val ang = -2 * PI / len; val wr0 = cos(ang); val wi0 = sin(ang); var s = 0
            while (s < n) { var wr = 1.0; var wi = 0.0
                for (k in 0 until len / 2) { val a = s + k; val b = a + len / 2; val tr = wr * re[b] - wi * im[b]; val ti = wr * im[b] + wi * re[b]
                    re[b] = re[a] - tr; im[b] = im[a] - ti; re[a] += tr; im[a] += ti; val nw = wr * wr0 - wi * wi0; wi = wr * wi0 + wi * wr0; wr = nw }
                s += len }
            len *= 2 }
    }
}
