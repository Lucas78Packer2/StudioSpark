package com.studiospark.app.importer

/**
 * Chunked overlap-add for long files (2.2.0 Import). Pure Kotlin so the math is unit-tested on the JVM.
 *
 * The file is cut into fixed-size chunks that overlap by [overlap] frames. Each chunk is processed on its own
 * (the separator always sees exactly [size] frames; the last chunk is zero-padded so the ONNX shape stays fixed).
 * In every overlap the previous chunk fades out and the next fades in with complementary linear weights
 * (w and 1 - w), so the weights sum to exactly 1 everywhere and an identity process reconstructs the input.
 * Output is streamed: only the previous chunk's tail is held, never a second full-length copy of the song.
 */
object OverlapAdd {
    data class Chunk(val index: Int, val start: Int, val length: Int) { val end get() = start + length }

    /** Chunk plan covering [frames] exactly once (before cross-fading). */
    fun plan(frames: Int, size: Int, overlap: Int): List<Chunk> {
        require(frames >= 0 && size > 0 && overlap in 0 until size)
        if (frames == 0) return emptyList()
        val step = size - overlap; val out = ArrayList<Chunk>(); var start = 0
        while (true) {
            if (start + size >= frames) { out += Chunk(out.size, start, frames - start); return out }
            out += Chunk(out.size, start, size); start += step
        }
    }

    /** Hann (raised-cosine) fade-in weight for frame [i] inside an overlap of length [overlap]; the previous chunk's
     *  fade-out is 1 - this, so the pair always sums to exactly 1 (constant gain through the seam). */
    fun fadeIn(i: Int, overlap: Int): Float = (0.5 - 0.5 * kotlin.math.cos(kotlin.math.PI * (i + 0.5) / overlap)).toFloat()

    /**
     * Streams per-chunk results for [outputs] mono signals into [write] (output index, absolute frame, value).
     * Every frame of the file is written exactly once.
     */
    class Writer(private val frames: Int, val size: Int, val overlap: Int, private val outputs: Int, private val write: (Int, Int, Float) -> Unit) {
        val chunks = plan(frames, size, overlap)
        private var tail: Array<FloatArray>? = null
        private var next = 0
        /** [data][output] holds at least chunk.length frames of the processed chunk. Chunks must be pushed in order. */
        fun push(c: Chunk, data: Array<FloatArray>) {
            require(c == chunks[next]) { "chunks must be pushed in order" }
            require(data.size == outputs && data.all { it.size >= c.length })
            val last = c.index == chunks.lastIndex
            val prev = tail
            val blend = if (prev == null) 0 else minOf(overlap, c.length)
            val emitEnd = if (last) c.length else c.length - overlap
            for (o in 0 until outputs) {
                val d = data[o]
                for (i in 0 until emitEnd) {
                    val v = if (i < blend) { val w = fadeIn(i, overlap); prev!![o][i] * (1f - w) + d[i] * w } else d[i]
                    write(o, c.start + i, v)
                }
            }
            tail = if (last) null else Array(outputs) { o -> data[o].copyOfRange(c.length - overlap, c.length) }
            next++
        }
        val done get() = next == chunks.size
    }
}
