package com.studiospark.app.eartraining

import kotlin.math.*

/** Output rate of every rendered clip. Matches com.studiospark.app.core.ENGINE_SR (48 kHz) so clips go straight to AudioEngine.preview. */
const val EAR_SR = 48000

/** Renders question audio. Implementations must be pure (no Android) so they are unit-testable. */
interface EarClipSource {
    /** Short UI label, e.g. "Stem library (bass, 3 notes)" or "Synth tones (stem library is empty)". */
    val label: String
    val isFallback: Boolean
    /** Mono PCM16 at [EAR_SR]. */
    fun render(notes: List<Int>, layout: Layout): ShortArray
}

/** Shared timing so both sources sound the same shape. */
object EarTiming {
    const val NOTE_SEC = 0.9
    const val GAP_SEC = 0.12
    const val TOGETHER_SEC = 1.7
    fun noteFrames(layout: Layout) = ((if (layout == Layout.TOGETHER) TOGETHER_SEC else NOTE_SEC) * EAR_SR).toInt()
    fun totalFrames(count: Int, layout: Layout): Int = if (layout == Layout.TOGETHER || count <= 1) noteFrames(layout)
        else count * noteFrames(layout) + (count - 1) * (GAP_SEC * EAR_SR).toInt()
    fun offset(index: Int, layout: Layout): Int = if (layout == Layout.TOGETHER) 0 else index * (noteFrames(layout) + (GAP_SEC * EAR_SR).toInt())
}

internal fun mixToPcm(total: Int, notes: List<Int>, layout: Layout, voice: (midi: Int, frames: Int) -> FloatArray): ShortArray {
    val out = FloatArray(total)
    val n = EarTiming.noteFrames(layout)
    notes.forEachIndexed { i, m -> val v = voice(m, n); val o = EarTiming.offset(i, layout); for (k in v.indices) if (o + k < total) out[o + k] += v[k] }
    val peak = out.maxOfOrNull { abs(it) } ?: 0f
    val g = if (peak > 1e-6f) 0.8f / peak else 0f
    return ShortArray(total) { (out[it] * g * 32767f).roundToInt().coerceIn(-32768, 32767).toShort() }
}

/** Simple attack/decay/sustain/release envelope value at frame [i] of [n]. */
internal fun envelope(i: Int, n: Int, attack: Int = (0.01 * EAR_SR).toInt(), release: Int = (0.12 * EAR_SR).toInt()): Float {
    val a = if (i < attack) i / attack.toFloat() else 1f
    val decay = 0.65f + 0.35f * exp(-3f * i / n)
    val r = if (i > n - release) ((n - i) / release.toFloat()).coerceAtLeast(0f) else 1f
    return a * decay * r
}

/** Fallback: additive tone (a few decaying harmonics) with an envelope. Always available. */
class SynthToneSource(override val label: String = SYNTH_EMPTY_LABEL) : EarClipSource {
    override val isFallback = true
    override fun render(notes: List<Int>, layout: Layout): ShortArray = mixToPcm(EarTiming.totalFrames(notes.size, layout), notes, layout) { m, n -> tone(m, n) }
    companion object {
        const val SYNTH_EMPTY_LABEL = "Synth tones (stem library is empty)"
        const val SYNTH_UNUSABLE_LABEL = "Synth tones (no clean single notes found in the stem library)"
        private val HARMONICS = floatArrayOf(1f, 0.5f, 0.28f, 0.14f, 0.07f)
        fun tone(midi: Int, n: Int): FloatArray { val f = Pitch.midiToHz(midi.toDouble())
            return FloatArray(n) { i -> var s = 0.0; for (h in HARMONICS.indices) { val fh = f * (h + 1); if (fh < EAR_SR / 2.2) s += HARMONICS[h] * sin(2 * PI * fh * i / EAR_SR) * exp(-h * 1.5 * i / n) }
                (s * envelope(i, n)).toFloat() } }
    }
}

