package com.studiospark.app.importer.demucs

import kotlin.math.PI
import kotlin.math.cos

/**
 * Streaming Hann-window overlap-add for chunked stem separation (pure Kotlin, JVM unit-tested).
 *
 * The model always sees exactly [segment] frames. Chunk k starts at `-pad + k * hop` with `pad = segment - hop`,
 * so the first chunk already ends one hop into the file and the last one starts inside its final hop: every real
 * frame is covered by at least `floor(segment / hop)` (>= 2) chunks, and frames outside the file are zeros.
 * Each processed chunk is multiplied by a periodic Hann window and accumulated together with the window itself;
 * output = sum(w * y) / sum(w). Because of that normalization the weights need not sum to a constant (the default
 * 7.8 s segment / 1 s hop does not), and an identity model reconstructs the input exactly, edges included.
 *
 * Memory: one segment of accumulator per output/channel (input is pulled per chunk via [readChunk]). Frames are emitted to [sink] as soon as no
 * later chunk can touch them, in order, each frame exactly once (absolute start frame, planar block [channel][n]).
 */
class HannOverlapAdd(
    val totalFrames: Int,
    val segment: Int,
    val hop: Int,
    val channels: Int,
    val outputs: Int,
    private val sink: (output: Int, startFrame: Int, block: Array<FloatArray>) -> Unit,
) {
    init {
        require(totalFrames >= 0 && channels > 0 && outputs > 0)
        require(segment > 1 && hop in 1..segment / 2) { "need 1 <= hop <= segment/2 (hop=$hop, segment=$segment)" }
    }

    val pad: Int = segment - hop
    /** Number of model calls for this file. */
    val chunkCount: Int = if (totalFrames == 0) 0 else ((totalFrames.toLong() + pad + hop - 1) / hop).toInt()
    val window: FloatArray = hann(segment)

    /** Absolute start frame (may be negative) of chunk [k]. */
    fun chunkStart(k: Int): Int = -pad + k * hop

    private val acc = Array(outputs) { Array(channels) { FloatArray(segment) } }
    private val wsum = FloatArray(segment)
    private var base = if (chunkCount > 0) chunkStart(0) else 0
    private var next = 0
    val done: Boolean get() = next == chunkCount && finished
    private var finished = chunkCount == 0

    /**
     * Streams the model input for chunk [k] into [dst] (planar [channel][segment]) by calling
     * [read] (startFrame, frames, interleavedDst) — the StereoSource contract: zeros past the end of the file.
     * Frames before 0 (the leading pad) are zero-filled here, so [read] never sees a negative start.
     * The input channel count is `dst.size` (stereo for HT-Demucs) and may differ from the OLA [channels]
     * (the engine overlap-adds mono stems). [scratch] must hold `segment * dst.size` floats; no per-chunk allocation.
     */
    fun readChunk(k: Int, read: (startFrame: Int, frames: Int, dst: FloatArray) -> Unit, scratch: FloatArray, dst: Array<FloatArray>) {
        val ch = dst.size
        require(ch > 0 && scratch.size >= segment * ch && dst.all { it.size >= segment })
        val s = chunkStart(k)
        val lead = maxOf(0, -s)                     // zero frames before the file start
        val n = segment - lead
        for (c in 0 until ch) dst[c].fill(0f, 0, lead)
        if (n <= 0) return
        read(s + lead, n, scratch)
        for (i in 0 until n) for (c in 0 until ch) dst[c][lead + i] = scratch[i * ch + c]
    }

    /** Push the model output for chunk [k] (must be pushed in order): [output][channel][segment]. */
    fun push(k: Int, data: Array<Array<FloatArray>>) {
        check(!finished) { "already finished" }
        require(k == next) { "chunks must be pushed in order (expected $next, got $k)" }
        require(data.size == outputs && data.all { o -> o.size == channels && o.all { it.size >= segment } }) { "bad chunk shape" }
        if (k > 0) { emit(hop); shift(hop) }
        for (o in 0 until outputs) for (c in 0 until channels) {
            val a = acc[o][c]; val d = data[o][c]
            for (i in 0 until segment) a[i] += window[i] * d[i]
        }
        for (i in 0 until segment) wsum[i] += window[i]
        next++
        if (next == chunkCount) { emit(segment); finished = true }
    }

    /** Emit accumulator positions [0, n) (absolute frames base..base+n), clipped to the file. */
    private fun emit(n: Int) {
        val from = maxOf(0, -base); val to = minOf(n, totalFrames - base)
        if (to <= from) return
        val len = to - from
        for (o in 0 until outputs) {
            val block = Array(channels) { c ->
                val a = acc[o][c]
                FloatArray(len) { i -> val w = wsum[from + i]; if (w > 1e-8f) a[from + i] / w else 0f }
            }
            sink(o, base + from, block)
        }
    }

    private fun shift(n: Int) {
        for (o in 0 until outputs) for (c in 0 until channels) {
            val a = acc[o][c]; System.arraycopy(a, n, a, 0, segment - n); a.fill(0f, segment - n, segment)
        }
        System.arraycopy(wsum, n, wsum, 0, segment - n); wsum.fill(0f, segment - n, segment)
        base += n
    }

    companion object {
        /** Periodic Hann: w[i] = 0.5 * (1 - cos(2 pi i / n)). */
        fun hann(n: Int): FloatArray = FloatArray(n) { i -> (0.5 * (1.0 - cos(2.0 * PI * i / n))).toFloat() }
    }
}
