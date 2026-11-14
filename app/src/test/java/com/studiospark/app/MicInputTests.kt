package com.studiospark.app

import android.Manifest
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.studiospark.app.audio.*
import com.studiospark.app.core.*
import com.studiospark.app.ui.StudioVM
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** Scripted recorder: behaviour per (config, device) decided by the test. */
private class FakeRecorder(val cfg: MicConfig, val dev: Int?, var init: Boolean = true, var starts: Boolean = true,
                           var reads: MutableList<Int> = mutableListOf(), var level: Float = 0.25f) : MicRecorder {
    var released = false; private var ph = 0.0
    override val initialized get() = init
    override fun start() = starts
    override val bufferFrames = 96
    override fun read(dst: FloatArray): Int {
        val n = if (reads.isNotEmpty()) reads.removeAt(0) else dst.size
        if (n > 0) for (i in 0 until n) { dst[i] = (level * sin(ph)).toFloat(); ph += 2 * PI * 440 / cfg.rate }
        return n
    }
    override fun release() { released = true }
}

private class Harness(val behave: (MicConfig, Int?) -> FakeRecorder?) {
    val tried = mutableListOf<Pair<MicConfig, Int?>>(); val made = mutableListOf<FakeRecorder>(); val logs = mutableListOf<String>()
    val session = MicSession(ENGINE_SR, { c, d, _ -> tried += c to d; behave(c, d)?.also { made += it } ?: throw UnsupportedOperationException("refused") }, { logs += it })
    val out = mutableListOf<Float>()
    fun tick(need: Boolean = true, pref: Int? = null, now: Long = 0) = session.tick(need, pref, now) { b, n -> for (i in 0 until n) out += b[i] }
}

/** Pure JVM tests of the 2.0.3 mic fallback ladder, 16-bit conversion, retry/back-off, read-error reopen and silence watchdog. */
class MicInputTests {
    private val vp = MediaRecorder.AudioSource.VOICE_PERFORMANCE; private val mic = MediaRecorder.AudioSource.MIC

    @Test fun ladderOrderIsFloatThen16BitThenMicThenAltRate() {
        assertEquals(listOf(MicConfig(vp, true, 48000), MicConfig(vp, false, 48000), MicConfig(mic, false, 48000), MicConfig(mic, false, 44100)), MicLadder.configs(48000))
        assertEquals(MicConfig(mic, false, 48000), MicLadder.configs(44100).last())
    }

    @Test fun pcm16ConvertsByDividingBy32768() {
        val s = shortArrayOf(0, 16384, -16384, 32767, -32768, 1); val f = FloatArray(6); Pcm16.toFloat(s, 6, f)
        assertArrayEquals(floatArrayOf(0f, 0.5f, -0.5f, 32767 / 32768f, -1f, 1 / 32768f), f, 0f)
    }

    @Test fun pixelRefusesFloatPerformanceMode_fallsBackTo16Bit() {
        val h = Harness { c, d -> if (c.float) null else FakeRecorder(c, d) }
        assertTrue(h.tick()); assertEquals(MicConfig(vp, false, 48000), h.session.config); assertNull(h.session.error)
        assertEquals(listOf(MicConfig(vp, true, 48000), MicConfig(vp, false, 48000)), h.tried.map { it.first })
        assertTrue(h.out.any { it != 0f }); assertTrue(h.logs.any { it.contains("PCM_FLOAT") && it.contains("failed") })
    }

    @Test fun notInitialized_notRecording_andEmptyFirstRead_allFallThroughToPlainMic() {
        val h = Harness { c, d -> FakeRecorder(c, d).apply {
            when { c.float -> init = false; c.source == vp -> starts = false; c.rate == 48000 -> reads = mutableListOf(0) } } }
        assertTrue(h.tick()); assertEquals(MicConfig(mic, false, 44100), h.session.config)
        assertTrue("failed recorders must be released", h.made.dropLast(1).all { it.released }); assertFalse(h.made.last().released)
        // 44.1 kHz is resampled to the engine rate: ~96 in → ~104 out
        assertTrue(h.out.size in 100..106)
    }

    @Test fun preferredDeviceFailsEverywhere_retriesOnDefaultDevice() {
        val h = Harness { c, d -> if (d == 7) null else FakeRecorder(c, d) }
        assertTrue(h.tick(pref = 7)); assertNull(h.session.device)
        assertEquals(MicLadder.configs(ENGINE_SR).size + 1, h.tried.size); assertTrue(h.tried.take(4).all { it.second == 7 })
    }

