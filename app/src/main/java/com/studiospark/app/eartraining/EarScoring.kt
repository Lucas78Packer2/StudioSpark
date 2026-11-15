package com.studiospark.app.eartraining

/** Immutable score/streak record. Only the first answer to a question counts. */
data class Score(val correct: Int = 0, val answered: Int = 0, val streak: Int = 0, val bestStreak: Int = 0) {
    val accuracyPercent: Int get() = if (answered == 0) 0 else Math.round(correct * 100f / answered)
    fun record(wasCorrect: Boolean): Score = if (wasCorrect) {
        val s = streak + 1; copy(correct = correct + 1, answered = answered + 1, streak = s, bestStreak = maxOf(bestStreak, s))
    } else copy(answered = answered + 1, streak = 0)
}

data class Feedback(val correct: Boolean, val picked: Any, val message: String)

/**
 * Pure quiz state machine (no Android): current question, feedback, score.
 * [answer] is ignored once the current question has been answered, until [next].
 */
class EarSession(private val generator: QuestionGenerator = QuestionGenerator(), settings: QuizSettings = QuizSettings()) {
    var settings: QuizSettings = settings; private set
    var question: Question = generator.next(settings); private set
    var feedback: Feedback? = null; private set
    var score: Score = Score(); private set
    val answered get() = feedback != null

    fun answer(choice: Any): Feedback? {
        if (feedback != null) return null
        val ok = Judge.isCorrect(question, choice)
        score = score.record(ok)
        val msg = if (ok) "Correct — ${question.answerLabel}" else "Not quite — it was ${question.answerLabel} (you picked ${labelOf(choice)})"
        return Feedback(ok, choice, msg).also { feedback = it }
    }
    fun next(): Question { feedback = null; question = generator.next(settings); return question }
    /** Changing settings starts a fresh question; the score carries over. An unanswered question is not counted. */
    fun updateSettings(s: QuizSettings): Question { settings = s; return next() }
    fun resetScore() { score = Score() }

    companion object {
        fun labelOf(choice: Any): String = when (choice) { is Interval -> choice.label; is ChordQuality -> choice.label; else -> choice.toString() }
    }
}
