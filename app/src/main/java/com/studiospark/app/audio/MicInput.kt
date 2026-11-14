package com.studiospark.app.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder

/** One way of opening the microphone: audio source, sample format and rate. */
data class MicConfig(val source: Int, val float: Boolean, val rate: Int) {
    val label: String get() = "${if (source == MediaRecorder.AudioSource.VOICE_PERFORMANCE) "VOICE_PERFORMANCE" else if (source == MediaRecorder.AudioSource.MIC) "MIC" else "source $source"} + ${if (float) "PCM_FLOAT" else "PCM_16BIT"} @ $rate Hz"
}

/** The fallback ladder, tried in order until one config actually delivers audio. */
object MicLadder {
    fun configs(engineSr: Int): List<MicConfig> {
        val vp = MediaRecorder.AudioSource.VOICE_PERFORMANCE; val mic = MediaRecorder.AudioSource.MIC
        return listOf(MicConfig(vp, true, engineSr), MicConfig(vp, false, engineSr), MicConfig(mic, false, engineSr)) +
            listOf(48000, 44100).filter { it != engineSr }.map { MicConfig(mic, false, it) }
    }
}

object Pcm16 {
    /** 16-bit PCM → float in [-1, 1): s / 32768. */
    fun toFloat(src: ShortArray, n: Int, dst: FloatArray) { for (i in 0 until n) dst[i] = src[i] / 32768f }
}

/** Streaming linear-interpolation resampler (state carried across chunks). */
class LinearResampler(val inRate: Int, val outRate: Int) {
    private val step = inRate.toDouble() / outRate
    private var pos = 0.0; private var prev = 0f
    fun maxOut(n: Int) = (n * outRate.toLong() / inRate).toInt() + 4
    fun process(src: FloatArray, n: Int, out: FloatArray): Int {
        if (n <= 0) return 0
        var o = 0
        while (o < out.size) {
            val i = kotlin.math.floor(pos).toInt(); if (i >= n - 1) break
            val a = if (i < 0) prev else src[i]; val b = src[i + 1]
            out[o++] = a + (b - a) * (pos - i).toFloat(); pos += step
        }
        prev = src[n - 1]; pos -= n
        return o
    }
}

/** Minimal recorder surface so the open/fallback/retry logic is testable without hardware. */
interface MicRecorder {
    val initialized: Boolean
    /** Starts capture; true only if the recorder reports it is actually recording. */
    fun start(): Boolean
    /** Reads up to dst.size mono frames as floats at the recorder's own rate. Returns frames read or a negative AudioRecord error code. */
    fun read(dst: FloatArray): Int
    fun release()
    val bufferFrames: Int
}

/** Builds a recorder for [cfg] on [deviceId] (null = system default input); may throw or return null when refused. */
typealias MicRecorderFactory = (cfg: MicConfig, deviceId: Int?, chunk: Int) -> MicRecorder?

@SuppressLint("MissingPermission")
class AndroidMicRecorder private constructor(private val r: AudioRecord, private val float: Boolean, chunk: Int, override val bufferFrames: Int) : MicRecorder {
    private val shorts = if (float) ShortArray(0) else ShortArray(chunk)
    override val initialized get() = r.state == AudioRecord.STATE_INITIALIZED
    override fun start(): Boolean { r.startRecording(); return r.recordingState == AudioRecord.RECORDSTATE_RECORDING }
    override fun read(dst: FloatArray): Int =
        if (float) r.read(dst, 0, dst.size, AudioRecord.READ_BLOCKING)
        else { val n = r.read(shorts, 0, minOf(dst.size, shorts.size), AudioRecord.READ_BLOCKING); if (n > 0) Pcm16.toFloat(shorts, n, dst); n }
    override fun release() { runCatching { r.stop() }; runCatching { r.release() } }

    companion object {
        fun factory(am: AudioManager): MicRecorderFactory = { cfg, deviceId, chunk ->
            val enc = if (cfg.float) AudioFormat.ENCODING_PCM_FLOAT else AudioFormat.ENCODING_PCM_16BIT
            val bpf = if (cfg.float) 4 else 2
            val min = AudioRecord.getMinBufferSize(cfg.rate, AudioFormat.CHANNEL_IN_MONO, enc)
            if (min <= 0) throw IllegalArgumentException("getMinBufferSize returned $min")
            val r = AudioRecord.Builder().setAudioSource(cfg.source)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(cfg.rate).setEncoding(enc).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
                .setBufferSizeInBytes(maxOf(min, chunk * bpf * 4)).build()
            deviceId?.let { id -> am.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.id == id }?.let { r.preferredDevice = it } }
            AndroidMicRecorder(r, cfg.float, chunk, maxOf(min / bpf, chunk))
        }
    }
}

/**
 * Owns the microphone for the input thread: opens it through [MicLadder] (preferred device first, then the default device),
 * backs off and gives up with a visible [error] instead of retrying silently, reopens on read errors, and flags all-zero input.
 */
