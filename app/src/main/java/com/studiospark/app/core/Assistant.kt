package com.studiospark.app.core

import kotlin.math.abs
import kotlin.random.Random

/** On-device music assistant: deterministic theory-based suggestions, no network or paid APIs. */
object Assistant {
    private val noteNames = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    private val major = listOf(0, 2, 4, 5, 7, 9, 11); private val minor = listOf(0, 2, 3, 5, 7, 8, 10)
    data class Key(val root: Int, val minorKey: Boolean) { override fun toString() = noteNames[root] + if (minorKey) " minor" else " major" }

    fun detectKey(pitches: List<Int>): Key {
        if (pitches.isEmpty()) return Key(0, false)
        val hist = IntArray(12); pitches.forEach { hist[((it % 12) + 12) % 12]++ }
        var best = Key(0, false); var score = -1
        for (r in 0 until 12) for (m in listOf(false, true)) { val sc = (if (m) minor else major).sumOf { hist[(r + it) % 12] } + hist[r]; if (sc > score) { score = sc; best = Key(r, m) } }
        return best
    }
    fun scale(k: Key) = (if (k.minorKey) minor else major).map { (k.root + it) % 12 }

    /** Next-note suggestions ranked by voice-leading distance within the detected key. */
    fun nextNotes(recent: List<Int>, count: Int = 4): List<Int> {
        val k = detectKey(recent); val last = recent.lastOrNull() ?: (60 + k.root); val sc = scale(k)
        return (last - 7..last + 7).filter { ((it % 12) + 12) % 12 in sc && it != last }.sortedBy { abs(it - last) + if (((it - k.root) % 12 + 12) % 12 == 0) -1 else 0 }.take(count)
    }
    fun noteName(m: Int) = noteNames[((m % 12) + 12) % 12] + (m / 12 - 1)

    fun chordProgression(k: Key, mood: String): List<String> {
        val degreesMaj = listOf("", "m", "m", "", "", "m", "dim"); val degreesMin = listOf("m", "dim", "", "m", "m", "", "")
        val pats = if (k.minorKey) mapOf("dark" to listOf(0, 5, 6, 4), "epic" to listOf(0, 5, 2, 6), "default" to listOf(0, 3, 5, 4)) else mapOf("happy" to listOf(0, 4, 5, 3), "ballad" to listOf(0, 5, 3, 4), "default" to listOf(0, 3, 4, 0))
        val pat = pats.entries.firstOrNull { mood.contains(it.key, true) }?.value ?: pats.getValue("default"); val sc = scale(k); val q = if (k.minorKey) degreesMin else degreesMaj
        return pat.map { noteNames[sc[it]] + q[it] }
    }

    /** Melody: notes in key over [bars], snapped to eighth notes. */
    fun melody(k: Key, bars: Int, samplesPerBeat: Long, seed: Int = 7): List<MidiNote> {
        val r = Random(seed); val sc = scale(k); var pitch = 60 + k.root; val out = mutableListOf<MidiNote>(); var t = 0L; val end = samplesPerBeat * 4 * bars; val eighth = samplesPerBeat / 2
        while (t < end) { val len = eighth * listOf(1, 1, 2, 2, 3, 4)[r.nextInt(6)]
            val step = listOf(-2, -1, -1, 1, 1, 2, 0, 3)[r.nextInt(8)]; var idx = sc.indexOf(((pitch % 12) + 12) % 12).coerceAtLeast(0) + step; var oct = pitch / 12
            while (idx < 0) { idx += 7; oct-- }; while (idx >= 7) { idx -= 7; oct++ }; pitch = (oct * 12 + sc[idx]).coerceIn(55, 79)
            out.add(MidiNote(pitch, t, (len * 0.9).toLong(), 70 + r.nextInt(40))); t += len }
        return out
    }

