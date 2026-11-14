package com.studiospark.app.core

import org.json.JSONObject
import kotlin.random.Random

/** Genre pattern packs: original written templates in assets/patterns/genres.json, turned into MIDI notes at the project tempo. */
data class DrumPattern(val name: String, val bars: Int, val lanes: Map<String, String>, val fill: Map<String, String>, val swing: Float)
data class LinePattern(val name: String, val bars: Int, val rhythm: String, val prog: List<Int>, val swing: Float)
data class Genre(val name: String, val tempo: Float, val key: Assistant.Key, val tags: List<String>, val drums: List<DrumPattern>, val bass: List<LinePattern>, val guitar: List<LinePattern>)

enum class PatternPart(val label: String) { DRUMS("Drums"), BASS("Bass"), GUITAR("Guitar"), BACKING("Backing set") }

object PatternLib {
    const val ASSET = "patterns/genres.json"
    fun parse(json: String): List<Genre> { val o = JSONObject(json); val a = o.getJSONArray("genres")
        return (0 until a.length()).map { i -> val g = a.getJSONObject(i)
            fun map(x: JSONObject?) = x?.keys()?.asSequence()?.associateWith { x.getString(it) } ?: emptyMap()
            fun lines(k: String) = g.getJSONArray(k).let { la -> (0 until la.length()).map { j -> val p = la.getJSONObject(j)
                LinePattern(p.getString("name"), p.getInt("bars"), p.getString("rhythm"), p.getJSONArray("prog").let { pr -> (0 until pr.length()).map { pr.getInt(it) } }, p.optDouble("swing", 0.0).toFloat()) } }
            Genre(g.getString("name"), g.getDouble("tempo").toFloat(), Assistant.Key(g.getInt("key"), g.getBoolean("minor")), g.optJSONArray("tags")?.let { t -> (0 until t.length()).map { t.getString(it) } } ?: emptyList(),
                g.getJSONArray("drums").let { da -> (0 until da.length()).map { j -> val p = da.getJSONObject(j)
                    DrumPattern(p.getString("name"), p.getInt("bars"), map(p.getJSONObject("lanes")), map(p.optJSONObject("fill")), p.optDouble("swing", 0.0).toFloat()) } },
                lines("bass"), lines("guitar")) } }
}

object PatternGen {
    /** GM-style drum notes used by the stock kits (see DrumCatalog), with fallbacks when a kit lacks a piece. */
    val DRUM_MIDI = linkedMapOf("kick" to listOf(36, 35), "snare" to listOf(38, 40), "rim" to listOf(37, 38), "hat" to listOf(42, 44), "ohat" to listOf(46, 42), "pedal" to listOf(44, 42),
        "crash" to listOf(49, 57, 55), "ride" to listOf(51, 59, 42), "bell" to listOf(53, 51), "china" to listOf(52, 49, 57), "tom1" to listOf(50, 48, 47), "tom2" to listOf(47, 48, 45), "floor" to listOf(43, 41, 45))
    fun drumVel(c: Char) = when (c) { 'X' -> 118; 'x' -> 96; 'o' -> 72; 'g' -> 38; else -> 0 }
    fun midiFor(lane: String, available: Set<Int>?): Int? { val opts = DRUM_MIDI[lane] ?: return null; return if (available == null) opts.first() else opts.firstOrNull { it in available } }
    private fun stepAt(s: String, bar: Int, step: Int): Char = if (s.length == 16) s[step] else s.getOrElse(bar * 16 + step) { '.' }
    private fun swingOffset(step: Int, swing: Float, step16: Long) = if (swing > 0f && step % 4 == 2) (swing * step16 * 2).toLong() else 0L

    /** Drum notes for [p]; [step16] = samples per 16th note. [kit] limits notes to pieces the kit has (null = GM defaults). */
    fun drums(p: DrumPattern, step16: Long, kit: Set<Int>? = null): List<MidiNote> {
        val out = ArrayList<MidiNote>()
        for (bar in 0 until p.bars) { val lanes = if (bar == p.bars - 1 && p.fill.isNotEmpty()) p.lanes + p.fill else p.lanes
            for ((lane, s) in lanes) { val m = midiFor(lane, kit) ?: continue
                for (st in 0 until 16) { val v = drumVel(stepAt(s, bar, st)); if (v == 0) continue
                    out += MidiNote(m, (bar * 16L + st) * step16 + swingOffset(st, p.swing, step16), step16, v) } } }
        return out.sortedBy { it.start }
    }

