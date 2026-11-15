package com.studiospark.app.songwriter

import com.studiospark.app.core.MidiNote
import kotlin.math.*

/** Mono pitch detection (YIN-style difference function) for the hum/play-a-melody path. Pure, unit-tested. */
object Pitch {
    /** Returns the fundamental in Hz of [x] at [sr], or null if unvoiced. Searches 70–1000 Hz. */
    fun detect(x: FloatArray, sr: Int, threshold: Double = 0.15): Double? {
        val minLag = sr / 1000; val maxLag = min(sr / 70, x.size / 2); if (maxLag <= minLag + 2) return null
        var rms = 0.0; for (v in x) rms += v * v; if (sqrt(rms / x.size) < 0.01) return null
        val w = x.size - maxLag; val d = DoubleArray(maxLag + 1)
        for (tau in 1..maxLag) { var s = 0.0; for (i in 0 until w) { val df = x[i] - x[i + tau]; s += df * df }; d[tau] = s }
        var run = 0.0; val cmnd = DoubleArray(maxLag + 1) { 1.0 }
        for (tau in 1..maxLag) { run += d[tau]; cmnd[tau] = if (run == 0.0) 1.0 else d[tau] * tau / run }
        var tau = minLag
        while (tau < maxLag) { if (cmnd[tau] < threshold) { while (tau + 1 < maxLag && cmnd[tau + 1] < cmnd[tau]) tau++; break }; tau++ }
        if (tau >= maxLag) return null
        val a = cmnd[tau - 1]; val b = cmnd[tau]; val c = cmnd[tau + 1]; val den = a - 2 * b + c
        val t = if (den != 0.0) tau + 0.5 * (a - c) / den else tau.toDouble()
        return sr / t
    }
    fun midi(hz: Double) = (69 + 12 * log2(hz / 440.0)).roundToInt()

    /** Frames [x] into ~46 ms windows and returns note events (MIDI pitch, start/len in frames), merging repeats. */
    fun track(x: FloatArray, sr: Int): List<MidiNote> {
        val win = 2048; val hop = 1024; val out = ArrayList<MidiNote>(); var cur = -1; var start = 0
        var i = 0
        fun close(end: Int) { if (cur >= 0 && end - start >= sr / 10) out += MidiNote(cur, start.toLong(), (end - start).toLong(), 100) }
        while (i + win <= x.size) {
            val p = detect(x.copyOfRange(i, i + win), sr)?.let { midi(it) } ?: -1
            if (p != cur) { close(i); cur = p; start = i }
            i += hop
        }
        close(i); return out
    }
}

/** Melody → chords: per bar, scores every diatonic chord by how much of the bar's melody (duration-weighted) it contains. */
object Harmonizer {
    fun chordsFor(key: Key, melody: List<MidiNote>, barFrames: Long, bars: Int = maxOf(1, (((melody.maxOfOrNull { it.start + it.length } ?: 0L) + barFrames - 1) / barFrames).toInt())): List<Chord> =
        (0 until bars).map { b ->
            val s = b * barFrames; val e = s + barFrames
            val weight = DoubleArray(12)
            for (n in melody) { val ov = min(e, n.start + n.length) - max(s, n.start); if (ov > 0) weight[((n.pitch % 12) + 12) % 12] += ov.toDouble() + if (n.start in s until s + barFrames / 4) barFrames * 0.1 else 0.0 }
            if (weight.sum() == 0.0) key.triad(0)
            else key.diatonic.withIndex().maxBy { (d, c) -> c.pcs.sumOf { weight[it] } - (key.scale - c.pcs).sumOf { 0.0 } + (if (d == 0) 0.05 else if (d == 4 || d == 3) 0.02 else 0.0) * weight.sum() - (if (c.quality == Quality.DIM) 0.1 * weight.sum() else 0.0) }.value
        }
}

/**
 * "AI" accompaniment = rule/pattern-based generation (no neural model): drums from a genre groove, bass on chord roots with
 * approach notes, guitar strums the chord voicing. Deterministic for a given progression and style. Output is MIDI notes.
 */
object Accompaniment {
    enum class Style(val label: String) { POP("Pop"), ROCK("Rock"), BALLAD("Ballad"), FUNK("Funk") }
    data class Parts(val drums: List<MidiNote>, val bass: List<MidiNote>, val guitar: List<MidiNote>)
    const val KICK = 36; const val SNARE = 38; const val HAT = 42

