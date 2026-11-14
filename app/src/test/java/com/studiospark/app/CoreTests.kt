package com.studiospark.app

import com.studiospark.app.core.*
import com.studiospark.app.dsp.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class TrackingTests {
    private fun project(): Project = Project().apply {
        tracks += Track(name = "Gtr", family = Family.GUITAR, instrumentId = "gtr_0", color = 0)
        tracks += Track(name = "Vox", family = Family.VOCAL, instrumentId = "vox_mic", color = 0)
    }

    @Test fun recordRequiresArmedTrack() {
        val p = project(); val s = TrackingSession(); s.countInBars = 0
        assertTrue(s.record(p) is TrackingSession.StartResult.Refused)
        assertEquals(TransportState.STOPPED, s.state)
        p.tracks[0].recArm = true
        assertTrue(s.record(p) is TrackingSession.StartResult.Started)
        assertEquals(TransportState.RECORDING, s.state)
        assertTrue(s.capturing(p))
    }

    @Test fun countInPrecedesCapture() {
        val p = project(); p.tracks[0].recArm = true; val s = TrackingSession(); s.countInBars = 1
        s.record(p); assertEquals(TransportState.COUNT_IN, s.state); assertFalse(s.capturing(p))
        s.advance(p, p.samplesPerBar.toInt()); assertEquals(TransportState.RECORDING, s.state); assertEquals(0L, s.position)
    }

    @Test fun takeListenPlaceDiscardNeverAutoPlaces() {
        val p = project(); val s = TrackingSession(); p.tracks[0].recArm = true; s.countInBars = 0; s.record(p); s.advance(p, 4800); s.stop()
        val takes = s.finishPass(p, listOf(Triple(p.tracks[0].id, "a1", 4800L), Triple(p.tracks[0].id, "a2", 4800L)), "Mic")
        assertEquals(2, s.pending(p).size)
        assertTrue("recording must not auto-place", p.tracks[0].clips.isEmpty())
        s.listen(takes[0].id); assertEquals(takes[0].id, s.previewTakeId)
        val tr = s.place(p, takes[0].id)
        assertEquals(1, tr.clips.size); assertTrue(takes[0].placed); assertNull(s.previewTakeId); assertTrue(takes[0].id in tr.playlist)
        assertEquals("a2", s.discard(p, takes[1].id)); assertTrue(s.pending(p).isEmpty())
        assertNull("placed takes are retained", s.discard(p, takes[0].id)); assertEquals(1, p.takes.size)
    }

    @Test fun micTakeCanBecomeItsOwnTrack() {
        val p = project(); val s = TrackingSession()
        val t = s.finishPass(p, listOf(Triple(null, "m1", 1000L)), "Jam Room").first()
        val n = p.tracks.size; val tr = s.place(p, t.id, null, Family.AUDIO, "Jam take")
        assertEquals(n + 1, p.tracks.size); assertEquals("Jam take", tr.name); assertEquals(1, tr.clips.size)
    }

    @Test fun punchWindowAndFlags() {
        val p = project(); p.tracks[0].recArm = true; val s = TrackingSession(); s.mode = RecordMode.PUNCH
        assertTrue(s.record(p) is TrackingSession.StartResult.Refused)
        p.punchIn = p.samplesPerBar * 2; p.punchOut = p.samplesPerBar * 3
        assertTrue(s.record(p) is TrackingSession.StartResult.Started)
        assertEquals(p.samplesPerBar, s.position)
        assertFalse(s.capturing(p))
        s.advance(p, p.samplesPerBar.toInt()); assertTrue(s.capturing(p))
        s.advance(p, p.samplesPerBar.toInt()); assertFalse(s.capturing(p))
    }

    @Test fun punchPlacementReplacesOnlyRegion() {
        val p = project(); val tr = p.tracks[0]; val bar = p.samplesPerBar
        tr.clips += Clip(audioId = "orig", start = 0, length = bar * 4)
        val s = TrackingSession(); s.mode = RecordMode.PUNCH
        val take = s.finishPass(p, listOf(Triple(tr.id, "punch", bar)), "Mic", start = bar).first()
        s.place(p, take.id)
        val sorted = tr.clips.sortedBy { it.start }
        assertEquals(3, sorted.size); assertEquals(bar, sorted[0].end); assertEquals("punch", sorted[1].audioId); assertEquals(bar * 2, sorted[2].start); assertEquals(bar * 2, sorted[2].offset)
    }

    @Test fun loopRecordMakesPassPerCycle() {
        val p = project(); p.tracks[0].recArm = true; p.loopStart = 0; p.loopEnd = p.samplesPerBar * 2
        val s = TrackingSession(); s.mode = RecordMode.LOOP; s.countInBars = 0
        assertTrue(s.record(p) is TrackingSession.StartResult.Started)
        var wraps = 0; repeat(100) { if (s.advance(p, (p.samplesPerBar / 10).toInt())) wraps++ }
        assertEquals(5, wraps); assertEquals(5, s.passIndex); assertTrue(s.position < p.loopEnd)
    }

    @Test fun loopPlaybackWraps() {
        val p = project(); p.loopStart = 1000; p.loopEnd = 5000; val s = TrackingSession(); s.loopPlayback = true; s.locate(1000); s.play()
        s.advance(p, 4500); assertEquals(1500L, s.position)
        s.loopPlayback = false; s.advance(p, 4500); assertEquals(6000L, s.position)
    }
}

