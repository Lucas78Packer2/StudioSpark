package com.studiospark.app

import com.studiospark.app.core.*
import com.studiospark.app.dsp.Family
import com.studiospark.app.dsp.RigProcessor
import com.studiospark.app.engine.shared.*
import org.junit.Assert.*
import org.junit.Test

class SharedEngineTests {
    private class FakeBackend : AudioBackend {
        val calls = mutableListOf<String>()
        override fun play() { calls += "play" }; override fun stop() { calls += "stop" }; override fun locate(frame: Long) { calls += "locate $frame" }
        override fun record() { calls += "record" }
        override fun preview(audio: ShortArray?, rig: RigProcessor?, loop: Boolean) { calls += "preview ${audio?.size}" }
    }
    private var built = 0
    private val studio = Sessions.blank()
    private fun host(gear: GearLibrary = GearLibrary()) = SessionHost({ studio }, { built++; FakeBackend() }, gear)

    @Test fun blankLaunchCreatesNothing() {
        var catalogLoads = 0
        val h = host(GearLibrary { catalogLoads++; GearLibrary.defaultCatalog() })
        assertTrue(h.createdKinds().isEmpty()); assertFalse(h.engine.isCreated()); assertEquals(0, built)
        assertFalse(h.gear.isLoaded()); assertEquals(0, catalogLoads); assertTrue(h.gear.loadedGear().isEmpty())
        h.engine.stop(); h.engine.preview(null)
        assertEquals("stopping/clearing never starts the engine", 0, built)
        val sw = h.session(SessionKind.SONGWRITER)
        assertTrue("a new session opens with empty strips", sw.tracks.isEmpty()); assertFalse(h.gear.isLoaded()); assertEquals(0, built)
    }

    @Test fun engineIsOneLazySharedInstance() {
        val h = host(); h.engine.play(); h.engine.preview(ShortArray(4)); h.engine.record(); h.engine.locate(9)
        assertEquals(1, built); assertEquals(1, h.engine.createCount)
        assertEquals(listOf("play", "preview 4", "record", "locate 9"), (h.engine.backend() as FakeBackend).calls)
    }

    @Test fun sessionsAreIsolated() {
        val h = host()
        val imp = h.session(SessionKind.IMPORT); val sw = h.session(SessionKind.SONGWRITER); val st = h.session(SessionKind.STUDIO)
        assertSame(studio, st.project); assertNotSame(imp.project, sw.project); assertNotSame(studio, imp.project); assertNotSame(studio, sw.project)
        val t = imp.addTrack("Stem · Drums", Family.DRUMS, "pads_main"); imp.setFader(t.id, -6f); imp.setPan(t.id, 0.5f)
        sw.addTrack("Bass", Family.BASS, "bass_0", listOf(MidiNote(40, 0, 100, 90)))
        assertEquals(0, studio.tracks.size); assertEquals(1, imp.tracks.size); assertEquals(1, sw.tracks.size)
        sw.clear(); assertEquals(1, imp.tracks.size)
        h.close(SessionKind.IMPORT); assertTrue(h.session(SessionKind.IMPORT).tracks.isEmpty())
        assertSame(h.session(SessionKind.SONGWRITER), h.session(SessionKind.SONGWRITER))
    }

    @Test fun gearLoadsOnlyWhenUserAddsIt() {
        var loads = 0
        val h = host(GearLibrary { loads++; GearLibrary.defaultCatalog() })
        val sw = h.session(SessionKind.SONGWRITER); val t = sw.addTrack("Guitar", Family.GUITAR, "gtr_0")
        assertEquals("adding a track loads no gear", 0, loads)
        val amp = GearLibrary.defaultCatalog().amps.first(); val pedal = GearLibrary.defaultCatalog().pedals.first()
        val before = t.rig.slots.size
        sw.addGear(t.id, amp.id); sw.addGear(t.id, pedal.id)
        assertEquals("loaded once, shared", 1, loads); assertEquals(before + 2, t.rig.slots.size)
        assertEquals(setOf(amp.id, pedal.id), h.gear.loadedGear())
        val imp = h.session(SessionKind.IMPORT); val t2 = imp.addTrack("x", Family.AUDIO, "vox_mic"); imp.setInstrument(t2.id, "keys_grand")
        assertEquals("same universal library for every tab", 1, loads); assertEquals(Family.KEYS, t2.family)
        try { sw.addGear(t.id, "no_such_gear"); fail() } catch (_: IllegalArgumentException) {}
        sw.removeTrack(t.id); assertFalse(amp.id in h.gear.loadedGear())
    }

