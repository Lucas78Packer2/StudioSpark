package com.studiospark.app

import com.studiospark.app.core.MidiNote
import com.studiospark.app.core.Project
import com.studiospark.app.core.Sessions
import com.studiospark.app.engine.shared.AudioBackend
import com.studiospark.app.engine.shared.SessionHost
import com.studiospark.app.engine.shared.SessionKind
import com.studiospark.app.dsp.RigProcessor
import com.studiospark.app.songwriter.*
import org.junit.Assert.*
import org.junit.Test

class SongwriterViewModelTests {
    private val SR = 48000
    private fun vm(h: SongwriterHandoff? = null) = SongwriterViewModel(SongwriterSession(), h, SR)

    @Test fun initialStateIsBlank() {
        val m = vm(); val s = m.state.value
        assertNull(s.keyRoot); assertNull(s.key); assertTrue(s.isBlank)
        assertTrue(s.progression.isEmpty() && s.suggestions.isEmpty() && s.voicings.isEmpty() && s.inScale.isEmpty() && s.outOfScale.isEmpty())
        assertNull(s.parts); assertNull(s.fingering); assertNull(s.capo); assertEquals(-1, s.playingBar)
    }

    @Test fun oneTapLocksOneKeyAtATime() {
        val m = vm(); m.lockRoot(7)
        assertEquals(7, m.state.value.keyRoot); assertEquals(Key(7), m.state.value.key)
        m.lockRoot(2); assertEquals("a second tap replaces the lock", 2, m.state.value.keyRoot)
        m.toggleMode(); assertEquals(Mode.MINOR, m.state.value.mode); assertEquals(Key(2, Mode.MINOR), m.state.value.key)
        assertEquals(setOf(2, 4, 5, 7, 9, 10, 0), m.state.value.inScale)
        assertEquals((0..11).toSet() - m.state.value.inScale, m.state.value.outOfScale)
    }

    @Test fun lockingSnapsExistingChordsIntoTheKey() {
        val m = vm(); m.addChord(Chord(2, Quality.MAJ)); m.lockRoot(0)
        assertEquals(Chord(2, Quality.MIN), m.state.value.progression.single())
    }

    @Test fun suggestionsAreRankedAndSessionWeighted() {
        val m = vm(); m.lockRoot(0); m.addChord(Chord(7, Quality.MAJ))
        assertEquals("V → I ranks first", Chord(0, Quality.MAJ), m.state.value.suggestions.first().chord)
        assertTrue(m.state.value.suggestions.zipWithNext().all { (a, b) -> a.score >= b.score })
        // Weighting: from I, vi (7) trails IV/V (9). After using vi a lot it should overtake them.
        val plain = vm().apply { lockRoot(0); addChord(Chord(0, Quality.MAJ)) }.state.value.suggestions.map { it.chord }
        assertTrue(plain.indexOf(Chord(9, Quality.MIN)) > 0)
        val w = vm(); w.lockRoot(0); repeat(3) { w.addChord(Chord(9, Quality.MIN)); w.addChord(Chord(0, Quality.MAJ)) }
        assertEquals(Chord(9, Quality.MIN), w.state.value.suggestions.first().chord)
    }

    @Test fun fingeringComesFromTheChordDictionary() {
        val m = vm(); m.lockRoot(0)
        val top = m.state.value.focusChord!!
        assertEquals(ChordDictionary.best(top), m.state.value.fingering)
        m.focusChord(Chord(5, Quality.MAJ))   // F: no open shape, barre voicings
        val f = m.state.value.fingering!!; assertNotNull(f.barre); assertEquals(ChordDictionary.voicings(Chord(5, Quality.MAJ)), m.state.value.voicings)
        m.nextVoicing(); assertEquals(1, m.state.value.voicingIndex)
        assertEquals(intArrayOf(-1, 3, 2, 0, 1, 0).toList(), m.fingeringFor(Chord(0, Quality.MAJ))!!.frets.toList())
    }

    @Test fun capoSuggestedForBarreHeavyProgressions() {
        val m = vm(); m.lockRoot(5); m.addChord(Chord(5, Quality.MAJ)); m.addChord(Chord(10, Quality.MAJ))
        assertNotNull(m.state.value.capo)
    }

    @Test fun melodyTapsAndFretTapsBecomeChords() {
        val m = vm(); m.lockRoot(0)
        m.tapNote(60); m.tapNote(64); m.tapNote(67); m.tapNote(72)   // C E G C = one bar
        assertEquals(4, m.state.value.melody.size); assertEquals(listOf(Chord(0, Quality.MAJ)), m.state.value.melodyChords)
        m.clearMelody(); assertTrue(m.state.value.melody.isEmpty())
        m.tapFret(0, 3); m.tapFret(1, 2); m.tapFret(3, 0); m.tapFret(5, 3)   // G B G G (string, fret)
        assertEquals(listOf(43, 47, 55, 67), m.state.value.melody.map { it.pitch })
        assertEquals(4, m.state.value.taps.size); assertEquals(Chord(7, Quality.MAJ), m.state.value.melodyChords.single())
        m.useMelodyChords(); assertEquals(listOf(Chord(7, Quality.MAJ)), m.state.value.progression)
    }

