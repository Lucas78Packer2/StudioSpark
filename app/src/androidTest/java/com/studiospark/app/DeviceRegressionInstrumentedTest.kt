package com.studiospark.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.studiospark.app.core.*
import com.studiospark.app.dsp.Family
import com.studiospark.app.ui.StudioVM
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.io.File

/**
 * On-device ports of the four 1.3.3 device-test failures (Nov 11, 7:10 PM run).
 * (a) mic take empty  (b) Jam Room AppNotIdle  (c) drum workbench idle + Stop  (d) synth workbench idle + Stop
 * (Pro has no synth workbench; synths play on the Studio tab's keys surface.) Needs a phone/emulator with a working mic for (a).
 */
@RunWith(AndroidJUnit4::class)
class DeviceRegressionInstrumentedTest {
    private val wipe = object : org.junit.rules.ExternalResource() { override fun before() { val c = InstrumentationRegistry.getInstrumentation().targetContext
        File(c.filesDir, "session").deleteRecursively(); c.getSharedPreferences("studiospark", 0).edit().clear().commit() } }
    val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val chain: RuleChain = RuleChain.outerRule(GrantPermissionRule.grant(android.Manifest.permission.RECORD_AUDIO)).around(wipe).around(compose)
    private fun vm() = ViewModelProvider(compose.activity)[StudioVM::class.java].also { v -> compose.waitUntil(30_000) { v.ready } }
    private fun waitFor(ms: Long = 10_000, c: () -> Boolean) = compose.waitUntil(ms) { c() }

    @Test fun a_microphoneTakeIsNotEmpty() {
        val v = vm(); val mic = InstrumentLibrary.stock.first { it.id == "vox_mic" }
        compose.runOnIdle { v.addTrack(mic, "Vocal"); v.setTrack(v.selectedTrackId!!) { recArm = true }; v.setCountIn(0) }
        compose.runOnIdle { v.record() }
        waitFor { v.transport == TransportState.RECORDING }
        Thread.sleep(3_000)
        compose.runOnIdle { v.stop() }
        waitFor { v.project.takes.isNotEmpty() }
        val take = v.project.takes.last(); val audio = v.pool.get(take.audioId)!!
        assertTrue("take too short: ${audio.size}", audio.size > ENGINE_SR)
        assertTrue("Microphone take was empty (every sample zero)", audio.any { it.toInt() != 0 })
    }

    @Test fun b_jamRoomReachesIdleAfterAddingGear() {
        val v = vm()
        compose.onNodeWithContentDescription("Jam Room tab").performScrollTo().performClick()
        compose.runOnIdle { v.roomAdd("instrument", "gtr_0"); v.roomAdd("pedal", "mid_push"); v.roomAdd("amp", "citrus_valve"); v.roomAdd("cab", "cab_412") }
        compose.waitForIdle()   // AppNotIdle in 1.3.3 failed exactly here
        assertEquals(4, v.project.room.size)
    }

    @Test fun c_drumKitTabIdleAndStopWorks() {
        val v = vm()
        compose.onNodeWithContentDescription("Drum Kit tab").performScrollTo().performClick(); compose.waitForIdle()
        compose.onNode(hasText("Record") and hasContentDescription("Record")).performScrollTo().performClick()
        waitFor { v.transport == TransportState.RECORDING || v.transport == TransportState.COUNT_IN }
        compose.onNode(hasText("Stop") and hasContentDescription("Stop")).performScrollTo().performClick()
        waitFor { v.transport == TransportState.STOPPED }; compose.waitForIdle()
    }

    @Test fun d_synthKeysSurfaceIdleAndStopWorks() {
        val v = vm(); val synth = InstrumentLibrary.stock.first { it.family == Family.SYNTH }
        compose.runOnIdle { v.addTrack(synth) }; compose.waitForIdle()
        compose.runOnIdle { v.noteOn(60, 100) }; Thread.sleep(300); compose.runOnIdle { v.noteOff(60) }
        compose.onNodeWithContentDescription("Play").performClick(); waitFor { v.transport == TransportState.PLAYING }
        compose.onNodeWithContentDescription("Stop").performClick(); waitFor { v.transport == TransportState.STOPPED }
        compose.waitForIdle()
    }
}
