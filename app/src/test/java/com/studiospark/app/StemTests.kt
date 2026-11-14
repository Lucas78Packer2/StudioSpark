package com.studiospark.app

import com.studiospark.app.stems.StemDsp
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

/** The UMX host transform must be transparent: STFT → unity mask → inverse STFT reconstructs the input, and the 4 learned-estimate masks partition the mix. */
class StemTests {
    private fun stereo(n: Int) = FloatArray(n * 2) { i -> val t = i / 2; val c = i % 2; (0.3 * sin(2 * PI * (110.0 + 55 * c) * t / 44100) + 0.1 * sin(2 * PI * 2200.0 * t / 44100)).toFloat() }

    @Test fun stftRoundTripIsTransparent() {
        val x = stereo(44100); val s = StemDsp.stft(x)
        val y = StemDsp.inverse(s, FloatArray(s.real.size) { 1f })
        var err = 0.0; for (i in 4096 until x.size - 4096) err = max(err, abs((x[i] - y[i]).toDouble()))
        assertTrue("max reconstruction error $err", err < 1e-3)
    }

    @Test fun masksPartitionTheMix() {
        val n = 1000; val est = List(4) { k -> FloatArray(n) { i -> ((i * (k + 1)) % 7 + 0.5f) } }
        val m = StemDsp.masks(est)
        for (i in 0 until n) assertEquals(1f, m.sumOf { it[i].toDouble() }.toFloat(), 1e-3f)
    }

    @Test fun separatedStemsSumBackToTheMix() {
        val x = stereo(22050); val s = StemDsp.stft(x)
        val est = List(4) { k -> FloatArray(s.magnitude.size) { i -> s.magnitude[i] * (k + 1) } }
        val ys = StemDsp.masks(est).map { StemDsp.inverse(s, it) }
        var err = 0.0; for (i in 4096 until x.size - 4096) err = max(err, abs(x[i] - ys.sumOf { it[i].toDouble() }))
        assertTrue("stems must sum to the mix (err $err)", err < 1e-3)
    }
}