/** A short single-note excerpt cut from a stem, with its detected pitch. PCM is mono float at [EAR_SR]. */
class NoteGrain(val stem: String, val pcm: FloatArray, val midi: Double)

/** Plays questions by varispeed-shifting the nearest-pitched stem grain to every target note. */
class StemGrainSource(val grains: List<NoteGrain>) : EarClipSource {
    init { require(grains.isNotEmpty()) }
    override val isFallback = false
    override val label: String = "Stem library (${grains.map { it.stem }.distinct().joinToString()} · ${grains.size} note${if (grains.size == 1) "" else "s"})"
    fun nearest(midi: Int): NoteGrain = grains.minBy { abs(it.midi - midi) }
    override fun render(notes: List<Int>, layout: Layout): ShortArray = mixToPcm(EarTiming.totalFrames(notes.size, layout), notes, layout) { m, n -> shifted(nearest(m), m, n) }
    companion object {
        /** Linear-interpolated varispeed: ratio = target/grain frequency. Output is [n] frames, padded with silence if the grain runs out. */
        fun shifted(g: NoteGrain, midi: Int, n: Int): FloatArray {
            val ratio = 2.0.pow((midi - g.midi) / 12.0)
            val len = min(n, ((g.pcm.size - 1) / ratio).toInt())
            return FloatArray(n) { i -> if (i >= len) 0f else { val p = i * ratio; val k = p.toInt(); val fr = (p - k).toFloat()
                (g.pcm[k] * (1 - fr) + g.pcm[min(k + 1, g.pcm.size - 1)] * fr) * envelope(i, len) } }
        }
    }
}

/** Raw stem audio handed to the ear trainer. Mono PCM16 at [sampleRate] (the stem library stores 44.1 kHz). */
class StemClip(val stem: String, val pcm: ShortArray, val sampleRate: Int)

/** Narrow seam over the app's stem library so the ear trainer never touches import internals directly. */
fun interface StemClipProvider {
    /** Called once, off the main thread, the first time the tab opens. Return empty if the library is empty. */
    fun clips(): List<StemClip>
}

/** Finds clean, stable single notes in stems with normalized autocorrelation. */
object GrainFinder {
    const val WIN = 2048
    const val GRAIN_SEC = 0.75
    const val MIN_HZ = 70.0
    const val MAX_HZ = 900.0
    const val CLARITY = 0.88
    /** Melodic stems first; drums are never used for pitched material. */
    val PREFERRED = listOf("bass", "guitar", "piano", "other", "vocals")

    data class PitchEstimate(val hz: Double, val clarity: Double)

    fun detect(x: FloatArray, start: Int, sr: Int, win: Int = WIN): PitchEstimate? {
        if (start < 0 || start + win * 2 > x.size) return null
        val minLag = (sr / MAX_HZ).toInt(); val maxLag = min(win - 1, (sr / MIN_HZ).toInt())
        var e0 = 0.0; for (i in 0 until win) e0 += x[start + i] * x[start + i]
        if (e0 < 1e-6) return null
        val nac = DoubleArray(maxLag + 2)
        for (lag in minLag..maxLag + 1) { var c = 0.0; var e1 = 0.0
            for (i in 0 until win) { val b = x[start + i + lag]; c += x[start + i] * b; e1 += b * b }
            nac[lag] = if (e1 > 0) c / sqrt(e0 * e1) else 0.0 }
        // first peak above 90% of the global max, to avoid octave errors
        var gmax = 0.0; for (l in minLag..maxLag) gmax = max(gmax, nac[l])
        if (gmax < CLARITY) return null
        var best = -1
        for (l in minLag + 1..maxLag) if (nac[l] >= 0.9 * gmax && nac[l] >= nac[l - 1] && nac[l] >= nac[l + 1]) { best = l; break }
        if (best < 0) return null
        val a = nac[best - 1]; val b = nac[best]; val c = nac[best + 1]; val d = a - 2 * b + c
        val shift = if (abs(d) > 1e-12) 0.5 * (a - c) / d else 0.0
        return PitchEstimate(sr / (best + shift), b)
    }

