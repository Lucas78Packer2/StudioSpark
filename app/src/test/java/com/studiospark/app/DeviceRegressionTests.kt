package com.studiospark.app

import android.Manifest
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.studiospark.app.audio.*
import com.studiospark.app.core.*
import com.studiospark.app.dsp.*
import com.studiospark.app.ui.StudioVM
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlinx.coroutines.launch

/**
 * JVM (Robolectric) versions of the four 1.3.3 device-test failures (AIMusicStudio era, Nov 11 7:10 PM run), ported to StudioSpark-Pro:
 * (a) "Microphone take was empty"  (b) Jam Room AppNotIdle  (c) drum workbench idle + Stop  (d) synth workbench idle + Stop.
 * Pro has no separate synth workbench: synths (Saw Lead etc.) play on the Studio tab's keys surface, which is tested instead.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DeviceRegressionTests {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun vm() = ViewModelProvider(compose.activity)[StudioVM::class.java].also { v -> compose.waitUntil(20_000) { v.ready } }

    /** Counts Compose snapshot state changes over [ms] of pumped time with the transport stopped: a perpetual ticker/animation shows up here. */
    private fun stateChangesWhileIdle(ms: Long): Int {
        compose.waitForIdle()
        val n = AtomicInteger(0); val h = Snapshot.registerGlobalWriteObserver { n.incrementAndGet() }
        // advance the real main-looper clock (ViewModel ticker delays live there) and the Compose frame clock together
        try { repeat((ms / 50).toInt()) { shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(50)); compose.mainClock.advanceTimeBy(50); Snapshot.sendApplyNotifications() } }
        finally { h.dispose() }
        return n.get()
    }

    // ---------------------------------------------------------------- (a) microphone take not empty
    @Test fun a_micTakeHasNonZeroSamplesNotJustAHeader() {
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        val mixer = Mixer(SampleBank(ctx.assets), AudioPool(java.io.File(ctx.cacheDir, "pool")))
        val p = Project(); val t = Track(name = "Vocal", family = Family.VOCAL, instrumentId = "vox_mic", color = 0, recArm = true); p.tracks += t
        mixer.snap = MixSnap(p, listOf(TrackSnap(t.id, t.family, "mic", null, emptyArray(), emptyArray(), CharArray(0), 1f, 0f, false, false, false, true, true,
            null, emptyArray(), false, 0.6f, 0)))
        mixer.session.countInBars = 0
        assertTrue(mixer.session.record(p) is TrackingSession.StartResult.Started)
        val n = 480; val out = FloatArray(2 * n); val input = FloatArray(n); var ph = 0.0
        repeat(100) { for (i in 0 until n) { input[i] = (0.3 * sin(ph)).toFloat(); ph += 2 * PI * 440 / ENGINE_SR }; mixer.render(out, input, n) }   // 1 s of "mic"
        mixer.session.stop()
        val take = mixer.channel(t.id).takeCapture()
        assertEquals(n * 100, take.size)
        val nonZero = take.count { it.toInt() != 0 }
        assertTrue("microphone take was empty ($nonZero non-zero of ${take.size})", nonZero > take.size / 2)
        assertTrue(take.maxOf { abs(it.toInt()) } > 32767 * 0.2)
        // and it encodes to a WAV that is more than a header
        val wav = Wav.encode(take); assertTrue(wav.size > 44); assertTrue(wav.drop(44).any { it.toInt() != 0 })
        // silence in => silence out (the test above is not passing trivially)
        mixer.session.record(p); repeat(10) { input.fill(0f); mixer.render(out, input, n) }; mixer.session.stop()
        assertTrue(mixer.channel(t.id).takeCapture().all { it.toInt() == 0 })
    }

    @Test fun a_micPermissionDeniedNeverOpensInputAtLaunch() {
        shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>()).denyPermissions(Manifest.permission.RECORD_AUDIO)
        val v = vm(); assertFalse(v.engine.inputActive); assertFalse(v.engine.wantInput)
    }

    // ---------------------------------------------------------------- (b) Jam Room reaches idle
    @Test fun b_jamRoomOpensAddsGearAndGoesIdle() {
        val v = vm()
        compose.onNodeWithContentDescription("Jam Room tab").performScrollTo().performClick(); compose.waitForIdle()
        assertTrue(v.roomReady); assertNotNull(v.mixer.roomRig)
        compose.runOnIdle { v.roomAdd("instrument", "gtr_0"); v.roomAdd("pedal", "mid_push"); v.roomAdd("amp", "citrus_valve"); v.roomAdd("cab", "cab_412") }
        compose.waitForIdle()
        assertEquals(4, v.project.room.size)
        assertTrue("room never went idle: perpetual state changes", stateChangesWhileIdle(3_000) <= 1)
    }

    // ---------------------------------------------------------------- (c) Drum Kit tab idle + Stop
    @Test fun c_drumKitTabGoesIdleAndStopWorks() {
        val v = vm()
        compose.onNodeWithContentDescription("Drum Kit tab").performScrollTo().performClick(); compose.waitForIdle()
        assertTrue(stateChangesWhileIdle(2_000) <= 1)
        compose.onNodeWithContentDescription("Play").performClick()
        assertTrue("transport never started", pumpUntil(10_000) { v.transport == TransportState.PLAYING })
        compose.onNodeWithContentDescription("Stop").performClick()
        assertTrue("Stop did not stop the transport", pumpUntil(10_000) { v.transport == TransportState.STOPPED })
        // drum-tab Record → its own Stop button
        compose.onNode(hasText("Record") and hasContentDescription("Record")).performScrollTo().performClick()
        assertTrue(pumpUntil(10_000) { v.transport == TransportState.RECORDING || v.transport == TransportState.COUNT_IN })
        compose.onNode(hasText("Stop") and hasContentDescription("Stop")).performScrollTo().performClick()
        assertTrue("drum Stop button did not stop recording", pumpUntil(10_000) { v.transport == TransportState.STOPPED })
        compose.waitForIdle(); assertTrue(stateChangesWhileIdle(2_000) <= 2)
    }

    // ---------------------------------------------------------------- (d) synth (keys surface) idle + Stop
    @Test fun d_synthKeysSurfaceGoesIdleAndStopWorks() {
        val v = vm()
        val synth = InstrumentLibrary.stock.first { it.family == Family.SYNTH }
        compose.runOnIdle { v.addTrack(synth) }; compose.waitForIdle()
        val t = v.selectedTrack()!!; assertEquals(synth.id, t.instrumentId)
        assertEquals("adding the synth built exactly its rig", setOf(t.id), v.rigs.builtIds())
        compose.runOnIdle { v.noteOn(60, 100); v.noteOff(60) }
        assertTrue(stateChangesWhileIdle(2_000) <= 3)
        compose.onNodeWithContentDescription("Play").performClick()
        assertTrue(pumpUntil(10_000) { v.transport == TransportState.PLAYING })
        compose.onNodeWithContentDescription("Stop").performClick()
        assertTrue(pumpUntil(10_000) { v.transport == TransportState.STOPPED })
        compose.waitForIdle(); assertTrue(stateChangesWhileIdle(2_000) <= 2)
    }

    /** Self-check: the idle detector really catches a perpetual main-thread ticker (so the idle tests above are not passing vacuously). */
    @Test fun idleDetectorCatchesAPerpetualTicker() {
        vm(); val churn = androidx.compose.runtime.mutableIntStateOf(0)
        val job = kotlinx.coroutines.MainScope().launch { while (true) { kotlinx.coroutines.delay(100); churn.intValue++ } }
        try { assertTrue("detector missed a 100 ms ticker", stateChangesWhileIdle(2_000) >= 5) } finally { job.cancel() }
    }

    // ---------------------------------------------------------------- ticker: no state churn when nothing is happening
    @Test fun tickerStopsPublishingWhenIdle() {
        vm(); compose.waitForIdle()
        assertTrue(stateChangesWhileIdle(3_000) <= 1)
    }
}
