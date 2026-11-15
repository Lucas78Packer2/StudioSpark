package com.studiospark.app.eartraining

import kotlin.math.pow
import kotlin.random.Random

/** Interval answers, unison through octave. [semitones] is the distance between the two notes. */
enum class Interval(val semitones: Int, val label: String, val short: String) {
    UNISON(0, "Unison", "P1"), MINOR_2ND(1, "Minor 2nd", "m2"), MAJOR_2ND(2, "Major 2nd", "M2"),
    MINOR_3RD(3, "Minor 3rd", "m3"), MAJOR_3RD(4, "Major 3rd", "M3"), PERFECT_4TH(5, "Perfect 4th", "P4"),
    TRITONE(6, "Tritone", "TT"), PERFECT_5TH(7, "Perfect 5th", "P5"), MINOR_6TH(8, "Minor 6th", "m6"),
    MAJOR_6TH(9, "Major 6th", "M6"), MINOR_7TH(10, "Minor 7th", "m7"), MAJOR_7TH(11, "Major 7th", "M7"),
    OCTAVE(12, "Octave", "P8");
    companion object { fun ofSemitones(s: Int): Interval = entries.first { it.semitones == s } }
}

/** How the two interval notes are played. */
enum class Direction(val label: String, val arrow: String) { ASCENDING("Ascending", "↑"), DESCENDING("Descending", "↓"), HARMONIC("Harmonic", "≡") }

/** Chord qualities as semitone offsets from the root (root position). */
enum class ChordQuality(val label: String, val short: String, val intervals: List<Int>) {
    MAJOR("Major", "maj", listOf(0, 4, 7)), MINOR("Minor", "min", listOf(0, 3, 7)),
    DIMINISHED("Diminished", "dim", listOf(0, 3, 6)), AUGMENTED("Augmented", "aug", listOf(0, 4, 8)),
    DOMINANT_7("Dominant 7th", "7", listOf(0, 4, 7, 10)), MAJOR_7("Major 7th", "maj7", listOf(0, 4, 7, 11)),
    MINOR_7("Minor 7th", "m7", listOf(0, 3, 7, 10));
    companion object {
        val TRIADS = listOf(MAJOR, MINOR, DIMINISHED, AUGMENTED)
        /** Identifies a quality from any set of MIDI notes (root = lowest), or null if it is none of these. */
        fun identify(notes: List<Int>): ChordQuality? { if (notes.isEmpty()) return null; val r = notes.min(); val set = notes.map { it - r }.toSortedSet().toList()
            return entries.firstOrNull { it.intervals == set } }
    }
}

enum class QuizMode(val label: String) { INTERVALS("Intervals"), CHORDS("Chord quality") }

/** How notes are laid out in time when rendered. */
enum class Layout { SEQUENTIAL, TOGETHER }

object Pitch {
    fun midiToHz(midi: Double): Double = 440.0 * 2.0.pow((midi - 69.0) / 12.0)
    fun hzToMidi(hz: Double): Double = 69.0 + 12.0 * kotlin.math.ln(hz / 440.0) / kotlin.math.ln(2.0)
    private val NAMES = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    fun name(midi: Int): String = NAMES[Math.floorMod(midi, 12)] + (Math.floorDiv(midi, 12) - 1)
}

/** One quiz question. [notes] are MIDI notes in play order (or all together for [Layout.TOGETHER]). */
sealed class Question {
    abstract val notes: List<Int>
    abstract val layout: Layout
    abstract val answerLabel: String
    data class IntervalQ(val root: Int, val interval: Interval, val direction: Direction) : Question() {
        override val notes get() = when (direction) {
            Direction.ASCENDING, Direction.HARMONIC -> listOf(root, root + interval.semitones)
            Direction.DESCENDING -> listOf(root + interval.semitones, root)
        }
        override val layout get() = if (direction == Direction.HARMONIC) Layout.TOGETHER else Layout.SEQUENTIAL
        override val answerLabel get() = "${interval.label} ${direction.arrow}"
    }
    data class ChordQ(val root: Int, val quality: ChordQuality) : Question() {
        override val notes get() = quality.intervals.map { root + it }
        override val layout get() = Layout.TOGETHER
        override val answerLabel get() = "${Pitch.name(root).dropLast(1)} ${quality.label}"
    }
}

/** Settings the generator draws from. Empty sets fall back to everything. */
data class QuizSettings(
    val mode: QuizMode = QuizMode.INTERVALS,
    val intervals: Set<Interval> = Interval.entries.toSet(),
    val directions: Set<Direction> = Direction.entries.toSet(),
    val chords: Set<ChordQuality> = ChordQuality.entries.toSet(),
    val lowestRoot: Int = 48, val highestRoot: Int = 64, // C3..E4: the octave stays inside a comfortable range
) {
    val answers: List<Any> get() = when (mode) { QuizMode.INTERVALS -> intervalPool(); QuizMode.CHORDS -> chordPool() }
    fun intervalPool() = intervals.ifEmpty { Interval.entries.toSet() }.sortedBy { it.semitones }
    fun directionPool() = directions.ifEmpty { Direction.entries.toSet() }.sortedBy { it.ordinal }
    fun chordPool() = chords.ifEmpty { ChordQuality.entries.toSet() }.sortedBy { it.ordinal }
}

/** Random question source. Avoids repeating the exact previous question when there is any alternative. */
class QuestionGenerator(private val random: Random = Random.Default) {
    private var last: Question? = null
    fun next(s: QuizSettings): Question {
        require(s.lowestRoot <= s.highestRoot)
        var q: Question
        var tries = 0
        do { q = draw(s); tries++ } while (q == last && tries < 8)
        last = q; return q
    }
    private fun draw(s: QuizSettings): Question {
        val root = random.nextInt(s.lowestRoot, s.highestRoot + 1)
        return when (s.mode) {
            QuizMode.INTERVALS -> { val i = s.intervalPool().random(random)
                // Unison is the same in every direction, so it is always asked harmonically-agnostic as ascending.
                val d = if (i == Interval.UNISON) Direction.ASCENDING else s.directionPool().random(random)
                Question.IntervalQ(root, i, d) }
            QuizMode.CHORDS -> Question.ChordQ(root, s.chordPool().random(random))
        }
    }
}

/** Judges an answer. Interval answers are judged on the interval only (direction is shown, not asked). */
object Judge {
    fun isCorrect(q: Question, answer: Any): Boolean = when (q) {
        is Question.IntervalQ -> answer == q.interval
        is Question.ChordQ -> answer == q.quality
    }
}