    fun resample(x: FloatArray, from: Int, to: Int): FloatArray { if (from == to) return x
        val n = (x.size.toLong() * to / from).toInt(); val r = from.toDouble() / to
        return FloatArray(n) { i -> val p = i * r; val k = p.toInt(); val f = (p - k).toFloat(); val a = x[min(k, x.size - 1)]; val b = x[min(k + 1, x.size - 1)]; a + (b - a) * f } }

    /**
     * Scans the loudest windows of each melodic stem and keeps excerpts whose pitch is stable (within 35 cents) across the grain.
     * Bounded work: at most [maxWindowsPerStem] windows per stem and [maxGrains] grains total.
     */
    fun find(clips: List<StemClip>, maxGrains: Int = 12, maxWindowsPerStem: Int = 160): List<NoteGrain> {
        val out = ArrayList<NoteGrain>()
        val ordered = clips.filter { it.stem in PREFERRED && it.pcm.isNotEmpty() }.sortedBy { PREFERRED.indexOf(it.stem) }
        for (clip in ordered) {
            if (out.size >= maxGrains) break
            val sr = clip.sampleRate; val x = FloatArray(clip.pcm.size) { clip.pcm[it] / 32768f }
            val grainLen = (GRAIN_SEC * sr).toInt(); if (x.size < grainLen + WIN * 2) continue
            val hop = sr / 10
            val starts = (0 until x.size - grainLen - WIN * 2 step hop).map { s -> var e = 0.0; for (i in s until s + WIN) e += x[i] * x[i]; s to e }
                .filter { it.second / WIN > 1e-4 }.sortedByDescending { it.second }.take(maxWindowsPerStem).map { it.first }.sorted()
            var lastUsedEnd = -1
            for (s in starts) {
                if (out.size >= maxGrains) break
                if (s < lastUsedEnd) continue
                val p0 = detect(x, s, sr) ?: continue
                val p1 = detect(x, s + grainLen / 2 - WIN, sr) ?: continue
                val p2 = detect(x, s + grainLen - WIN * 2, sr) ?: continue
                val m0 = Pitch.hzToMidi(p0.hz)
                if (abs(Pitch.hzToMidi(p1.hz) - m0) > 0.35 || abs(Pitch.hzToMidi(p2.hz) - m0) > 0.35) continue
                if (out.any { it.stem == clip.stem && abs(it.midi - m0) < 0.5 }) continue // keep pitch variety
                val grain = resample(x.copyOfRange(s, s + grainLen), sr, EAR_SR)
                out += NoteGrain(clip.stem, grain, (m0 + Pitch.hzToMidi(p1.hz) + Pitch.hzToMidi(p2.hz)) / 3); lastUsedEnd = s + grainLen
            }
        }
        return out
    }
}

/** Why the trainer is using the source it is using. */
data class SourceChoice(val source: EarClipSource, val stemCount: Int, val error: String? = null)

/** Picks stem material when possible, otherwise the labelled synth fallback. Never throws. */
object EarSourceSelector {
    fun select(provider: StemClipProvider?): SourceChoice {
        val clips = try { provider?.clips().orEmpty() } catch (t: Throwable) {
            return SourceChoice(SynthToneSource("Synth tones (stem library could not be read)"), 0, t.message ?: t.javaClass.simpleName) }
        if (clips.isEmpty() || clips.all { it.pcm.isEmpty() }) return SourceChoice(SynthToneSource(SynthToneSource.SYNTH_EMPTY_LABEL), 0)
        val grains = try { GrainFinder.find(clips) } catch (t: Throwable) { emptyList() }
        return if (grains.isEmpty()) SourceChoice(SynthToneSource(SynthToneSource.SYNTH_UNUSABLE_LABEL), clips.size)
        else SourceChoice(StemGrainSource(grains), clips.size)
    }
}