    private fun degree(key: Assistant.Key, rootPc: Int, steps: Int, fallback: Int): Int { val sc = Assistant.scale(key); val i = sc.indexOf(((rootPc % 12) + 12) % 12)
        return if (i < 0) fallback else ((sc[(i + steps) % 7] - sc[i]) % 12 + 12) % 12 }
    /** Lowest root for the part: bass from E1 (28), guitar from E2 (40). */
    fun baseNote(part: PatternPart, pc: Int) = (if (part == PatternPart.BASS) 28 else 40) + (((pc - 4) % 12) + 12) % 12

    /** Bass or guitar notes for [p] in [key]. Guitar chords are lightly strummed; '-' holds the previous note. */
    fun line(p: LinePattern, key: Assistant.Key, part: PatternPart, step16: Long): List<MidiNote> {
        val out = ArrayList<MidiNote>(); val strum = 384L   // 8 ms between strings at 48 kHz
        for (bar in 0 until p.bars) { val rootPc = key.root + p.prog[bar % p.prog.size]; val root = baseNote(part, rootPc)
            val third = degree(key, rootPc, 2, 3); val seventh = degree(key, rootPc, 6, 10)
            for (st in 0 until 16) { val c = stepAt(p.rhythm, bar, st); if (c == '.' || c == '-') continue
                var hold = 1; while (st + hold < 16 && stepAt(p.rhythm, bar, st + hold) == '-') hold++
                val at = (bar * 16L + st) * step16 + swingOffset(st, p.swing, step16); val len = (hold * step16 * 0.92).toLong()
                val vel = if (c.isUpperCase()) 104 else 76; val single = if (part == PatternPart.GUITAR) 12 else 0
                val pitches: List<Int> = when (c.uppercaseChar()) {
                    'R' -> listOf(root + single); 'F' -> listOf(root + 7 + single); 'O' -> listOf(root + 12 + single); 'T' -> listOf(root + third + single); 'S' -> listOf(root + seventh + single)
                    'C' -> if (part == PatternPart.GUITAR) listOf(root, root + 7, root + 12) else listOf(root)
                    'M' -> if (part == PatternPart.GUITAR) listOf(root, root + 7, root + 12, root + 12 + third) else listOf(root)
                    'X' -> if (part == PatternPart.GUITAR) listOf(root, root + 7) else listOf(root)
                    else -> emptyList() }
                val muted = c == 'x'
                pitches.forEachIndexed { i, pitch -> out += MidiNote(pitch, at + if (c == 'M') i * strum else 0L, if (muted) step16 / 2 else len, if (muted) 52 else vel) } } }
        return out.sortedBy { it.start }
    }
    fun lengthOf(bars: Int, step16: Long) = bars * 16L * step16
    /** Moves any drum note the kit lacks onto its closest available piece (or drops it). */
    fun fitKit(notes: List<MidiNote>, kit: Set<Int>?): List<MidiNote> { if (kit == null) return notes
        return notes.mapNotNull { n -> if (n.pitch in kit) n else DRUM_MIDI.values.firstOrNull { n.pitch in it }?.firstOrNull { it in kit }?.let { n.copy(pitch = it) } } }
    fun bars(g: Genre, part: PatternPart, index: Int) = when (part) { PatternPart.BASS -> g.bass[index].bars; PatternPart.GUITAR -> g.guitar[index].bars; else -> g.drums[index].bars }
}

