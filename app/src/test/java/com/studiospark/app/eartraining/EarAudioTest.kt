package com.studiospark.app.eartraining

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class EarAudioTest {
    private fun sine(hz: Double, sec: Double, sr: Int = 44100, amp: Double = 0.5) = ShortArray((sec * sr).toInt()) { (amp * 32767 * (sin(2 * PI * hz * it / sr) + 0.3 * sin(4 * PI * hz * it / sr)) / 1.3).roundToInt().toShort() }
    private fun noise(sec: Double, sr: Int = 44100) = java.util.Random(1).let { r -> ShortArray((sec * sr).toInt()) { (r.nextGaussian() * 6000).toInt().coerceIn(-32768, 32767).toShort() } }
    private fun f(a: ShortArray) = FloatArray(a.size) { a[it] / 32768f }

    @Test fun detectsPitchOfHarmonicTone() {
        for (hz in listOf(98.0, 220.0, 440.0, 659.25)) {
            val p = GrainFinder.detect(f(sine(hz, 0.3)), 1000, 44100)!!
            assertEquals(hz, p.hz, hz * 0.006) // within ~10 cents
        }
        assertNull(GrainFinder.detect(f(noise(0.3)), 1000, 44100))
    }
    @Test fun synthRenderLengthsAndLevels() {
        val s = SynthToneSource()
        val seq = s.render(listOf(60, 67), Layout.SEQUENTIAL); val tog = s.render(listOf(60, 64, 67), Layout.TOGETHER)
        assertEquals(EarTiming.totalFrames(2, Layout.SEQUENTIAL), seq.size); assertEquals(EarTiming.noteFrames(Layout.TOGETHER), tog.size)
        val peak = tog.maxOf { abs(it.toInt()) }; assertTrue(peak in 20000..27000)
        assertEquals(0, seq.first().toInt()) // envelope starts silent: no click
    }
    @Test fun synthToneHasTheRequestedPitch() {
        val pcm = SynthToneSource().render(listOf(57), Layout.SEQUENTIAL)
        val p = GrainFinder.detect(f(pcm), 4000, EAR_SR)!!; assertEquals(220.0, p.hz, 220 * 0.006)
    }
    @Test fun emptyOrMissingLibraryFallsBackToLabelledSynth() {
        for (provider in listOf<StemClipProvider?>(null, StemClipProvider { emptyList() }, StemClipProvider { listOf(StemClip("bass", ShortArray(0), 44100)) })) {
            val c = EarSourceSelector.select(provider)
            assertTrue(c.source.isFallback); assertEquals("Synth tones (stem library is empty)", c.source.label)
        }
    }
    @Test fun unreadableOrUnpitchedLibraryFallsBack() {
        val boom = EarSourceSelector.select { throw java.io.IOException("disk") }
        assertTrue(boom.source.isFallback); assertTrue(boom.source.label.startsWith("Synth tones")); assertEquals("disk", boom.error)
        val drumsOnly = EarSourceSelector.select { listOf(StemClip("drums", sine(220.0, 3.0), 44100)) }
        assertTrue(drumsOnly.source.isFallback); assertEquals(SynthToneSource.SYNTH_UNUSABLE_LABEL, drumsOnly.source.label)
        val noisy = EarSourceSelector.select { listOf(StemClip("other", noise(3.0), 44100)) }
        assertTrue(noisy.source.isFallback)
    }
    @Test fun pitchedStemIsUsedAndShiftedToTarget() {
        val bass = sine(110.0, 2.0) + ShortArray(4410) + sine(146.83, 2.0) // A2 then D3
        val c = EarSourceSelector.select { listOf(StemClip("bass", bass, 44100), StemClip("drums", noise(4.0), 44100)) }
        assertFalse(c.source.isFallback); assertTrue(c.source.label.startsWith("Stem library (bass"))
        val src = c.source as StemGrainSource
        assertTrue(src.grains.any { abs(it.midi - 45.0) < 0.2 }); assertTrue(src.grains.any { abs(it.midi - 50.0) < 0.2 })
        val out = src.render(listOf(52), Layout.SEQUENTIAL) // E3 = 164.81 Hz, shifted from the D3 grain
        val p = GrainFinder.detect(f(out), 6000, EAR_SR)!!; assertEquals(164.81, p.hz, 164.81 * 0.008)
    }
    @Test fun varispeedPadsWhenGrainRunsOut() {
        val g = NoteGrain("other", FloatArray(1000) { sin(it * 0.1f) }, 60.0)
        val y = StemGrainSource.shifted(g, 72, 2000)
        assertEquals(2000, y.size); assertTrue(y.copyOfRange(600, 2000).all { it == 0f })
    }
}
