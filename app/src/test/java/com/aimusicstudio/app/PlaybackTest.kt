package com.aimusicstudio.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

class PlaybackTest {
    private fun energy(x: FloatArray, from: Double, to: Double) = (((from * SR).toInt()) until minOf(x.size, (to * SR).toInt())).sumOf { abs(x[it]).toDouble() }

    @Test fun drumLoopRendersAudio() {
        var dp = DrumPattern(); listOf(0, 4, 8, 12).forEach { dp = dp.toggled(DrumPad.KICK, it) }
        for (k in listOf(DrumKit.ROCK, DrumKit.MASK_METAL, DrumKit.PROG_ARENA, DrumKit.DJENT)) {
            val (l, _) = MixRenderer.render(StudioSession(drumPattern = dp, kit = k), File("/tmp"))
            assertTrue("kit $k silent", energy(l, 0.0, 0.1) > 1.0)
            assertTrue("kit $k second bar silent", energy(l, 2.0, 2.1) > 1.0) // loop repeats across bars
        }
    }
    @Test fun bassMidiTrackRendersAudio() {
        val t = Track(name = "Bass", kind = TrackKind.SYNTH, waveform = SynthWaveform.SUB_BASS, notes = listOf(SynthNote(pitch = 33, startTime = 0.5, duration = 1.0)))
        val (l, _) = MixRenderer.render(StudioSession(tracks = listOf(t)), File("/tmp"))
        assertTrue(energy(l, 0.6, 1.4) > 10.0); assertTrue(energy(l, 0.0, 0.45) < 1e-3)
    }
    @Test fun guitarThroughPedalsAndAmpRenders() {
        val dry = Track(name = "G", kind = TrackKind.GUITAR, notes = listOf(SynthNote(pitch = 40, startTime = 0.0, duration = 1.0)))
        val wet = dry.copy(amp = AmpModel.METAL, pedals = listOf(PedalSlot(PedalType.METAL_DIST, amount = 0.7f)))
        val a = MixRenderer.render(StudioSession(tracks = listOf(dry)), File("/tmp")).first
        val b = MixRenderer.render(StudioSession(tracks = listOf(wet)), File("/tmp")).first
        val c = MixRenderer.render(StudioSession(tracks = listOf(wet.copy(pedals = emptyList()))), File("/tmp")).first
        assertTrue(energy(a, 0.0, 1.0) > 1.0); assertTrue(energy(b, 0.0, 1.0) > 1.0)
        assertTrue("pedal/amp should change sound", abs(energy(a, 0.0, 1.0) - energy(b, 0.0, 1.0)) > 1.0)
        assertTrue("removing pedal should change sound", abs(energy(b, 0.0, 1.0) - energy(c, 0.0, 1.0)) > 1.0)
    }
    @Test fun recordedClipPlaysAndStretchLoops() {
        val f = File.createTempFile("take", ".wav"); MixRenderer.writeWavInterleaved(f, FloatArray(SR) { (kotlin.math.sin(it * 0.05) * 0.5).toFloat() }, 1)
        val t = Track(name = "Vox", clips = listOf(AudioClip(fileName = f.name, startTime = 1.0, duration = 3.0)))
        val (l, _) = MixRenderer.render(StudioSession(tracks = listOf(t)), f.parentFile!!)
        assertTrue("take audible", energy(l, 1.1, 1.9) > 10.0)
        assertTrue("stretched past source loops", energy(l, 2.2, 3.9) > 10.0)
        assertTrue(energy(l, 0.0, 0.95) < 1e-3)
    }
}
