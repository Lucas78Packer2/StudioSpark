package com.studiospark.app.eartraining

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class EarScoringTest {
    @Test fun streakAndBest() {
        var s = Score()
        listOf(true, true, true, false, true).forEach { s = s.record(it) }
        assertEquals(4, s.correct); assertEquals(5, s.answered); assertEquals(1, s.streak); assertEquals(3, s.bestStreak); assertEquals(80, s.accuracyPercent)
        assertEquals(0, Score().accuracyPercent)
    }
    @Test fun sessionCountsOnlyFirstAnswer() {
        val e = EarSession(QuestionGenerator(Random(2)))
        val right = (e.question as Question.IntervalQ).interval
        val wrong = Interval.entries.first { it != right }
        val f = e.answer(wrong)!!
        assertFalse(f.correct); assertTrue(f.message.contains("it was")); assertNull(e.answer(right))
        assertEquals(Score(0, 1, 0, 0), e.score)
        e.next(); assertNull(e.feedback)
        assertTrue(e.answer((e.question as Question.IntervalQ).interval)!!.correct)
        assertEquals(1, e.score.streak)
    }
    @Test fun settingsChangeKeepsScoreAndSwitchesMode() {
        val e = EarSession(QuestionGenerator(Random(4)))
        e.answer((e.question as Question.IntervalQ).interval)
        e.updateSettings(QuizSettings(mode = QuizMode.CHORDS))
        assertTrue(e.question is Question.ChordQ); assertEquals(1, e.score.correct)
        assertEquals(ChordQuality.entries.toList(), e.settings.answers)
        e.resetScore(); assertEquals(Score(), e.score)
    }
}
