package com.studiospark.app.eartraining

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class EarTheoryTest {
    @Test fun intervalsCoverUnisonToOctave() {
        assertEquals((0..12).toList(), Interval.entries.map { it.semitones })
        assertEquals(Interval.PERFECT_5TH, Interval.ofSemitones(7))
    }
    @Test fun intervalNotesByDirection() {
        assertEquals(listOf(60, 67), Question.IntervalQ(60, Interval.PERFECT_5TH, Direction.ASCENDING).notes)
        assertEquals(listOf(67, 60), Question.IntervalQ(60, Interval.PERFECT_5TH, Direction.DESCENDING).notes)
        val h = Question.IntervalQ(60, Interval.MAJOR_3RD, Direction.HARMONIC)
        assertEquals(listOf(60, 64), h.notes); assertEquals(Layout.TOGETHER, h.layout)
        assertEquals(Layout.SEQUENTIAL, Question.IntervalQ(60, Interval.OCTAVE, Direction.DESCENDING).layout)
    }
    @Test fun chordSpellings() {
        assertEquals(listOf(60, 64, 67), Question.ChordQ(60, ChordQuality.MAJOR).notes)
        assertEquals(listOf(57, 60, 64), Question.ChordQ(57, ChordQuality.MINOR).notes)
        assertEquals(listOf(59, 62, 65), Question.ChordQ(59, ChordQuality.DIMINISHED).notes)
        assertEquals(listOf(60, 64, 68), Question.ChordQ(60, ChordQuality.AUGMENTED).notes)
        assertEquals(listOf(55, 59, 62, 65), Question.ChordQ(55, ChordQuality.DOMINANT_7).notes)
        assertEquals(listOf(60, 64, 67, 71), Question.ChordQ(60, ChordQuality.MAJOR_7).notes)
        assertEquals(listOf(62, 65, 69, 72), Question.ChordQ(62, ChordQuality.MINOR_7).notes)
    }
    @Test fun identifyRoundTripsEveryQuality() {
        for (q in ChordQuality.entries) for (root in 40..70) assertEquals(q, ChordQuality.identify(Question.ChordQ(root, q).notes.shuffled(Random(root))))
        assertNull(ChordQuality.identify(listOf(60, 61, 62)))
    }
    @Test fun pitchMath() {
        assertEquals(440.0, Pitch.midiToHz(69.0), 1e-9)
        assertEquals(261.6256, Pitch.midiToHz(60.0), 1e-3)
        assertEquals(57.0, Pitch.hzToMidi(220.0), 1e-9)
        assertEquals("C4", Pitch.name(60)); assertEquals("A#2", Pitch.name(46))
    }
    @Test fun generatorRespectsSettingsAndRange() {
        val g = QuestionGenerator(Random(1))
        val s = QuizSettings(intervals = setOf(Interval.MAJOR_3RD, Interval.PERFECT_5TH), directions = setOf(Direction.DESCENDING))
        repeat(200) { val q = g.next(s) as Question.IntervalQ
            assertTrue(q.interval in s.intervals); assertEquals(Direction.DESCENDING, q.direction); assertTrue(q.root in 48..64) }
        val c = QuizSettings(mode = QuizMode.CHORDS, chords = ChordQuality.TRIADS.toSet())
        repeat(200) { val q = g.next(c) as Question.ChordQ; assertTrue(q.quality in ChordQuality.TRIADS); assertTrue(q.notes.max() <= 72) }
    }
    @Test fun generatorCoversEverythingAndAvoidsImmediateRepeats() {
        val g = QuestionGenerator(Random(7)); val s = QuizSettings()
        val seen = HashSet<Interval>(); val dirs = HashSet<Direction>(); var prev: Question? = null
        repeat(2000) { val q = g.next(s) as Question.IntervalQ; seen += q.interval; dirs += q.direction; assertNotEquals(prev, q); prev = q }
        assertEquals(Interval.entries.toSet(), seen); assertEquals(Direction.entries.toSet(), dirs)
    }
    @Test fun emptySelectionsFallBackToAll() {
        val s = QuizSettings(intervals = emptySet(), directions = emptySet(), chords = emptySet())
        assertEquals(13, s.intervalPool().size); assertEquals(3, s.directionPool().size); assertEquals(7, s.chordPool().size)
    }
    @Test fun unisonIsNeverAskedDescendingOrHarmonic() {
        val g = QuestionGenerator(Random(3)); val s = QuizSettings(intervals = setOf(Interval.UNISON))
        repeat(50) { assertEquals(Direction.ASCENDING, (g.next(s) as Question.IntervalQ).direction) }
    }
    @Test fun judgeIgnoresDirectionAndWrongTypes() {
        val q = Question.IntervalQ(50, Interval.TRITONE, Direction.HARMONIC)
        assertTrue(Judge.isCorrect(q, Interval.TRITONE)); assertFalse(Judge.isCorrect(q, Interval.PERFECT_4TH)); assertFalse(Judge.isCorrect(q, ChordQuality.MAJOR))
        assertTrue(Judge.isCorrect(Question.ChordQ(60, ChordQuality.MINOR_7), ChordQuality.MINOR_7))
    }
}