class RigTests {
    private val sr = 48000f
    private fun tone(n: Int = 4096, amp: Float = 0.3f) = FloatArray(n) { (amp * sin(2 * PI * 220 * it / sr)).toFloat() }
    private fun diff(a: FloatArray, b: FloatArray) = a.indices.sumOf { abs(a[it] - b[it]).toDouble() } / a.size

    @Test fun chainOrderAndMove() {
        val r = Rig(); r.add("quiet_gate"); r.add("mid_push"); r.add("citrus_valve"); r.add("cab_412"); r.add("rack_plate")
        assertEquals(listOf("quiet_gate", "mid_push", "citrus_valve", "cab_412", "rack_plate"), r.order())
        r.move(r.slots[4].uid, 1); assertEquals("rack_plate", r.order()[1])
        r.remove(r.slots[1].uid); assertEquals(4, r.slots.size)
    }

    @Test fun processedBufferDiffersFromDryAndBypassRestoresDry() {
        val r = Rig(); r.add("mid_push"); r.add("citrus_valve"); r.add("cab_412")
        val dry = tone(); val wet = dry.copyOf(); RigProcessor(sr, r).process(wet, wet.size)
        assertTrue("rig must change the audio", diff(dry, wet) > 0.01)
        r.slots.forEach { it.enabled = false }; val byp = dry.copyOf(); RigProcessor(sr, r).process(byp, byp.size)
        assertEquals(0.0, diff(dry, byp), 1e-6)
        r.slots.forEach { it.enabled = true }; r.power = false; val off = dry.copyOf(); RigProcessor(sr, r).process(off, off.size)
        assertEquals(0.0, diff(dry, off), 1e-6)
    }

    @Test fun knobChangesSoundLive() {
        val r = Rig(); val amp = r.add("citrus_valve"); val proc = RigProcessor(sr, r)
        val a = tone(); proc.process(a, a.size)
        amp.values[0] = 1f; amp.values[3] = 1f
        val b = tone(); proc.process(b, b.size)
        assertTrue(diff(a, b) > 0.01)
    }

    @Test fun micPositionAudiblyChangesCab() {
        val noise = java.util.Random(3).let { rnd -> FloatArray(8192) { (rnd.nextFloat() * 2 - 1) * 0.3f } }
        fun run(x: Float): FloatArray { val r = Rig(); val c = r.add("cab_412"); c.values[0] = x; val b = noise.copyOf(); RigProcessor(sr, r).process(b, b.size); return b }
        val centre = run(0f); val edge = run(1f)
        fun hf(b: FloatArray) = (1 until b.size).sumOf { abs(b[it] - b[it - 1]).toDouble() }
        assertTrue("off-axis must be darker", hf(edge) < hf(centre) * 0.9)
    }