    private val images = mapOf(
        "love" to listOf("your voice in the static", "a porch light left on", "two hearts out of time", "the space where you were"),
        "night" to listOf("neon on the wet street", "the city holds its breath", "headlights paint the ceiling", "a radio half asleep"),
        "road" to listOf("white lines through the dark", "the engine hums a promise", "every mile a story", "dust on the dashboard"),
        "fire" to listOf("sparks against the sky", "we burn but never break", "embers in our hands", "smoke becomes the chorus"),
        "default" to listOf("we were made of thunder", "echoes in the hallway", "the room begins to shake", "nothing left to fake"))

    /** Songwriting draft: verse/chorus lyric sheet, one editable line per entry. Template-based on-device writing, not a cloud model. */
    fun lyrics(prompt: String, genre: String, seed: Int = prompt.hashCode()): List<String> {
        val r = Random(seed); val theme = images.keys.firstOrNull { prompt.contains(it, true) } ?: "default"; val pool = images.getValue(theme) + images.getValue("default")
        val hook = prompt.trim().ifBlank { "Spark in the dark" }.replaceFirstChar { it.uppercase() }
        fun line() = pool[r.nextInt(pool.size)].replaceFirstChar { it.uppercase() }
        val tag = if (genre.contains("metal", true) || genre.contains("rock", true)) "!" else ""
        return listOf("[Verse 1]", line(), line() + ",", line(), "and I can't let go", "[Chorus]", "$hook$tag", line(), "$hook$tag", "we're never coming down", "[Verse 2]", line(), line() + ",", line(), "and the night says so", "[Chorus]", "$hook$tag", line(), "$hook$tag", "we're never coming down")
    }

    fun syllables(line: String): Int = line.lowercase().split(Regex("[^a-z']+")).filter { it.isNotBlank() }.sumOf { w -> Regex("[aeiouy]+").findAll(w).count().coerceAtLeast(1) - (if (w.endsWith("e") && w.length > 2 && Regex("[aeiouy]+").findAll(w).count() > 1) 1 else 0) }

    // ------------------------------------------------------------ pattern variation rules (genre packs)
    /**
     * Variation of a written pattern so repeats and Shuffle never sound copy-pasted: velocity humanising (±[humanize]),
     * snare ghost notes on empty 16ths (drums), a tom/snare fill in the last beat with a crash on the downbeat (drums),
     * and occasional octave pops on bass. Deterministic per [seed].
     */
    fun vary(notes: List<MidiNote>, drums: Boolean, seed: Int, step16: Long, bars: Int, humanize: Int = 8, ghosts: Float = 0.12f, fill: Boolean = true): List<MidiNote> {
        val r = Random(seed); val out = notes.map { it.copy(velocity = (it.velocity + r.nextInt(-humanize, humanize + 1)).coerceIn(20, 127)) }.toMutableList()
        val barLen = 16 * step16; val end = bars * barLen
        if (drums) {
            val snareAt = out.filter { it.pitch == 38 }.map { it.start / step16 }.toSet(); val kickAt = out.filter { it.pitch == 36 }.map { it.start / step16 }.toSet()
            for (s in 0L until bars * 16L) if (s % 2 == 1L && s !in snareAt && s !in kickAt && r.nextFloat() < ghosts) out += MidiNote(38, s * step16, step16, 30 + r.nextInt(14))
            if (fill && bars >= 2) { val f0 = end - 4 * step16; out.removeAll { it.start >= f0 && it.pitch != 36 }
                val run = listOf(38, 38, 50, 43).let { if (r.nextBoolean()) it else listOf(50, 47, 43, 43) }
                run.forEachIndexed { i, m -> out += MidiNote(m, f0 + i * step16, step16, 90 + i * 8) } }
            if (out.none { it.pitch == 49 && it.start == 0L } && r.nextFloat() < 0.7f) out += MidiNote(49, 0, step16, 110)
        } else for (i in out.indices) { val n = out[i]; if (n.velocity > 90 && n.pitch < 52 && r.nextFloat() < 0.08f) out[i] = n.copy(pitch = n.pitch + 12) }
        return out.sortedBy { it.start }
    }
}