    @Test fun handoffIsALosslessDeepCopy() {
        val h = host(); val sw = h.session(SessionKind.SONGWRITER)
        val t = sw.addTrack("Lead", Family.GUITAR, "gtr_3", listOf(MidiNote(64, 0, 480, 100)), listOf(Clip(audioId = "a1", start = 10, length = 500, name = "take", gainDb = -2f)), listOf("line one", "line two"))
        t.pan = -0.3f; t.volumeDb = -4.5f; t.kitId = null; t.tuning = "Drop D"; t.automation.add(AutoPoint(0, -3f)); t.automationOn = true
        val amp = GearLibrary.defaultCatalog().amps.first().id; sw.addGear(t.id, amp); t.rig.slots.last().values[0] = 0.91f; t.rig.inputGainDb = 3f
        SharedEngine.setCaptureMode(t, CaptureMode.MONITOR_FX_RECORD_DRY)
        assertTrue("no handoff without the trigger's session", h.handoffToStudio(HandoffTrigger.IMPORT_COMMIT).isEmpty())
        val c = h.handoffToStudio(HandoffTrigger.SONGWRITER_SEND_TO_STUDIO).single()
        assertSame(c, studio.tracks.single())
        assertNotEquals(t.id, c.id)
        assertEquals(t.name, c.name); assertEquals(t.family, c.family); assertEquals(t.instrumentId, c.instrumentId); assertEquals(t.volumeDb, c.volumeDb); assertEquals(t.pan, c.pan)
        assertEquals(t.notes, c.notes); assertEquals(t.lyrics, c.lyrics); assertEquals(t.automation, c.automation); assertEquals(t.tuning, c.tuning); assertTrue(c.automationOn)
        assertEquals(1, c.clips.size); assertEquals("a1", c.clips[0].audioId); assertEquals(-2f, c.clips[0].gainDb); assertNotEquals(t.clips[0].id, c.clips[0].id)
        assertEquals(t.rig.order(), c.rig.order()); assertEquals(0.91f, c.rig.slots.last().values[0]); assertEquals(3f, c.rig.inputGainDb)
        assertEquals(CaptureMode.MONITOR_FX_RECORD_DRY, SharedEngine.captureMode(c))
        // nothing shared
        t.notes.clear(); t.lyrics.clear(); t.rig.slots.last().values[0] = 0f; t.clips[0].gainDb = 0f; t.rig.slots.clear()
        assertEquals(1, c.notes.size); assertEquals(2, c.lyrics.size); assertEquals(0.91f, c.rig.slots.last().values[0]); assertEquals(-2f, c.clips[0].gainDb)
    }

    @Test fun studioCannotBeHandedToItselfAndStudioSessionCannotBeCleared() {
        val h = host(); h.session(SessionKind.SONGWRITER)
        val sw = h.session(SessionKind.SONGWRITER)
        try { h.handoffToStudio(HandoffTrigger.SONGWRITER_SEND_TO_STUDIO, into = sw.project); fail() } catch (_: IllegalArgumentException) {}
        try { h.session(SessionKind.STUDIO).clear(); fail() } catch (_: IllegalStateException) {}
    }

    @Test fun printProcessedUsesTheExistingRig() {
        val rig = com.studiospark.app.dsp.Rig(outputGainDb = -6.0206f)
        val out = SharedEngine.printProcessed(ShortArray(1000) { 16000 }, rig, ENGINE_SR.toFloat())
        assertEquals(8000f, out[500].toFloat(), 2f)
    }
}