    @Test fun everyGearProcessesFinite() {
        for (g in GearCatalog.all) { val r = Rig(); r.add(g.id); val b = tone(2048); RigProcessor(sr, r).process(b, b.size); assertTrue(g.id, b.all { it.isFinite() }) }
    }

    @Test fun rigAvailableForEveryInstrumentInUploadFlow() {
        for (f in Family.values()) { assertTrue(RigAvailability.available(f)); assertTrue(RigPresets.defaultFor(f).slots.isNotEmpty()) }
        assertTrue(RigAvailability.available(Family.CUSTOM, "My hurdy-gurdy"))
        for (i in InstrumentLibrary.stock) assertTrue(i.name, RigAvailability.available(i.family, i.name))
        val r = RigPresets.defaultFor(Family.DRUMS); r.add("velvet_fuzz", 0); r.add("dual_furnace"); r.add("cab_412")
        assertTrue(r.slots.map { it.gear.kind }.containsAll(GearKind.values().toList()))
    }

    @Test fun tunerDetectsA() {
        val x = FloatArray(4096) { (0.5 * sin(2 * PI * 110 * it / sr)).toFloat() }
        val r = Tuner.detect(x, x.size, sr)!!; assertEquals("A2", r.note); assertTrue(abs(r.cents) < 5)
    }
}

class ProjectTests {
    @Test fun persistenceRoundTrip() {
        val p = Project(name = "Night Session", tempo = 96f, sigNum = 7, sigDen = 8)
        val t = Track(name = "Lead", family = Family.GUITAR, instrumentId = "gtr_2", color = 0xFF112233, volumeDb = -3f, pan = 0.25f, mute = true, recArm = true, tuning = "Drop D (D A D G B E)")
        t.clips += Clip(audioId = "abc", start = 4800, offset = 10, length = 9600, gainDb = -2f, rate = 1.5f, name = "Riff")
        t.notes += MidiNote(64, 0, 4800, 100); t.automation += AutoPoint(0, -6f); t.lyrics += "Line one"
        t.rig.slots[1].values[0] = 0.77f; t.rig.slots[0].enabled = false
        p.tracks += t
        p.takes += Take(audioId = "tk", trackId = t.id, start = 0, length = 100, name = "Take 1", source = "Mic").also { it.notes += MidiNote(60, 0, 10, 90) }
        p.customKits += DrumKits.addPiece(DrumKits.studio, "crash-0").copy(id = "mine", name = "My Kit", stick = Stick.BRUSHES, custom = true)
        p.room += RoomItem("r1", "amp", "citrus_valve", 0.3f, 0.4f); p.cords += Cord("guitar", "r1"); p.loopEnd = 999; p.punchIn = 5; p.punchOut = 50
        val q = ProjectJson.read(ProjectJson.write(p))
        assertEquals(ProjectJson.write(p), ProjectJson.write(q))
        val t2 = q.tracks[0]
        assertEquals("Night Session", q.name); assertEquals(7, q.sigNum); assertEquals(1.5f, t2.clips[0].rate); assertEquals(0.77f, t2.rig.slots[1].values[0], 1e-6f); assertFalse(t2.rig.slots[0].enabled)
        assertEquals(Stick.BRUSHES, q.customKits[0].stick); assertEquals(DrumKits.studio.pieces.size + 1, q.customKits[0].pieces.size); assertEquals(1, q.takes[0].notes.size)
    }

    @Test fun undoRedo() { val u = UndoStack(); u.push("a"); u.push("b"); assertEquals("b", u.undo("c")); assertEquals("c", u.redo("b")); assertTrue(u.canUndo) }

