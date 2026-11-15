package com.studiospark.app.engine.shared

import com.studiospark.app.audio.AudioEngine
import com.studiospark.app.core.Track
import com.studiospark.app.dsp.Rig
import com.studiospark.app.dsp.RigProcessor

/** The three tabs that sit on the one shared engine. Each gets its own isolated session (see [SessionHost]). */
enum class SessionKind(val label: String) { STUDIO("Studio"), IMPORT("Import"), SONGWRITER("Songwriter") }

/**
 * How a mic/interface track records. Both modes monitor through the track's amp and pedals.
 * [MONITOR_FX_RECORD_DRY]: the take is stored dry and the rig is applied at playback (re-ampable).
 * [PRINT_PROCESSED]: the take is captured post-rig and plays back with the rig bypassed (never processed twice).
 * Maps 1:1 onto the 2.1.0 Track.printTone flag that the existing Mixer/AudioEngine already honour.
 */
enum class CaptureMode { MONITOR_FX_RECORD_DRY, PRINT_PROCESSED }

/** The user button a handoff came from. There is no automatic or background path into the Studio project. */
enum class HandoffTrigger(val from: SessionKind) { SONGWRITER_SEND_TO_STUDIO(SessionKind.SONGWRITER), IMPORT_COMMIT(SessionKind.IMPORT) }

/** The slice of the 2.1.0 AudioEngine every tab uses. JVM tests supply a fake. */
interface AudioBackend {
    fun play()
    fun stop()
    fun locate(frame: Long)
    fun record()
    fun preview(audio: ShortArray?, rig: RigProcessor? = null, loop: Boolean = false)
}

/** Adapter over the existing 2.1.0 AudioEngine (recording, playback, amp/pedal DSP). No DSP is reimplemented here. */
class AudioEngineBackend(private val engine: AudioEngine) : AudioBackend {
    override fun play() { engine.play() }
    override fun stop() { engine.stopTransport() }
    override fun locate(frame: Long) { engine.locate(frame) }
    override fun record() { engine.record() }
    override fun preview(audio: ShortArray?, rig: RigProcessor?, loop: Boolean) { engine.preview(audio, rig, loop) }
}

/**
 * The one recorder/playback/amp/pedal engine under Studio, Import and Songwriter. The backend is created on first
 * use only — constructing a SharedEngine touches no audio device, thread, rig or sample.
 */
class SharedEngine(private val factory: () -> AudioBackend) {
    private var backend: AudioBackend? = null
    var createCount = 0; private set
    @Synchronized fun isCreated() = backend != null
    @Synchronized fun backend(): AudioBackend = backend ?: factory().also { backend = it; createCount++ }

    fun play() = backend().play()
    fun stop() { if (isCreated()) backend().stop() }
    fun locate(frame: Long) = backend().locate(frame)
    fun record() = backend().record()
    /** Private audition bus (Songwriter playback, Import stem preview). Stopping a preview never creates the engine. */
    fun preview(audio: ShortArray?, rig: RigProcessor? = null, loop: Boolean = false) {
        if (audio == null && !isCreated()) return
        backend().preview(audio, rig, loop)
    }

    companion object {
        fun captureMode(t: Track) = if (t.printTone) CaptureMode.PRINT_PROCESSED else CaptureMode.MONITOR_FX_RECORD_DRY
        fun setCaptureMode(t: Track, mode: CaptureMode) { t.printTone = mode == CaptureMode.PRINT_PROCESSED }

        /** Offline "print processed": runs [audio] through a fresh copy of [rig] using the existing RigProcessor. */
        fun printProcessed(audio: ShortArray, rig: Rig, sr: Float, processor: (Float, Rig) -> RigProcessor = { s, r -> RigProcessor(s, r) }): ShortArray {
            val proc = processor(sr, rig.copy()); val out = ShortArray(audio.size); val buf = FloatArray(512); var i = 0
            while (i < audio.size) { val n = minOf(512, audio.size - i)
                for (k in 0 until n) buf[k] = audio[i + k] / 32768f
                proc.process(buf, n)
                for (k in 0 until n) out[i + k] = (buf[k].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                i += n }
            return out
        }
    }
}
