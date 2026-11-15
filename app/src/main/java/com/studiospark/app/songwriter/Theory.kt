package com.studiospark.app.songwriter

/**
 * Songwriter theory core (2.2.0). Pure Kotlin, unit-tested on the JVM.
 * Chord dictionary: self-authored by StudioSpark (no third-party data). No "Porch" chord/scale data exists in the
 * repo or on the build machine, so the open shapes below were written by hand from standard guitar fingerings and
 * barre chords are generated from the movable E- and A-shapes.
 */
object Notes {
    val SHARP = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    val FLAT = listOf("C", "Db", "D", "Eb", "E", "F", "Gb", "G", "Ab", "A", "Bb", "B")
    fun name(pc: Int, flats: Boolean = false) = (if (flats) FLAT else SHARP)[((pc % 12) + 12) % 12]
    /** Standard tuning, low E to high e, as MIDI notes. */
    val STANDARD = intArrayOf(40, 45, 50, 55, 59, 64)
}

enum class Mode(val label: String, val steps: IntArray) { MAJOR("Major", intArrayOf(0, 2, 4, 5, 7, 9, 11)), MINOR("Minor", intArrayOf(0, 2, 3, 5, 7, 8, 10)) }

enum class Quality(val suffix: String, val intervals: IntArray) {
    MAJ("", intArrayOf(0, 4, 7)), MIN("m", intArrayOf(0, 3, 7)), DIM("dim", intArrayOf(0, 3, 6)),
    DOM7("7", intArrayOf(0, 4, 7, 10)), MAJ7("maj7", intArrayOf(0, 4, 7, 11)), MIN7("m7", intArrayOf(0, 3, 7, 10));
}

data class Chord(val root: Int, val quality: Quality) {
    val pcs get() = quality.intervals.map { (root + it) % 12 }.toSet()
    fun name(flats: Boolean = false) = Notes.name(root, flats) + quality.suffix
    fun transpose(st: Int) = copy(root = ((root + st) % 12 + 12) % 12)
}

data class Key(val root: Int, val mode: Mode = Mode.MAJOR) {
    val flats get() = (if (mode == Mode.MAJOR) root else (root + 3) % 12) in setOf(5, 10, 3, 8, 1, 6)
    val scale: Set<Int> get() = mode.steps.map { (root + it) % 12 }.toSet()
    fun inScale(midiOrPc: Int) = ((midiOrPc % 12) + 12) % 12 in scale
    /** Diatonic triads by degree 0..6 (plus the dominant 7th on V). */
    fun triad(degree: Int): Chord {
        val s = mode.steps; val r = s[degree % 7]; val third = (s[(degree + 2) % 7] - r + 12) % 12; val fifth = (s[(degree + 4) % 7] - r + 12) % 12
        val q = when { third == 4 && fifth == 7 -> Quality.MAJ; third == 3 && fifth == 7 -> Quality.MIN; else -> Quality.DIM }
        return Chord((root + r) % 12, q)
    }
    val diatonic: List<Chord> get() = (0..6).map { triad(it) }
    fun degreeOf(c: Chord): Int? = diatonic.indexOfFirst { it.root == c.root }.takeIf { it >= 0 }
    /** Snap any chord into the key: the diatonic chord on the same root, else the diatonic chord sharing most notes. */
    fun snap(c: Chord): Chord = diatonic.firstOrNull { it.root == c.root } ?: diatonic.maxBy { d -> (d.pcs intersect c.pcs).size * 10 - ((d.root - c.root + 12) % 12) }
    fun roman(c: Chord): String { val d = degreeOf(c) ?: return c.name(flats); val n = listOf("I", "II", "III", "IV", "V", "VI", "VII")[d]
        return when (c.quality) { Quality.MAJ, Quality.DOM7, Quality.MAJ7 -> n; Quality.DIM -> n.lowercase() + "°"; else -> n.lowercase() } }
}

/**
 * Next-chord ranking. The transition table is HAND-AUTHORED by StudioSpark from widely taught functional-harmony
 * conventions (e.g. V→I, IV→V, ii→V, vi→IV in pop); it is not derived from a measured song corpus and cites none.
 * Weights are relative (0–10) per scale degree, indexed [from][to], and work for major and natural minor alike.
 */
object Progressions {
    val TABLE: Array<IntArray> = arrayOf(
        //            I  ii iii IV  V  vi vii
        intArrayOf(0, 3, 2, 9, 9, 7, 1),   // from I
        intArrayOf(2, 0, 1, 3, 10, 2, 2),  // from ii
        intArrayOf(2, 1, 0, 5, 2, 8, 0),   // from iii
        intArrayOf(8, 4, 1, 0, 9, 4, 1),   // from IV
        intArrayOf(10, 1, 2, 4, 0, 7, 1),  // from V
        intArrayOf(3, 6, 2, 9, 7, 0, 1),   // from vi
        intArrayOf(9, 0, 4, 1, 2, 3, 0),   // from vii°
    )
    /** Opening chords when nothing has been played yet. */
    val START = intArrayOf(10, 2, 1, 5, 4, 7, 0)
    const val SESSION_WEIGHT = 0.6

    data class Suggestion(val chord: Chord, val score: Double, val roman: String)

    /** Ranks every diatonic chord after [current] (null = song start), boosted toward chords already in [used]. */
    fun suggest(key: Key, current: Chord?, used: List<Chord>): List<Suggestion> {
        val from = current?.let { key.degreeOf(key.snap(it)) }
        val base = if (from == null) START else TABLE[from]
        val counts = used.map { key.snap(it) }.groupingBy { it.root }.eachCount(); val maxC = counts.values.maxOrNull() ?: 0
        return key.diatonic.mapIndexed { d, c ->
            val boost = if (maxC == 0) 0.0 else SESSION_WEIGHT * (counts[c.root] ?: 0) / maxC
            Suggestion(c, base[d] * (1.0 + boost), key.roman(c))
        }.filter { it.score > 0 && (current == null || it.chord.root != key.snap(current).root) }.sortedByDescending { it.score }
    }
}
