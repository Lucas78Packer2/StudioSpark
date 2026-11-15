package com.studiospark.app.importer.demucs

/** Separation sample rate (all HT-Demucs exports). */
const val SEPARATION_RATE = 44_100

/** HT-Demucs stems (GUITAR/PIANO only from the 6-stem export). */
enum class Stem(val id: String, val label: String) {
    VOCALS("vocals", "Vocals"), DRUMS("drums", "Drums"), BASS("bass", "Bass"), OTHER("other", "Other"), GUITAR("guitar", "Guitar"), PIANO("piano", "Piano");
    companion object {
        /**
         * HT-Demucs output order (htdemucs `sources`) = stem index order of the engine:
         * 0 drums, 1 bass, 2 other, 3 vocals — same as the main tree's `DemucsVariant.FOUR.stems`.
         */
        val HTDEMUCS_ORDER = listOf(DRUMS, BASS, OTHER, VOCALS)
        /** htdemucs_6s `sources`: drums, bass, other, vocals, guitar, piano. */
        val HTDEMUCS_6S_ORDER = listOf(DRUMS, BASS, OTHER, VOCALS, GUITAR, PIANO)
        fun byId(id: String): Stem? = values().firstOrNull { it.id == id }
    }
}

/** One fixed-size model call. Implemented on device by [HtDemucsOrtModel]; by an identity fake in unit tests. */
interface SegmentModel {
    /** Frames per model call (HT-Demucs: 343 980 = 7.8 s at 44.1 kHz). */
    val segmentFrames: Int
    /** Which [Stem] each output row is (row index == stem index written to the sink). */
    val stemOrder: List<Stem>
    /** [input] planar stereo [2][segmentFrames] -> [stemOrder.size][2][segmentFrames]. Blocking. */
    fun run(input: Array<FloatArray>): Array<Array<FloatArray>>
}

/**
 * Streaming chunked separation (pure Kotlin, blocking — call it from a worker thread / Dispatchers.Default).
 *
 * Pulls one segment at a time through [read] (44.1 kHz interleaved stereo, zeros past the end), runs [model],
 * downmixes each stem to mono, Hann-overlap-adds with a [hopFrames] hop and calls [write] (stemIndex, frame, value)
 * exactly once per frame per stem, frames increasing. Peak memory ~ one segment of input + model output +
 * one segment of accumulator per stem (no whole-song arrays). [cancel] is polled before every model call; when it
 * returns true an [InterruptedException] is thrown (what the main tree's ImportController treats as "Cancelled").
 * [progress] gets (label, fraction in [0, 1]) before each segment and (label, 1) at the end.
 */
fun separateStreaming(
    frames: Int,
    read: (startFrame: Int, frames: Int, dst: FloatArray) -> Unit,
    model: SegmentModel,
    hopFrames: Int,
    write: (stem: Int, frame: Int, value: Float) -> Unit,
    cancel: () -> Boolean = { false },
    progress: (String, Float) -> Unit = { _, _ -> },
) {
    val seg = model.segmentFrames
    val stems = model.stemOrder.size
    val ola = HannOverlapAdd(frames, seg, hopFrames, channels = 1, outputs = stems) { o, start, block ->
        val b = block[0]; for (i in b.indices) write(o, start + i, b[i])
    }
    val scratch = FloatArray(seg * 2)
    val planar = Array(2) { FloatArray(seg) }
    val mono = Array(stems) { arrayOf(FloatArray(seg)) }
    val total = ola.chunkCount
    for (k in 0 until total) {
        if (cancel()) throw InterruptedException("Import cancelled")
        progress("Splitting segment ${k + 1}/$total", k.toFloat() / total)
        ola.readChunk(k, read, scratch, planar)
        val y = model.run(planar)
        require(y.size == stems) { "model returned ${y.size} stems, expected $stems" }
        for (s in 0 until stems) { val l = y[s][0]; val r = y[s][1]; val m = mono[s][0]; for (i in 0 until seg) m[i] = (l[i] + r[i]) * 0.5f }
        ola.push(k, mono)
    }
    check(ola.done)
    progress("Split complete", 1f)
}
