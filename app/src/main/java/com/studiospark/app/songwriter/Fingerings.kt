package com.studiospark.app.songwriter

/** One guitar voicing, low E → high e. fret -1 = muted, 0 = open. finger 0 = none/open, 1..4 = index..pinky. */
data class Fingering(val frets: IntArray, val fingers: IntArray, val barre: Int? = null, val shape: String = "open") {
    val muted get() = frets.indices.filter { frets[it] < 0 }
    val minFret get() = frets.filter { it > 0 }.minOrNull() ?: 0
    val maxFret get() = frets.maxOrNull() ?: 0
    /** Barre chords and stretches over 3 frets count as awkward (drives capo suggestions). */
    val awkward get() = barre != null || (maxFret - minFret) > 3
    fun notes(tuning: IntArray = Notes.STANDARD) = frets.indices.filter { frets[it] >= 0 }.map { tuning[it] + frets[it] }
    override fun equals(other: Any?) = other is Fingering && frets.contentEquals(other.frets) && fingers.contentEquals(other.fingers)
    override fun hashCode() = frets.contentHashCode()
}

/** Self-authored chord dictionary (see Theory.kt header for provenance). */
object ChordDictionary {
    private fun f(fr: String, fi: String, shape: String = "open") = Fingering(fr.split(",").map { it.trim().toInt() }.toIntArray(), fi.map { it.digitToInt() }.toIntArray(), shape = shape)
    /** Open-position shapes keyed by chord name (sharps). */
    val OPEN: Map<String, Fingering> = mapOf(
        "C" to f("-1,3,2,0,1,0", "032010"), "A" to f("-1,0,2,2,2,0", "001230"), "G" to f("3,2,0,0,0,3", "210004"),
        "E" to f("0,2,2,1,0,0", "023100"), "D" to f("-1,-1,0,2,3,2", "000132"), "Am" to f("-1,0,2,2,1,0", "002310"),
        "Em" to f("0,2,2,0,0,0", "023000"), "Dm" to f("-1,-1,0,2,3,1", "000231"), "E7" to f("0,2,0,1,0,0", "020100"),
        "A7" to f("-1,0,2,0,2,0", "002030"), "D7" to f("-1,-1,0,2,1,2", "000213"), "G7" to f("3,2,0,0,0,1", "320001"),
        "C7" to f("-1,3,2,3,1,0", "032410"), "B7" to f("-1,2,1,2,0,2", "021304"), "Cmaj7" to f("-1,3,2,0,0,0", "032000"),
        "Amaj7" to f("-1,0,2,1,2,0", "002130"), "Dmaj7" to f("-1,-1,0,2,2,2", "000111"), "Fmaj7" to f("-1,-1,3,2,1,0", "003210"),
        "Gmaj7" to f("3,2,0,0,0,2", "210003"), "Emaj7" to f("0,2,1,1,0,0", "031200"), "Am7" to f("-1,0,2,0,1,0", "002010"),
        "Em7" to f("0,2,0,0,0,0", "020000"), "Dm7" to f("-1,-1,0,2,1,1", "000211"), "Bdim" to f("-1,2,3,4,3,-1", "012430"),
        "Edim" to f("0,1,2,0,-1,0", "012000"), "Adim" to f("-1,0,1,2,1,-1", "001320"), "Ddim" to f("-1,-1,0,1,3,1", "000131"),
        "F#dim" to f("2,0,-1,2,1,-1", "200310"), "G#dim" to f("4,2,0,4,3,-1", "310420"), "C#dim" to f("-1,4,2,0,2,0", "041020"),
    )
    // Movable barre templates: offsets from the barre fret, root on low E (E-shape) or A string (A-shape).
    private val E_SHAPE = mapOf(Quality.MAJ to ("0,2,2,1,0,0" to "134211"), Quality.MIN to ("0,2,2,0,0,0" to "134111"), Quality.DOM7 to ("0,2,0,1,0,0" to "131211"),
        Quality.MIN7 to ("0,2,0,0,0,0" to "131111"), Quality.MAJ7 to ("0,-9,1,1,0,-9" to "103400"))
    private val A_SHAPE = mapOf(Quality.MAJ to ("-9,0,2,2,2,0" to "013331"), Quality.MIN to ("-9,0,2,2,1,0" to "013421"), Quality.DOM7 to ("-9,0,2,0,2,0" to "013141"),
        Quality.MIN7 to ("-9,0,2,0,1,0" to "013121"), Quality.MAJ7 to ("-9,0,2,1,2,0" to "013241"), Quality.DIM to ("-9,0,1,2,1,-9" to "012430"))

    private fun barre(c: Chord, lowString: Int, tpl: Pair<String, String>?, name: String): Fingering? {
        tpl ?: return null
        val open = if (lowString == 0) 4 else 9; var fret = ((c.root - open) % 12 + 12) % 12; if (fret == 0) fret = 12
        val off = tpl.first.split(",").map { it.trim().toInt() }
        val frets = IntArray(6) { if (off[it] < -1) -1 else fret + off[it] }
        return Fingering(frets, tpl.second.map { it.digitToInt() }.toIntArray().also { fi -> frets.indices.forEach { if (frets[it] < 0) fi[it] = 0 } }, barre = fret, shape = name)
    }

    /** All voicings for [c], easiest first (open shapes, then the lower barre). */
    fun voicings(c: Chord): List<Fingering> {
        val out = ArrayList<Fingering>(); OPEN[c.name()]?.let { out += it }
        listOfNotNull(barre(c, 0, E_SHAPE[c.quality], "E-shape barre"), barre(c, 1, A_SHAPE[c.quality], "A-shape barre")).sortedBy { it.barre }.forEach { out += it }
        return out
    }
    fun best(c: Chord): Fingering? = voicings(c).firstOrNull()

    data class Capo(val fret: Int, val shapes: List<Chord>)
    /**
     * When any chord's easiest voicing is awkward (barre/stretch), finds the lowest capo fret 1..7 at which every chord
     * can be played with an open shape. Null if none needs it or none works.
     */
    fun capoFor(chords: List<Chord>): Capo? {
        val distinct = chords.distinct(); if (distinct.isEmpty()) return null
        if (distinct.none { best(it)?.awkward != false }) return null
        for (capo in 1..7) { val shapes = distinct.map { it.transpose(-capo) }; if (shapes.all { OPEN.containsKey(it.name()) }) return Capo(capo, shapes) }
        return null
    }
}
