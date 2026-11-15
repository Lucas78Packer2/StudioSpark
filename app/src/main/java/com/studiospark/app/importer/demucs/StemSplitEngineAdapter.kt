package com.studiospark.app.importer.demucs

import com.studiospark.app.importer.ModelGate
import com.studiospark.app.importer.StemSink
import com.studiospark.app.importer.StemSplitEngine
import com.studiospark.app.importer.StereoSource
import java.io.File

/**
 * HT-Demucs engines implementing `com.studiospark.app.importer.StemSplitEngine` (StudioSpark Builder's streaming
 * engine, merged into the main tree in 2.2.0). Constructors load ONNX sessions (blocking): build them on the import
 * worker only, never at startup. Every load goes through `ModelGate.record` (the launch-guard tests count it).
 *
 * Models are downloaded on first use (ModelStore, pinned size + SHA-256). Nothing is bundled in the APK and there is
 * no asset-loading path (a unit test also checks no HT-Demucs .onnx is in assets).
 */
object DemucsHop {
    /**
     * Lucas's 2.2.0 default (revised 9:28 PM CST): 85 995 frames ≈ 1.95 s = a quarter of the 343 980-frame segment.
     * A shorter hop means MORE model calls (slower), not faster: calls per second of audio = 44 100 / hop per model.
     * [SHORT_TEST] (0.25 s) is kept for tests/experiments. Any value in 1..segment/2 (171 990) is accepted.
     */
    const val DEFAULT = 85_995
    const val SHORT_TEST = SEPARATION_RATE / 4
    fun callsPerSecond(hopFrames: Int, models: Int = 1) = models * SEPARATION_RATE.toDouble() / hopFrames
}

/** One HT-Demucs model (4-stem htdemucs, or 6-stem htdemucs_6s with [stemOrder] = HTDEMUCS_6S_ORDER). */
class DemucsStemSplitEngine private constructor(model: HtDemucsOrtModel, hopFrames: Int) :
    SegmentModelSplitEngine(model, hopFrames, model.provider, { model.close() }) {

    constructor(modelFile: File, preferNnapi: Boolean = true, hopFrames: Int = DemucsHop.DEFAULT, stemOrder: List<Stem> = Stem.HTDEMUCS_ORDER) :
        this(load("HT-Demucs ${modelFile.name}") { HtDemucsOrtModel.open(HtDemucsOrtModel.Source.Path(modelFile), preferNnapi, stemOrder) }, hopFrames)

    internal companion object {
        fun load(what: String, open: () -> HtDemucsOrtModel): HtDemucsOrtModel {
            ModelGate.record("ORT environment + $what session")
            return open().also { ModelGate.note("$what on ${it.provider}") }
        }
    }
}

/**
 * htdemucs_ft "bag of models": four fine-tuned specialists, each contributing only its own stem (one-hot bag weights,
 * as in StemSplitio's bag_infer.py). To keep memory to ONE model at a time, the song is streamed through each
 * specialist in turn (4 passes; input comes from the memory-mapped decode file), so compute is 4x a single model.
 * Output stems: drums, bass, other, vocals (same order as DemucsVariant.FOUR).
 */
class BagStemSplitEngine(
    private val specialists: Map<Stem, File>,
    private val preferNnapi: Boolean = true,
    val hopFrames: Int = DemucsHop.DEFAULT,
    private val opener: (Stem, File) -> SegmentModel = { st, f -> DemucsStemSplitEngine.load("HT-Demucs FT ${st.id}") { HtDemucsOrtModel.open(HtDemucsOrtModel.Source.Path(f), preferNnapi) } },
) : StemSplitEngine {
    private val order = Stem.HTDEMUCS_ORDER
    init { require(specialists.keys == order.toSet()) { "need one specialist per stem" } }
    override val stems: List<String> = order.map { it.id }
    override var provider: String = "not loaded"; private set

    override fun separate(input: StereoSource, output: StemSink, cancel: () -> Boolean, progress: (String, Float) -> Unit) {
        order.forEachIndexed { pass, stem ->
            if (cancel()) throw InterruptedException("Import cancelled")
            progress("Loading ${stem.label} model (${pass + 1}/4)", pass / 4f)
            val model = opener(stem, specialists.getValue(stem))
            try {
                provider = (model as? HtDemucsOrtModel)?.provider ?: provider
                val row = model.stemOrder.indexOf(stem); check(row >= 0) { "${stem.id} specialist lacks its stem" }
                separateStreaming(input.frames, input::read, model, hopFrames, { s, f, v -> if (s == row) output.write(pass, f, v) }, cancel) { l, f ->
                    progress("${stem.label} (${pass + 1}/4) · $l", (pass + f) / 4f) }
            } finally { (model as? AutoCloseable)?.close() }
        }
    }
    override fun close() {}
}
