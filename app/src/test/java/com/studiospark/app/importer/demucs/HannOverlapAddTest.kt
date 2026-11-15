package com.studiospark.app.importer.demucs

import com.studiospark.app.importer.ArrayStereoSource
import com.studiospark.app.importer.StemSink
import com.studiospark.app.importer.StereoSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Overlap-add math through the streaming path: main-tree StereoSource -> SegmentModelSplitEngine (Hann OLA) ->
 * main-tree StemSink, with an identity model (every stem = the input, downmixed to mono by the engine).
 */
class HannOverlapAddTest {
    private val sr = 44_100

    /** Mono sine (440 Hz + 1 kHz) duplicated to L = R, so the engine's mono downmix is exactly the sine. */
    private fun sineAt(i: Int) = (0.5 * sin(2 * PI * 440.0 * i / sr) + 0.3 * sin(2 * PI * 1000.0 * i / sr + 0.3)).toFloat()

    /** Streams frames computed on the fly and counts what was read, so the test proves no whole-song buffer is needed. */
    private class SineSource(override val frames: Int, val f: (Int) -> Float) : StereoSource {
        var maxRead = 0
        override fun read(startFrame: Int, frames: Int, dst: FloatArray) {
            assertTrue("negative start $startFrame", startFrame >= 0)
            maxRead = maxOf(maxRead, frames)
            for (i in 0 until frames) { val g = startFrame + i; val v = if (g < this.frames) f(g) else 0f; dst[2 * i] = v; dst[2 * i + 1] = v }
        }
    }

    private class Identity(override val segmentFrames: Int, val gains: FloatArray = FloatArray(4) { 1f }) : SegmentModel {
        override val stemOrder = Stem.HTDEMUCS_ORDER
        var calls = 0
        override fun run(input: Array<FloatArray>): Array<Array<FloatArray>> {
            calls++
            return Array(4) { s -> Array(2) { c -> FloatArray(segmentFrames) { input[c][it] * gains[s] } } }
        }
    }

    /** Collecting sink that enforces the contract: every sample once, frames increasing per stem. */
    private class CheckingSink(stems: Int, frames: Int) : StemSink {
        val out = Array(stems) { FloatArray(frames) { Float.NaN } }
        private val last = IntArray(stems) { -1 }
        override fun write(stem: Int, frame: Int, value: Float) {
            if (frame != last[stem] + 1) fail("stem $stem: frame $frame after ${last[stem]}")
            last[stem] = frame; out[stem][frame] = value
        }
        fun complete() = out.all { it.none { v -> v.isNaN() } }
    }

    private fun run(frames: Int, segment: Int, hop: Int, gains: FloatArray = FloatArray(4) { 1f }): Triple<CheckingSink, SineSource, Identity> {
        val src = SineSource(frames, ::sineAt); val model = Identity(segment, gains); val sink = CheckingSink(4, frames)
        val labels = ArrayList<Pair<String, Float>>()
        SegmentModelSplitEngine(model, hop, "test").separate(src, sink, { false }) { l, f -> labels += l to f }
        assertTrue(sink.complete())
        assertEquals(1f, labels.last().second, 0f)
        assertTrue(labels.zipWithNext().all { (a, b) -> b.second >= a.second })
        return Triple(sink, src, model)
    }

    @Test fun identitySineReconstructsWithOneSecondHopAndHtDemucsSegment() {
        val frames = (sr * 20.3).toInt()          // several full segments plus a ragged tail
        val seg = 343_980
        val (sink, src, model) = run(frames, seg, sr)
        val y = sink.out[0]
        var worst = 0f; for (i in 0 until frames) worst = maxOf(worst, abs(y[i] - sineAt(i)))
        assertTrue("max error $worst", worst <= 1e-5f)
        // chunk boundaries: every 1 s hop start and every segment end, explicitly
        val boundaries = (0 until frames step sr) + (0 until frames).filter { (it + seg - sr) % sr == 0 }
        for (b in boundaries) for (f in maxOf(0, b - 2)..minOf(frames - 1, b + 2)) assertEquals("boundary frame $f", sineAt(f), y[f], 1e-5f)
        // file edges
        for (f in (0 until 64) + (frames - 64 until frames)) assertEquals("edge frame $f", sineAt(f), y[f], 1e-5f)
        // streaming: never asked for more than one segment at a time; one model call per planned chunk
        assertTrue(src.maxRead <= seg)
        assertEquals(HannOverlapAdd(frames, seg, sr, 1, 4) { _, _, _ -> }.chunkCount, model.calls)
    }

    @Test fun stemIndicesFollowStemsList() {
        // drums x1, bass x0.5, other x-1, vocals x0.25 -> sink index i must carry stems[i]
        val gains = floatArrayOf(1f, 0.5f, -1f, 0.25f)
        val e = SegmentModelSplitEngine(Identity(2_000), 500, "t")
        assertEquals(listOf("drums", "bass", "other", "vocals"), e.stems)
        val (sink, _, _) = run(7_777, 2_000, 500, gains)
        for (s in 0 until 4) for (i in 0 until 7_777 step 13) assertEquals("stem $s frame $i", sineAt(i) * gains[s], sink.out[s][i], 1e-5f)
    }

    @Test fun shortFilesExactMultiplesAndSmallSegments() {
        for ((frames, seg, hop) in listOf(Triple(1, 8, 4), Triple(5, 8, 4), Triple(100, 16, 4), Triple(96, 16, 8), Triple(97, 17, 5), Triple(sr / 2, 343_980, sr))) {
            val (sink, _, _) = run(frames, seg, hop)
            for (i in 0 until frames) assertEquals("frames=$frames seg=$seg hop=$hop i=$i", sineAt(i), sink.out[0][i], 1e-5f)
        }
    }

    @Test fun worksWithMainTreeArrayStereoSource() {
        val frames = 3_001
        val inter = FloatArray(frames * 2) { sineAt(it / 2) }
        val sink = CheckingSink(4, frames)
        SegmentModelSplitEngine(Identity(1_024), 256, "t").separate(ArrayStereoSource(inter), sink, { false }) { _, _ -> }
        for (i in 0 until frames) assertEquals(sineAt(i), sink.out[3][i], 1e-5f)
    }

    @Test fun cancelBetweenSegmentsThrowsInterrupted() {
        val model = Identity(1_000); var polls = 0
        try {
            SegmentModelSplitEngine(model, 250, "t").separate(SineSource(10_000, ::sineAt), CheckingSink(4, 10_000), { ++polls > 3 }) { _, _ -> }
            fail("expected InterruptedException")
        } catch (_: InterruptedException) { }
        assertEquals(3, model.calls)
    }

    @Test fun chunkPlanAndWindow() {
        val ola = HannOverlapAdd(sr * 10, 343_980, sr, 2, 4) { _, _, _ -> }
        assertEquals(-(343_980 - sr), ola.chunkStart(0))
        val last = ola.chunkStart(ola.chunkCount - 1)
        assertTrue(last < sr * 10 && last >= sr * 10 - sr)
        assertEquals(0f, HannOverlapAdd.hann(8)[0], 0f)
        assertEquals(1f, HannOverlapAdd.hann(8)[4], 1e-6f)
    }

    @Test(expected = IllegalArgumentException::class) fun hopLargerThanHalfSegmentRejected() {
        SegmentModelSplitEngine(Identity(10), 6, "t")
    }
}