    fun generate(chords: List<Chord>, beatFrames: Long, style: Style = Style.POP, beatsPerBar: Int = 4): Parts {
        val drums = ArrayList<MidiNote>(); val bass = ArrayList<MidiNote>(); val gtr = ArrayList<MidiNote>()
        val e8 = beatFrames / 2; val bar = beatFrames * beatsPerBar
        chords.forEachIndexed { i, c ->
            val t0 = i * bar
            for (k in 0 until beatsPerBar * 2) { val t = t0 + k * e8
                if (style != Style.BALLAD || k % 2 == 0) drums += MidiNote(HAT, t, e8 / 2, if (k % 2 == 0) 90 else 60)
                val kick = when (style) { Style.ROCK -> k == 0 || k == 4 || k == 5; Style.FUNK -> k == 0 || k == 3 || k == 6; Style.BALLAD -> k == 0; else -> k == 0 || k == 5 }
                if (kick) drums += MidiNote(KICK, t, e8, 110)
                if (k == 2 || k == 6) drums += MidiNote(SNARE, t, e8, if (style == Style.BALLAD) 80 else 105) }
            val root = 36 + ((c.root - 0 + 12) % 12).let { if (it > 7) it - 12 else it } + 12
            val next = chords.getOrNull(i + 1)
            for (b in 0 until beatsPerBar) { val t = t0 + b * beatFrames
                val p = when { b == beatsPerBar - 1 && next != null && style != Style.BALLAD -> approach(root, next); b == 2 && style == Style.FUNK -> root + 12; b == 2 -> root + c.quality.intervals[2]; else -> root }
                if (style == Style.BALLAD && b % 2 == 1) continue
                bass += MidiNote(p, t, if (style == Style.BALLAD) beatFrames * 2 else beatFrames * 9 / 10, 100) }
            val voice = ChordDictionary.best(c)?.notes() ?: c.quality.intervals.map { 52 + c.root + it }
            val hits = when (style) { Style.BALLAD -> listOf(0); Style.ROCK -> (0 until beatsPerBar * 2).toList(); Style.FUNK -> listOf(1, 3, 4, 6); else -> listOf(0, 3, 4, 6) }
            for (h in hits) voice.forEachIndexed { j, n -> gtr += MidiNote(n, t0 + h * e8 + j * 240, if (style == Style.BALLAD) bar else e8, 85) }
        }
        return Parts(drums, bass, gtr)
    }
    private fun approach(root: Int, next: Chord): Int { var n = 36 + next.root; if (n > 43) n -= 12; n += 12; return if (n > root) n - 1 else n + 1 }

    /** Tiny preview synth for the Songwriter tab (sine bass, plucked guitar, noise drums), mono PCM16 at [sr]. */
    fun render(parts: Parts, sr: Int, lengthFrames: Long): ShortArray {
        val out = FloatArray(lengthFrames.toInt().coerceAtLeast(1)); val rnd = java.util.Random(7)
        fun add(n: MidiNote, gain: Float, kind: Int) { val f = 440.0 * 2.0.pow((n.pitch - 69) / 12.0); val len = min(n.length, (sr * 1.5).toLong()).toInt()
            for (i in 0 until len) { val idx = n.start.toInt() + i; if (idx >= out.size) break; val tt = i.toDouble() / sr
                val v = when (kind) { 0 -> sin(2 * PI * f * tt) * exp(-tt * 3); 1 -> (2 * ((f * tt) % 1.0) - 1) * exp(-tt * 6); else -> when (n.pitch) { KICK -> sin(2 * PI * (60 + 90 * exp(-tt * 30)) * tt) * exp(-tt * 12); SNARE -> (rnd.nextGaussian() * 0.5) * exp(-tt * 20); else -> rnd.nextGaussian() * 0.25 * exp(-tt * 60) } }
                out[idx] += (v * gain * n.velocity / 127.0).toFloat() } }
        parts.bass.forEach { add(it, 0.35f, 0) }; parts.guitar.forEach { add(it, 0.08f, 1) }; parts.drums.forEach { add(it, 0.4f, 2) }
        return ShortArray(out.size) { (out[it].coerceIn(-1f, 1f) * 32767).toInt().toShort() }
    }
}
