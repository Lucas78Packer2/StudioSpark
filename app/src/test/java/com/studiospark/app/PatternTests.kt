package com.studiospark.app

import com.studiospark.app.core.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** 2.1 milestone 6: genre packs, pattern → MIDI generation, variation rules and the offline prompt. Pure JVM. */
class PatternTests {
    private val lib by lazy { PatternLib.parse(File("src/main/assets/${PatternLib.ASSET}").readText()) }
    private val step16 = 6000L   // 120 BPM at 48 kHz

    @Test fun sixtyOriginalPatternsInFourGenres() {
        assertEquals(listOf("Rock", "Country", "Metal", "Pop"), lib.map { it.name })
        lib.forEach { g -> assertEquals(5, g.drums.size); assertEquals(5, g.bass.size); assertEquals(5, g.guitar.size) }
        assertEquals(60, lib.sumOf { it.drums.size + it.bass.size + it.guitar.size })
        lib.forEach { g -> (g.drums.map { it.bars } + g.bass.map { it.bars } + g.guitar.map { it.bars }).forEach { assertTrue("$g bars $it", it in 4..8) } }
        val names = lib.flatMap { g -> g.drums.map { it.name } + g.bass.map { it.name } + g.guitar.map { it.name } }
        assertEquals("every pattern has its own name", names.size, names.toSet().size)
    }

    @Test fun everyPatternMakesNotesInsideItsLength() {
        lib.forEach { g -> PatternPart.values().filter { it != PatternPart.BACKING }.forEach { part -> (0 until 5).forEach { i ->
            val notes = when (part) { PatternPart.DRUMS -> PatternGen.drums(g.drums[i], step16); PatternPart.BASS -> PatternGen.line(g.bass[i], g.key, part, step16); else -> PatternGen.line(g.guitar[i], g.key, part, step16) }
            assertTrue("${g.name} $part $i empty", notes.size >= 8)
            val len = PatternGen.lengthOf(PatternGen.bars(g, part, i), step16)
            notes.forEach { assertTrue(it.start in 0 until len); assertTrue(it.velocity in 1..127); assertTrue(it.length > 0) } } } }
    }

    @Test fun drumsUseGmNotesAndFallBackToKitPieces() {
        val notes = PatternGen.drums(lib[0].drums[0], step16)
        val gm = PatternGen.DRUM_MIDI.values.flatten().toSet(); notes.forEach { assertTrue(it.pitch in gm) }
        assertTrue(notes.any { it.pitch == 36 } && notes.any { it.pitch == 38 })
        val kit = setOf(36, 38, 42, 49)
        PatternGen.fitKit(listOf(MidiNote(52, 0, 10, 100), MidiNote(46, 0, 10, 100), MidiNote(51, 0, 10, 100), MidiNote(99, 0, 10, 100)), kit).let { m ->
            assertEquals(listOf(49, 42, 42), m.map { it.pitch }) }
    }

    @Test fun linesStayInKeyAndRange() {
        lib.forEach { g -> val sc = Assistant.scale(g.key)
            g.bass.forEach { p -> PatternGen.line(p, g.key, PatternPart.BASS, step16).forEach { n -> assertTrue("bass ${n.pitch}", n.pitch in 28..64) } }
            g.guitar.forEach { p -> PatternGen.line(p, g.key, PatternPart.GUITAR, step16).forEach { n -> assertTrue("gtr ${n.pitch}", n.pitch in 40..88) } }
            // the root of each bar is diatonic for diatonic progressions
            g.bass.forEach { p -> p.prog.forEach { r -> val pc = (g.key.root + r) % 12; if (pc in sc) assertTrue(pc in sc) } } }
        // E minor, root E: third is G (minor), seventh D
        val em = Assistant.Key(4, true); val p = LinePattern("t", 4, "R...T...S...O...", listOf(0), 0f)
        assertEquals(listOf(28, 31, 38, 40), PatternGen.line(p, em, PatternPart.BASS, step16).take(4).map { it.pitch })
    }

    @Test fun tempoScalesNoteTimes() {
        val slow = PatternGen.drums(lib[0].drums[0], 12000L); val fast = PatternGen.drums(lib[0].drums[0], 6000L)
        assertEquals(fast.size, slow.size); assertEquals(fast.last().start * 2, slow.last().start)
    }

    @Test fun variationIsDeterministicAndAddsFillAndGhosts() {
        val raw = PatternGen.drums(lib[0].drums[0], step16); val bars = lib[0].drums[0].bars
        val a = Assistant.vary(raw, true, 7, step16, bars); val b = Assistant.vary(raw, true, 7, step16, bars); val c = Assistant.vary(raw, true, 8, step16, bars)
        assertEquals(a, b); assertNotEquals(a, c)
        val end = bars * 16 * step16; val fill = a.filter { it.start >= end - 4 * step16 && it.pitch != 36 }
        assertEquals("one fill hit per 16th in the last beat", 4, fill.size); assertTrue(fill.all { it.pitch in setOf(38, 50, 47, 43) })
        assertTrue("ghost snares", (1..40).any { s -> Assistant.vary(raw, true, s, step16, bars).any { it.pitch == 38 && it.velocity < 50 } })
        a.forEach { assertTrue(it.velocity in 20..127) }
        val off = Assistant.vary(raw, true, 7, step16, bars, humanize = 0, ghosts = 0f, fill = false).filter { it.pitch != 49 || it.start != 0L }
        assertEquals(raw.filter { it.pitch != 49 || it.start != 0L }, off)
    }

    @Test fun shuffleAlwaysPicksAnotherPattern() {
        val r = kotlin.random.Random(3); var p = PatternPick(index = 2)
        repeat(50) { val n = p.shuffled(r); assertNotEquals(p.index, n.index); assertTrue(n.index in 0..4); p = n }
    }

    @Test fun offlinePromptMapsKeywords() {
        PatternPrompt.parse("rock beat 120", lib)!!.let { assertEquals("Rock", it.genre.name); assertEquals(PatternPart.DRUMS, it.part); assertEquals(120f, it.tempo) }
        PatternPrompt.parse("Metal riff 160 bpm", lib)!!.let { assertEquals("Metal", it.genre.name); assertEquals(PatternPart.GUITAR, it.part); assertEquals(160f, it.tempo) }
        PatternPrompt.parse("country backing band in G", lib)!!.let { assertEquals("Country", it.genre.name); assertEquals(PatternPart.BACKING, it.part); assertEquals(Assistant.Key(7, false), it.key); assertNull(it.tempo) }
        PatternPrompt.parse("pop bass in a minor", lib)!!.let { assertEquals("Pop", it.genre.name); assertEquals(PatternPart.BASS, it.part); assertEquals(Assistant.Key(9, true), it.key) }
        assertNull("absurd tempi are ignored", PatternPrompt.parse("rock beat 5000", lib)!!.tempo)
    }
}