    @Test fun totalFailure_backsOffEvery2s_givesUpAfter5_andExposesReason() {
        val h = Harness { _, _ -> null }
        assertFalse(h.tick(now = 0)); val err = h.session.error
        assertNotNull(err); assertTrue(err!!, err.contains("attempt 1 of 5") && err.contains("refused"))
        assertFalse(h.tick(now = 1000)); assertEquals(4, h.tried.size)          // still backing off
        var t = 2000L; repeat(10) { h.tick(now = t); t += 2000 }
        assertEquals(5, h.session.failures); assertEquals(5 * 4, h.tried.size)   // no silent forever-retry
        assertTrue(h.session.error!!.contains("after 5 attempts"))
        h.tick(need = false); assertNull(h.session.error)                         // disarm clears; re-arm retries
        assertFalse(h.tick(now = t)); assertEquals(6 * 4, h.tried.size)
    }

    @Test fun errorClearsWhenInputSucceeds() {
        var ok = false; val h = Harness { c, d -> if (ok) FakeRecorder(c, d) else null }
        h.tick(now = 0); assertNotNull(h.session.error)
        ok = true; assertTrue(h.tick(now = 2000)); assertNull(h.session.error); assertTrue(h.session.active)
    }

    @Test fun readErrorReleasesAndReopensThroughLadder() {
        val h = Harness { c, d -> FakeRecorder(c, d).apply { if (h0 == 0) reads = mutableListOf(96, 96, AudioRecord.ERROR_DEAD_OBJECT) }.also { h0++ } }
        assertTrue(h.tick()); assertTrue(h.tick()); assertFalse(h.tick(now = 10))
        assertTrue(h.made[0].released); assertFalse(h.session.active); assertTrue(h.logs.any { it.contains("ERROR_DEAD_OBJECT") })
        assertTrue(h.tick(now = 10)); assertTrue(h.session.active); assertEquals(2, h.made.size); assertNull(h.session.error)
    }

    @Test fun silenceWatchdogWarnsAfter2sOfExactZeros_andClearsOnSignal() {
        val h = Harness { c, d -> FakeRecorder(c, d, level = 0f) }
        h.tick(); var frames = 96; while (frames < ENGINE_SR * 2 - 96) { h.tick(); frames += 96 }
        assertNull(h.session.warning); h.tick(); h.tick()
        assertEquals(MicSession.SILENCE, h.session.warning)
        h.made.last().level = 0.1f; h.tick(); assertNull(h.session.warning)
    }

    @Test fun resamplerKeepsPitchAndLength() {
        val rs = LinearResampler(44100, 48000); val inp = FloatArray(96); val out = FloatArray(rs.maxOut(96)); val all = mutableListOf<Float>(); var ph = 0.0
        repeat(44100 / 96) { for (i in 0 until 96) { inp[i] = sin(ph).toFloat(); ph += 2 * PI * 1000 / 44100 }; val m = rs.process(inp, 96, out); for (i in 0 until m) all += out[i] }
        assertTrue(abs(all.size - 44064 * 48000 / 44100) <= 2)
        var zc = 0; for (i in 1 until all.size) if (all[i - 1] < 0 && all[i] >= 0) zc++
        assertTrue("1 kHz should stay ~1 kHz, zero crossings=$zc", abs(zc - 999) <= 3)
    }
    private var h0 = 0
}

/** The on-screen part: with a mic track armed and every config refused, the studio shows "Mic recording failed: …". */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MicErrorSurfacesTests {
    val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val chain: org.junit.rules.RuleChain = org.junit.rules.RuleChain.outerRule(FreshInstallRule()).around(compose)

    @Test fun armedMicTrackWithRefusedMicShowsErrorOnScreen() {
        shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>()).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val v = ViewModelProvider(compose.activity)[StudioVM::class.java].also { x -> compose.waitUntil(20_000) { x.ready } }
        v.engine.micFactory = { _, _, _ -> throw UnsupportedOperationException("Pixel refused") }
        compose.runOnIdle { v.addTrack(InstrumentLibrary.stock.first { it.id == "vox_mic" }); v.setTrack(v.project.tracks.last().id) { recArm = true } }
        assertTrue("error never surfaced", pumpUntil(20_000) { v.micError != null })
        assertTrue(v.micError!!, v.micError!!.startsWith("Mic recording failed:") && v.micError!!.contains("Pixel refused"))
        compose.waitForIdle()
        compose.onNode(androidx.compose.ui.test.hasText("Mic recording failed", substring = true)).assertExists()
        compose.runOnIdle { v.setTrack(v.project.tracks.last().id) { recArm = false } }
        assertTrue("error should clear when the mic is no longer wanted", pumpUntil(10_000) { v.micError == null })
    }

    @Test fun micErrorTextKeepsSilenceWarningAsIs() {
        assertNull(StudioVM.micErrorText(null))
        assertEquals(MicSession.SILENCE, StudioVM.micErrorText(MicSession.SILENCE))
        assertEquals("Mic recording failed: x", StudioVM.micErrorText("x"))
    }
}
