package com.studiospark.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.studiospark.app.audio.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.math.abs
import kotlin.math.sqrt

/** 2.1 milestone 5: recorded CC0 (VCSL) snare/hat/cymbal layers are a drop-in for the synthesized ones. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DrumSampleTests {
    private val assets get() = ApplicationProvider.getApplicationContext<android.app.Application>().assets
    private val replaced = listOf("snare-0", "snare-1", "rimshot-0", "closed_hat-0", "open_hat-0", "hat_pedal-0", "crash-0", "ride-0", "ride_bell-0")

    @Test fun sameNamesFiveLayersMonoPcm() {
        val names = assets.list("original_drums")!!.toSet()
        assertEquals("135 drum layers, unchanged count", 135, names.count { it.endsWith(".wav") })
        for (k in replaced) for (l in 1..5) { val f = "original_drums/$k-$l.wav"; assertTrue(f, "$k-$l.wav" in names)
            val pcm = assets.open(f).use { Wav.parse(it.readBytes()) }; assertEquals(1, pcm.ch); assertEquals(44100, pcm.sr); assertTrue(pcm.frames > 44100 / 4) }
    }

    @Test fun layersGetLouderAndNeverClip() {
        fun rms(k: String, l: Int): Double { val s = assets.open("original_drums/$k-$l.wav").use { Wav.parse(it.readBytes()) }.samples; val n = minOf(s.size, 5292); return sqrt((0 until n).sumOf { (s[it] * s[it]).toDouble() } / n) }
        for (k in replaced) { val r = (1..5).map { rms(k, it) }; for (i in 0 until 4) assertTrue("$k layer ${i + 2} louder than ${i + 1}: $r", r[i + 1] > r[i])
            for (l in 1..5) assertTrue(assets.open("original_drums/$k-$l.wav").use { Wav.parse(it.readBytes()) }.samples.maxOf { abs(it) } <= 0.9f) }
    }

    @Test fun provenanceRecordsVerifiedCc0Source() {
        val prov = assets.open("original_drums/PROVENANCE.txt").use { it.readBytes().decodeToString() }
        assertTrue(prov.contains("sgossner/VCSL")); assertTrue(prov.contains("CC0 1.0 Universal")); assertTrue(prov.contains("c1ea7bcc3c7309650ab0da9d15c9cd1fbc4a4c7e"))
        for (k in replaced) for (l in 1..5) assertTrue("$k-$l listed", prov.contains("$k-$l.wav  <=  VCSL"))
        assertFalse(prov.contains("808", ignoreCase = false) && !prov.contains("No 808"))
        assertTrue(assets.open("original_drums/VCSL-LICENSE-CC0.txt").use { it.readBytes().decodeToString() }.contains("CC0 1.0 Universal"))
    }

    @Test fun bankLoadsRecordedLayersOnDemandOnly() {
        val bank = SampleBank(assets)
        assertNull("nothing preloaded", bank.drum("snare-0", 100, 0))
        bank.ensureBlocking("drums")
        for (k in replaced) for (v in listOf(10, 64, 127)) assertNotNull("$k v$v", bank.drum(k, v, 0))
    }
}