    @Test fun midiRoundTrip() {
        val p = Project(tempo = 100f); val t = Track(name = "Keys", family = Family.KEYS, instrumentId = "keys_grand", color = 0); t.notes += MidiNote(60, 0, p.samplesPerBeat, 100); t.notes += MidiNote(64, p.samplesPerBeat, p.samplesPerBeat, 80)
        val r = MidiFile.read(MidiFile.write(p, listOf(t)))
        assertEquals(100f, r.tempo, 0.1f); assertEquals(2, r.tracks[0].second.size); assertEquals(64, r.tracks[0].second[1].pitch)
        assertEquals(p.samplesPerBeat.toDouble(), r.tracks[0].second[1].start.toDouble(), 50.0)
    }

    @Test fun drumKitAcceptsArbitraryExtraPieces() {
        var k = DrumKits.studio; repeat(5) { k = DrumKits.addPiece(k, "kick-0") }; k = DrumKits.addPiece(k, "crash-0"); k = DrumKits.addPiece(k, "low_tom-1")
        assertEquals(DrumKits.studio.pieces.size + 7, k.pieces.size); assertEquals(k.pieces.size, k.pieces.map { it.id }.toSet().size); assertEquals("every sounding zone has its own MIDI note (hardware has none)", k.pieces.count { it.sounding }, k.pieces.filter { it.sounding }.map { it.midi }.toSet().size)
    }

    @Test fun guitarTuningsArePitchCorrect() {
        assertEquals(listOf(40, 45, 50, 55, 59, 64), Tunings.guitar[0].midi)
        assertEquals(82.41f, Tuner.midiToHz(40f), 0.05f); assertEquals(329.63f, Tuner.midiToHz(64f), 0.05f)
        assertEquals(38, Tunings.guitar[1].midi[0]); assertEquals(36, Tunings.guitar[2].midi[0])
    }

    @Test fun signalGraphBlocksFeedback() {
        val g = SignalGraph(); assertTrue(g.connect("guitar", "pedal")); assertTrue(g.connect("pedal", "amp")); assertFalse(g.connect("amp", "guitar")); assertFalse(g.connect("amp", "amp"))
        assertEquals(listOf("guitar", "pedal", "amp"), g.chainFrom("guitar")); g.remove("pedal"); assertEquals(listOf("guitar", "amp"), g.chainFrom("guitar"))
    }
}

class ImportPolicyTests {
    private fun rejected(u: String, n: String? = null, m: String? = null) = ImportPolicy.classify(u, n, m) is ImportPolicy.Decision.Reject
    @Test fun rejectsUnsupportedSources() {
        assertTrue(rejected("spotify:track:123")); assertTrue(rejected("https://open.spotify.com/track/1")); assertTrue(rejected("magnet:?xt=urn:btih:abc"))
        assertTrue(rejected("content://x/1", "song.torrent")); assertTrue(rejected("content://x/2", "song.m4p")); assertTrue(rejected("https://example.com/a.mp3"))
        assertTrue(rejected("https://youtu.be/xyz")); assertTrue(rejected("content://x/3", "notes.pdf", "application/pdf"))
    }
    @Test fun acceptsLocalAuthorizedFilesWithPermissionNote() {
        val d = ImportPolicy.classify("content://media/1", "riff.wav", "audio/wav"); assertTrue(d is ImportPolicy.Decision.Accept); assertEquals(ImportPolicy.permissionText, (d as ImportPolicy.Decision.Accept).note)
        assertTrue(ImportPolicy.classify("content://media/2", "song.mid", null) is ImportPolicy.Decision.Accept)
        assertTrue(ImportPolicy.classify("content://media/3", "take.flac", "audio/flac") is ImportPolicy.Decision.Accept)
    }
}

class AssistantTests {
    @Test fun suggestionsStayInKey() { val k = Assistant.detectKey(listOf(60, 62, 64, 65, 67, 69, 71)); assertEquals("C major", k.toString())
        Assistant.nextNotes(listOf(60, 64, 67)).forEach { assertTrue(((it % 12) + 12) % 12 in Assistant.scale(k)) }
        assertEquals(4, Assistant.chordProgression(k, "happy").size); assertTrue(Assistant.lyrics("night drive", "rock").size > 10) }
}
