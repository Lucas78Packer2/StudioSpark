package com.studiospark.app

import com.studiospark.app.core.MidiNote
import com.studiospark.app.songwriter.*
import org.junit.Assert.*
import org.junit.Test

class SongwriterTests {
    private val C = Key(0); private val G = Key(7)

    @Test fun lockedKeySnapsOutOfKeyChords() {
        assertEquals(Chord(5, Quality.MAJ), C.snap(Chord(5, Quality.MIN)))      // Fm -> F in C major
        assertEquals(Chord(2, Quality.MIN), C.snap(Chord(2, Quality.MAJ)))      // D -> Dm in C major
        assertTrue(C.diatonic.contains(C.snap(Chord(1, Quality.MAJ))))          // C# snaps to something diatonic
        val s = SongwriterSession(); s.add(Chord(2, Quality.MAJ)); s.lockKey(0)
        assertEquals(Chord(2, Quality.MIN), s.progression[0])
        s.add(Chord(4, Quality.MAJ)); assertEquals(Chord(4, Quality.MIN), s.progression[1])
    }

    @Test fun suggestionsAreDiatonicAndRankedByTable() {
        val sg = Progressions.suggest(C, Chord(7, Quality.MAJ), emptyList())
        assertEquals("V goes home to I first", Chord(0, Quality.MAJ), sg.first().chord)
        assertTrue(sg.all { C.diatonic.contains(it.chord) })
        assertTrue("never suggests the current chord", sg.none { it.chord.root == 7 })
        val start = Progressions.suggest(C, null, emptyList()); assertEquals(Chord(0, Quality.MAJ), start.first().chord)
    }

    @Test fun sessionChordsAreWeightedUp() {
        val plain = Progressions.suggest(C, Chord(0, Quality.MAJ), emptyList()).map { it.chord }
        assertTrue(plain.indexOf(Chord(9, Quality.MIN)) > 0)  // vi not first from I without history
        val used = List(5) { Chord(9, Quality.MIN) } + Chord(0, Quality.MAJ)
        val boosted = Progressions.suggest(C, Chord(0, Quality.MAJ), used)
        val viPlain = Progressions.suggest(C, Chord(0, Quality.MAJ), emptyList()).first { it.chord.root == 9 }.score
        assertTrue(boosted.first { it.chord.root == 9 }.score > viPlain)
    }

    @Test fun fingeringsHaveFretsFingersAndMutes() {
        val c = ChordDictionary.best(Chord(0, Quality.MAJ))!!
        assertArrayEquals(intArrayOf(-1, 3, 2, 0, 1, 0), c.frets); assertEquals(listOf(0), c.muted); assertFalse(c.awkward)
        // every diatonic chord in every key has a voicing whose notes match the chord
        for (r in 0..11) for (ch in Key(r).diatonic) { val f = ChordDictionary.best(ch); assertNotNull("${ch.name()} missing", f)
            assertTrue("${ch.name()} voicing wrong notes", f!!.notes().map { it % 12 }.toSet().all { it in ch.pcs }) }
        val bb = ChordDictionary.best(Chord(10, Quality.MAJ))!!; assertTrue(bb.awkward); assertNotNull(bb.barre)
    }

    @Test fun capoSuggestedForBarreHeavyProgressions() {
        val prog = listOf(Chord(1, Quality.MAJ), Chord(6, Quality.MAJ), Chord(8, Quality.MAJ)) // C# F# G#
        val cp = ChordDictionary.capoFor(prog)!!
        assertTrue(cp.fret in 1..7); assertTrue(cp.shapes.all { ChordDictionary.OPEN.containsKey(it.name()) })
        assertNull("easy open progression needs no capo", ChordDictionary.capoFor(listOf(Chord(0, Quality.MAJ), Chord(7, Quality.MAJ))))
    }

    @Test fun fretboardDimmingFollowsTheScale() {
        assertTrue(G.inScale(66)); assertFalse(G.inScale(65)); assertFalse(C.inScale(61)); assertTrue(C.inScale(64))
    }

    @Test fun melodyGetsFittingChords() {
        val bar = 4000L; val mel = listOf(MidiNote(64, 0, 2000, 100), MidiNote(67, 2000, 2000, 100), MidiNote(65, 4000, 2000, 100), MidiNote(69, 6000, 2000, 100))
        val ch = Harmonizer.chordsFor(C, mel, bar)
        assertEquals(2, ch.size); assertTrue(64 % 12 in ch[0].pcs && 67 % 12 in ch[0].pcs); assertTrue(65 % 12 in ch[1].pcs && 69 % 12 in ch[1].pcs)
        val s = SongwriterSession(); s.lockKey(0); s.tapNote(60, 48000); s.tapNote(64, 48000); assertEquals(2, s.melody.size); assertEquals(1, s.melodyChords.size)
    }

    @Test fun accompanimentCoversEveryBar() {
        val prog = listOf(Chord(0, Quality.MAJ), Chord(7, Quality.MAJ), Chord(9, Quality.MIN), Chord(5, Quality.MAJ))
        val p = Accompaniment.generate(prog, 1000)
        for (b in 0..3) { val s = b * 4000L; val e = s + 4000
            assertTrue(p.drums.any { it.start in s until e }); assertTrue(p.bass.any { it.start in s until e && (it.pitch % 12) in prog[b].pcs })
            assertTrue(p.guitar.any { it.start in s until e }) }
    }

    @Test fun lyricsStayOneLinePerBar() {
        val s = SongwriterSession(); s.lockKey(0); s.add(Chord(0, Quality.MAJ)); s.add(Chord(7, Quality.MAJ)); s.setLyric(1, "hello")
        assertEquals(2, s.lyrics.size); assertEquals("hello", s.lyrics[1]); s.removeLast(); assertEquals(2, s.lyrics.size)
    }
}