class MicSession(
    private val engineSr: Int,
    private val factory: MicRecorderFactory,
    private val log: (String) -> Unit,
    val maxAttempts: Int = 5,
    val retryMs: Long = 2000,
    val chunk: Int = 96,
) {
    @Volatile var error: String? = null; private set
    @Volatile var warning: String? = null; private set
    @Volatile var active = false; private set
    @Volatile var config: MicConfig? = null; private set
    /** Device actually opened (null = system default). */
    @Volatile var device: Int? = null; private set
    @Volatile var latencyMs = 0f; private set
    var failures = 0; private set

    private var rec: MicRecorder? = null
    private var requested: Int? = null
    private var nextAttemptAt = 0L
    private var streamErrors = 0; private var goodFrames = 0L; private var lastDataAt = 0L
    private var zeroFrames = 0L; private var silenceLogged = false
    private val buf = FloatArray(chunk)
    private var resampler: LinearResampler? = null; private var rsOut = FloatArray(0)

    companion object {
        const val SILENCE = "Mic is open but receiving silence. Check the Input is set to Phone microphone."
        const val STALL_MS = 2000L
    }

    /** Runs one step. Returns true if audio was delivered to [sink] (else the caller should sleep briefly). */
    fun tick(need: Boolean, preferred: Int?, now: Long, sink: (FloatArray, Int) -> Unit): Boolean {
        if (!need) { if (rec != null) { close(); log("Mic closed (no longer wanted)") }; failures = 0; streamErrors = 0; nextAttemptAt = 0; error = null; warning = null; return false }
        if (rec != null && preferred != requested) { log("Input changed to ${preferred ?: "default"}; reopening"); close(); failures = 0; streamErrors = 0; nextAttemptAt = 0 }
        if (rec == null) {
            if (failures > 0 && preferred != requested) { failures = 0; streamErrors = 0; nextAttemptAt = 0 }   // user picked another input: try again now
            if (failures >= maxAttempts || now < nextAttemptAt) return false
            return open(preferred, now, sink)
        }
        val r = rec!!
        val n = try { r.read(buf) } catch (t: Throwable) { log("read threw ${t.javaClass.simpleName}: ${t.message}"); -1 }
        if (n < 0) { streamFailure("read() returned error $n${readErrorName(n)}", now); return false }
        if (n == 0) { if (now - lastDataAt > STALL_MS) streamFailure("read() returned no audio for ${STALL_MS / 1000} s", now); return false }
        lastDataAt = now
        goodFrames += n; if (goodFrames >= engineSr) streamErrors = 0
        deliver(n, sink)
        return true
    }

    private fun readErrorName(n: Int) = when (n) { AudioRecord.ERROR_INVALID_OPERATION -> " (ERROR_INVALID_OPERATION)"; AudioRecord.ERROR_BAD_VALUE -> " (ERROR_BAD_VALUE)"; AudioRecord.ERROR_DEAD_OBJECT -> " (ERROR_DEAD_OBJECT)"; AudioRecord.ERROR -> " (ERROR)"; else -> "" }

    private fun streamFailure(why: String, now: Long) {
        log("Mic stream failed on ${config?.label}: $why; releasing and reopening")
        close(); streamErrors++
        if (streamErrors > maxAttempts) { failures = maxAttempts; error = "the mic stream kept failing ($why). Disarm and re-arm the track to try again." }
        else { error = "mic stream interrupted ($why), reopening…"; nextAttemptAt = now }
    }

    private fun open(preferred: Int?, now: Long, sink: (FloatArray, Int) -> Unit): Boolean {
        requested = preferred
        val devices = if (preferred != null) listOf(preferred, null) else listOf(null)
        var last = "no config tried"
        for (dev in devices) for (cfg in MicLadder.configs(engineSr)) {
            val where = "${cfg.label} on ${dev?.let { "device $it" } ?: "default device"}"
            var r: MicRecorder? = null
            try {
                r = factory(cfg, dev, chunk) ?: throw IllegalStateException("recorder not created")
                if (!r.initialized) throw IllegalStateException("state not INITIALIZED")
                if (!r.start()) throw IllegalStateException("startRecording() did not reach RECORDSTATE_RECORDING")
                val n = r.read(buf)
                if (n <= 0) throw IllegalStateException("first read returned $n${readErrorName(n)}")
                log("Mic opened: $where")
                rec = r; config = cfg; device = dev; active = true; error = null; failures = 0
                latencyMs = maxOf(r.bufferFrames, chunk) * 1000f / cfg.rate
                resampler = if (cfg.rate != engineSr) LinearResampler(cfg.rate, engineSr).also { rsOut = FloatArray(it.maxOut(chunk)) } else null
                goodFrames = n.toLong(); lastDataAt = now; zeroFrames = 0; silenceLogged = false
                deliver(n, sink)
                return true
            } catch (t: Throwable) {
                last = "$where: ${t.javaClass.simpleName}: ${t.message}"
                log("Mic attempt failed — $last")
                r?.let { runCatching { it.release() } }
            }
        }
        failures++
        nextAttemptAt = now + retryMs
        error = if (failures >= maxAttempts) "could not open the microphone after $failures attempts ($last). Disarm and re-arm the track to try again."
            else "could not open the microphone ($last). Retrying (attempt $failures of $maxAttempts)…"
        log("Mic open failed (attempt $failures/$maxAttempts): every config refused")
        return false
    }

    private fun deliver(n: Int, sink: (FloatArray, Int) -> Unit) {
        var allZero = true; for (i in 0 until n) if (buf[i] != 0f) { allZero = false; break }
        if (allZero) { zeroFrames += n; val rate = config?.rate ?: engineSr
            if (zeroFrames >= rate * 2L && warning == null) { warning = SILENCE; if (!silenceLogged) { log("Silence watchdog: ${zeroFrames} consecutive zero samples on ${config?.label}"); silenceLogged = true } } }
        else { zeroFrames = 0; warning = null }
        val rs = resampler
        if (rs == null) sink(buf, n) else { val m = rs.process(buf, n, rsOut); if (m > 0) sink(rsOut, m) }
    }

    fun close() { rec?.let { runCatching { it.release() } }; rec = null; active = false; config = null; zeroFrames = 0 }
}