    @Test fun micDetectedNotesAreHarmonized() {
        val m = vm(); m.lockRoot(9, ); m.toggleMode()   // A minor
        val beat = (SR * 60.0 / 100).toLong()
        m.micNotes(listOf(MidiNote(57, 0, beat * 2, 100), MidiNote(60, beat * 2, beat * 2, 100)), "ok")
        assertEquals(Chord(9, Quality.MIN), m.state.value.melodyChords.single()); assertEquals("ok", m.state.value.status)
    }

    @Test fun generationFollowsTheProgression() {
        val m = vm(); assertNull("nothing to generate", m.generate())
        m.lockRoot(0); m.addChord(Chord(0, Quality.MAJ)); m.addChord(Chord(7, Quality.MAJ))
        val p = m.generate()!!
        assertEquals(Accompaniment.generate(listOf(Chord(0, Quality.MAJ), Chord(7, Quality.MAJ)), (SR * 60.0 / 100).toLong()), p)
        assertTrue(p.drums.isNotEmpty() && p.bass.isNotEmpty() && p.guitar.isNotEmpty())
        val bar = (SR * 60.0 / 100).toLong() * 4
        assertEquals("bar 2 bass on G", 7, p.bass.first { it.start >= bar }.pitch % 12)
        m.addChord(Chord(5, Quality.MAJ)); assertNull("stale parts dropped when the progression changes", m.state.value.parts)
    }

    @Test fun lyricsAreOneLinePerBarSyncedToTheTimeline() {
        val m = vm(); m.lockRoot(0); m.addChord(Chord(0, Quality.MAJ)); m.addChord(Chord(5, Quality.MAJ)); m.addChord(Chord(7, Quality.MAJ))
        m.setLyric(1, "second line")
        val bar = (SR * 60.0 / 100).toLong() * 4
        val ls = m.state.value.lyrics
        assertEquals(3, ls.size); assertEquals("second line", ls[1].text); assertEquals(bar, ls[1].startFrame); assertEquals(Chord(5, Quality.MAJ), ls[1].chord)
        assertEquals(1, m.barAt(bar + 10)); assertEquals(-1, m.barAt(bar * 3)); assertEquals(-1, m.barAt(-1))
        m.seek(bar * 2 + 5); assertTrue(m.state.value.lyrics[2].playing); assertFalse(m.state.value.lyrics[1].playing)
        m.setPlayingBar(-1); assertTrue(m.state.value.lyrics.none { it.playing })
    }

    private class Silent : AudioBackend { override fun play() {}; override fun stop() {}; override fun locate(frame: Long) {}; override fun record() {}
        override fun preview(audio: ShortArray?, rig: RigProcessor?, loop: Boolean) {} }

    @Test fun handoffOnlyOnSendAndStudioStaysIsolated() {
        val studio: Project = Sessions.blank()
        val host = SessionHost({ studio }, { Silent() })
        var sent = 0
        val m = vm { s -> sent++; SongwriterToStudio.send(s, host, studio, "pads_main", SR) }
        m.lockRoot(0); m.addChord(Chord(0, Quality.MAJ)); m.addChord(Chord(9, Quality.MIN)); m.setLyric(0, "hello"); m.generate(); m.tapNote(60)
        assertEquals("nothing reaches Studio before the button", 0, studio.tracks.size); assertEquals(0, sent)
        assertTrue(m.sendToStudio()); assertEquals(1, sent)
        assertEquals(listOf("Songwriter · Drums", "Songwriter · Bass", "Songwriter · Guitar", "Songwriter · Lyrics"), studio.tracks.map { it.name })
        assertEquals(listOf("hello", ""), studio.tracks.last().lyrics)
        val sw = host.peek(SessionKind.SONGWRITER)!!
        assertNotSame(sw.project, studio)
        // later Songwriter edits never leak into the Studio copies
        sw.tracks[1].notes.clear(); sw.setFader(sw.tracks[1].id, -20f)
        assertTrue(studio.tracks[1].notes.isNotEmpty()); assertEquals(0f, studio.tracks[1].volumeDb)
        assertTrue("no amp/pedal added by Songwriter", studio.tracks.all { it.rig.slots.map { s -> s.gearId } == com.studiospark.app.dsp.RigPresets.defaultFor(it.family).slots.map { s -> s.gearId } })
        assertFalse("empty progression sends nothing", vm { sent++ }.sendToStudio())
    }
}
