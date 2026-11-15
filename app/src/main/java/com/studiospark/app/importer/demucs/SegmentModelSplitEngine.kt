package com.studiospark.app.importer.demucs

import com.studiospark.app.importer.StemSink
import com.studiospark.app.importer.StemSplitEngine
import com.studiospark.app.importer.StereoSource

/**
 * Adapts any [SegmentModel] to the main tree's `com.studiospark.app.importer.StemSplitEngine` (importer/StemModel.kt).
 * No Android or ONNX imports, so the streaming path is unit-tested on the JVM with an identity model.
 *
 * Stem index i written to the [StemSink] is `stems[i]`, i.e. the model's [SegmentModel.stemOrder]; for HT-Demucs:
 * 0 "drums", 1 "bass", 2 "other", 3 "vocals" (matches `DemucsVariant.FOUR.stems`). Values are mono (L+R)/2, 44.1 kHz.
 * [separate] is blocking, like the other engines: the ImportController calls it inside withContext(Dispatchers.Default).
 */
open class SegmentModelSplitEngine(
    private val model: SegmentModel,
    /** Overlap-add hop in frames (1 s = 44 100 per the 2.2.0 spec); must be <= segment / 2. */
    val hopFrames: Int,
    override val provider: String,
    private val onClose: () -> Unit = {},
) : StemSplitEngine {
    init { require(hopFrames in 1..model.segmentFrames / 2) { "hop must be in 1..${model.segmentFrames / 2}" } }

    override val stems: List<String> = model.stemOrder.map { it.id }

    override fun separate(input: StereoSource, output: StemSink, cancel: () -> Boolean, progress: (String, Float) -> Unit) =
        separateStreaming(input.frames, input::read, model, hopFrames, output::write, cancel, progress)

    override fun close() = onClose()
}
