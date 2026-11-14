package com.studiospark.app

import androidx.lifecycle.ViewModelProvider
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
import org.robolectric.annotation.Config
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** 2.1 milestone 1: guitar/bass mic source, wet monitoring, "Print processed tone" (GitHub issue 1). Mixer-level, no device mic. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MicSourceTests {
    val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val chain: org.junit.rules.RuleChain = org.junit.rules.RuleChain.outerRule(FreshInstallRule()).around(compose)

    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private fun mixer() = Mixer(SampleBank(ctx.assets), AudioPool(java.io.File(ctx.cacheDir, "pool-${System.nanoTime()}")))
    /** A rig whose only effect is a fixed output gain, so "processed" is measurable. */
    private fun gainRig(db: Float) = RigProcessor(ENGINE_SR.toFloat(), Rig(outputGainDb = db, presetName = "gain"))
    private fun snap(t: Track, clips: Array<Clip> = emptyArray(), monitor: Boolean, armed: Boolean, print: Boolean, rig: RigProcessor?) =
        TrackSnap(t.id, t.family, "guitar", null, clips, emptyArray(), CharArray(0), 1f, 0f, false, false, monitor, armed, true, rig, emptyArray(), false, 0.6f, 0, printTone = print)
    private fun sine(buf: FloatArray, n: Int, ph0: Double): Double { var ph = ph0; for (i in 0 until n) { buf[i] = (0.2 * sin(ph)).toFloat(); ph += 2 * PI * 220 / ENGINE_SR }; return ph }

    private fun recordPass(print: Boolean, monitor: Boolean): Pair<ShortArray, Float> {
        val m = mixer(); val p = Project(); val t = Track(name = "Gtr", family = Family.GUITAR, instrumentId = "gtr_0", color = 0, recArm = true, useMic = true); p.tracks += t
        m.snap = MixSnap(p, listOf(snap(t, monitor = monitor, armed = true, print = print, rig = gainRig(6.0206f))))
        m.session.countInBars = 0; m.session.metronome = false; assertTrue(m.session.record(p) is TrackingSession.StartResult.Started)
        val n = 480; val out = FloatArray(2 * n); val input = FloatArray(n); var ph = 0.0; var outPk = 0f
        repeat(50) { ph = sine(input, n, ph); m.render(out, input, n); outPk = maxOf(outPk, out.maxOf { abs(it) }) }
        m.session.stop()
        return m.channel(t.id).takeCapture() to outPk
    }

    @Test fun printedCaptureIsPostRig_dryCaptureIsRawInput() {
        val (printed, _) = recordPass(print = true, monitor = true)
        val (dry, _) = recordPass(print = false, monitor = true)
        val pk = { a: ShortArray -> a.maxOf { abs(it.toInt()) } / 32768f }
        assertEquals("dry capture is the raw 0.2 input", 0.2f, pk(dry), 0.01f)
        assertEquals("printed capture carries the rig's +6 dB", 0.4f, pk(printed), 0.02f)
    }

    @Test fun printWithoutMonitorStillCapturesButStaysSilent() {
        val (cap, outPk) = recordPass(print = true, monitor = false)
        assertTrue(cap.any { it.toInt() != 0 })
        assertEquals("unmonitored print is not heard (no surprise feedback)", 0f, outPk, 1e-6f)
    }

    @Test fun printedClipBypassesRigOnPlayback() {
        fun play(printed: Boolean): Float {
            val m = mixer(); val p = Project(); val t = Track(name = "Gtr", family = Family.GUITAR, instrumentId = "gtr_0", color = 0); p.tracks += t
            m.pool.put("a", ShortArray(ENGINE_SR) { (0.25f * 32767 * sin(2 * PI * 220 * it / ENGINE_SR)).toInt().toShort() }, persist = false)
            val c = Clip(audioId = "a", start = 0, length = ENGINE_SR.toLong(), printed = printed)
            m.snap = MixSnap(p, listOf(snap(t, arrayOf(c), monitor = false, armed = false, print = false, rig = gainRig(-40f)))); m.session.metronome = false; m.session.play()
            val n = 480; val out = FloatArray(2 * n); val input = FloatArray(n); var pk = 0f
            repeat(20) { m.render(out, input, n); pk = maxOf(pk, out.maxOf { abs(it) }) }; return pk
        }
        assertTrue("dry clip goes through the rig (−40 dB)", play(false) < 0.01f)
        assertEquals("printed clip skips the rig", 0.25f, play(true), 0.02f)
    }

    @Test fun printedFlagsRoundTripAndPlaceCarriesIt() {
        val p = Project(); val t = Track(name = "Bass", family = Family.BASS, instrumentId = "bass_0", color = 0, useMic = true, printTone = false); p.tracks += t
        t.clips += Clip(audioId = "x", start = 0, length = 100, printed = true)
        p.takes += Take(audioId = "tk", trackId = t.id, start = 480, length = 100, name = "T", source = "Mic", printed = true)
        val q = ProjectJson.read(ProjectJson.write(p)); val qt = q.tracks[0]
        assertTrue(qt.useMic); assertFalse(qt.printTone); assertTrue(qt.clips[0].printed); assertTrue(q.takes[0].printed)
        // old sessions (no keys) default to instrument source and printed tone on
        val old = ProjectJson.read(ProjectJson.write(Project().also { it.tracks += Track(name = "G", family = Family.GUITAR, instrumentId = "gtr_0", color = 0) }).replace("\"useMic\":false,", "").replace("\"print\":true,", ""))
        assertFalse(old.tracks[0].useMic); assertTrue(old.tracks[0].printTone)
        val tr = TrackingSession().place(q, q.takes[0].id); assertTrue(tr.clips.last().printed)
    }

    @Test fun micRigPresetsAreGateFirstThenEqCompAmpCab() {
        for (f in listOf(Family.GUITAR, Family.BASS)) { val r = RigPresets.micFor(f)!!.build()
            assertEquals(listOf(GearKind.STOMP, GearKind.RACK, GearKind.STOMP, GearKind.AMP, GearKind.CAB), r.slots.map { it.gear.kind })
            assertTrue(r.slots[0].gearId.contains("gate")); assertEquals("rack_peq", r.slots[1].gearId); assertTrue(r.slots[2].gearId.contains("comp")) }
        assertNull(RigPresets.micFor(Family.KEYS))
        // stock defaults unchanged
        assertEquals("Citrus Crunch", RigPresets.defaultFor(Family.GUITAR).presetName); assertEquals("Tube Bass DI+Amp", RigPresets.defaultFor(Family.BASS).presetName)
    }

    @Test fun guitarSourceMicTurnsOnMicInputAndPrint() {
        val v = ViewModelProvider(compose.activity)[StudioVM::class.java].also { v -> compose.waitUntil(20_000) { v.ready } }
        compose.runOnIdle { v.addTrack(InstrumentLibrary.stock.first { it.family == Family.GUITAR && it.surface == Surface.STRINGS }) }
        val t = v.selectedTrack()!!
        assertFalse(v.mixer.snap!!.tracks.first { it.id == t.id }.micInput)
        compose.runOnIdle { v.setTrackSource(t, true) }
        val s = v.mixer.snap!!.tracks.first { it.id == t.id }
        assertTrue(s.micInput); assertTrue("print defaults on", s.printTone)
        assertEquals(RigPresets.MIC_GUITAR, v.selectedTrack()!!.rig.presetName)
        compose.runOnIdle { v.setTrackSource(v.selectedTrack()!!, false) }
        assertFalse(v.mixer.snap!!.tracks.first { it.id == t.id }.micInput)
        // vocal tracks keep recording dry (print only applies to guitar/bass mic)
        compose.runOnIdle { v.addTrack(InstrumentLibrary.stock.first { it.id == "vox_mic" }) }
        val vs = v.mixer.snap!!.tracks.first { it.id == v.selectedTrack()!!.id }; assertTrue(vs.micInput); assertFalse(vs.printTone)
    }
}