/** Jam-room backing band: which parts sound, and bar-accurate pattern swaps while the loop is rolling. */
object JamBand {
    enum class Mode(val label: String, val parts: Set<PatternPart>) {
        DRUMS("Drums", setOf(PatternPart.DRUMS)), DRUMS_BASS("Drums+Bass", setOf(PatternPart.DRUMS, PatternPart.BASS)), FULL("Full band", setOf(PatternPart.DRUMS, PatternPart.BASS, PatternPart.GUITAR));
        fun muted(part: PatternPart) = part !in parts }
    val ROLES = listOf(PatternPart.DRUMS, PatternPart.BASS, PatternPart.GUITAR)
    data class Swap(val now: List<MidiNote>, val pendingBar: Int?, val pending: List<MidiNote>)
    /**
     * Swap [old] for [new] starting at the next bar after [pos]: the bar currently playing keeps its old notes, every other bar gets
     * the new pattern at once (it is ahead of the playhead), and the current bar's new notes are [pending] until the playhead leaves it.
     */
    fun swapAtNextBar(old: List<MidiNote>, new: List<MidiNote>, pos: Long, bar: Long, loopLen: Long): Swap {
        if (bar <= 0 || loopLen <= 0) return Swap(new, null, emptyList())
        val b = ((pos % loopLen) / bar).toInt(); val s = b * bar; val e = s + bar; fun inBar(n: MidiNote) = n.start >= s && n.start < e
        return Swap((old.filter(::inBar) + new.filterNot(::inBar)).sortedBy { it.start }, b, new.filter(::inBar))
    }
    /** Applies a pending bar once the playhead is in another bar. */
    fun applyPending(current: List<MidiNote>, pendingBar: Int, pending: List<MidiNote>, bar: Long) =
        (current.filterNot { it.start >= pendingBar * bar && it.start < (pendingBar + 1) * bar } + pending).sortedBy { it.start }
    fun barOf(pos: Long, bar: Long, loopLen: Long) = if (bar <= 0 || loopLen <= 0) 0 else ((pos % loopLen) / bar).toInt()
}

/** Current picker selection, shared by the Studio picker and the Jam room. */
data class PatternPick(val genre: Int = 0, val part: PatternPart = PatternPart.DRUMS, val index: Int = 0, val seed: Int = 1, val vary: Boolean = true) {
    /** Shuffle within the genre: another pattern for the same part, and a fresh variation seed. */
    fun shuffled(r: kotlin.random.Random = kotlin.random.Random): PatternPick { val n = (index + 1 + r.nextInt(4)) % 5; return copy(index = n, seed = r.nextInt(1, Int.MAX_VALUE), vary = true) }
}

/** Offline prompt → genre/part/tempo/key. Keyword rules only: no network, no API keys. */
object PatternPrompt {
    data class Result(val genre: Genre, val part: PatternPart, val tempo: Float?, val key: Assistant.Key?, val index: Int?)
    private val notes = listOf("c", "c#", "d", "d#", "e", "f", "f#", "g", "g#", "a", "a#", "b"); private val flats = mapOf("db" to 1, "eb" to 3, "gb" to 6, "ab" to 8, "bb" to 10)
    fun parse(text: String, genres: List<Genre>): Result? {
        if (genres.isEmpty()) return null
        val t = text.lowercase(); val words = t.split(Regex("[^a-z0-9#]+")).filter { it.isNotBlank() }
        val genre = genres.firstOrNull { g -> g.name.lowercase() in words || g.tags.any { it in words } } ?: genres.first()
        val tempo = Regex("(\\d{2,3})\\s*(bpm)?").findAll(t).map { it.groupValues[1].toFloat() }.firstOrNull { it in 30f..300f }
        val part = when { words.any { it in setOf("band", "backing", "full", "jam", "everything", "all") } -> PatternPart.BACKING
            words.any { it.startsWith("bass") } -> PatternPart.BASS; words.any { it in setOf("guitar", "riff", "strum", "chords", "chord") } -> PatternPart.GUITAR; else -> PatternPart.DRUMS }
        val key = Regex("(?:in |key of )([a-g](?:#|b)?)\\s*(minor|min|m|major|maj)?\\b").find(t)?.let { m -> val n = m.groupValues[1]; val pc = flats[n] ?: notes.indexOf(n); if (pc < 0) null else Assistant.Key(pc, m.groupValues[2].startsWith("m") && !m.groupValues[2].startsWith("maj")) }
        val list = when (part) { PatternPart.BASS -> genre.bass.map { it.name }; PatternPart.GUITAR -> genre.guitar.map { it.name }; else -> genre.drums.map { it.name } }
        val index = list.indexOfFirst { n -> n.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 3 }.any { it in words } }.takeIf { it >= 0 }
        return Result(genre, part, tempo, key, index)
    }
}
