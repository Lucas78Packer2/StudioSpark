package com.studiospark.app.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.*
import android.os.Handler
import android.os.Looper
import android.os.Process
import com.studiospark.app.core.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/** A finished recording pass for one destination (a track id, or null for the jam room). */
class CapturedPass(val trackId: String?, val audio: ShortArray, val notes: List<MidiNote>, val start: Long, val source: String)

data class InputDevice(val id: Int, val name: String, val type: Int, val external: Boolean)

/**
 * Real-time audio I/O. Output runs on a dedicated URGENT_AUDIO thread writing to a low-latency AudioTrack; input runs on its own
 * thread into a lock-free ring. All graph state is mutated on the audio thread via [post], so the UI thread never blocks it.
 */
class AudioEngine(private val ctx: Context, val mixer: Mixer) {
    private val am = ctx.getSystemService(AudioManager::class.java)
    private val commands = ConcurrentLinkedQueue<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    private var outThread: Thread? = null; private var inThread: Thread? = null
    @Volatile var block = 192; private set
    @Volatile var outputLatencyMs = 0f; private set
    @Volatile var inputLatencyMs = 0f; private set
    @Volatile var inputPeak = 0f; private set
    @Volatile var inputActive = false; private set
    @Volatile var xruns = 0; private set
    @Volatile var cpuLoad = 0f; private set
    @Volatile var preferredInput: Int? = null
    var onPasses: ((List<CapturedPass>) -> Unit)? = null
    var onRecordRefused: ((String) -> Unit)? = null

    // input ring
    private val ring = FloatArray(ENGINE_SR); private val wIdx = AtomicInteger(0); private var rIdx = 0
    val tunerBuf = FloatArray(4096); @Volatile var tunerWrite = 0
    @Volatile var wantInput = false

    @Volatile private var closed = false
    /** Queues a command for the audio thread; the engine starts lazily on the first command (threads only — the AudioTrack is built on the audio thread). */
    fun post(cmd: () -> Unit) { commands.add(cmd); if (!running && !closed) start() }

    fun start() {
        if (running) return; running = true
        outThread = Thread({ outputLoop() }, "studiospark-audio-out").apply { priority = Thread.MAX_PRIORITY; start() }
        inThread = Thread({ inputLoop() }, "studiospark-audio-in").apply { priority = Thread.MAX_PRIORITY; start() }
    }
    fun shutdown() { closed = true; running = false; outThread?.join(500); inThread?.join(500) }

    private fun outputLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val fpb = am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull() ?: 192
        block = fpb.coerceIn(64, 512)
        val minBytes = AudioTrack.getMinBufferSize(ENGINE_SR, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(ENGINE_SR).setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY).setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(minBytes, block * 8 * 2)).build()
        // shrink buffer toward two bursts for low latency
        runCatching { track.bufferSizeInFrames = block * 2 }
        outputLatencyMs = track.bufferSizeInFrames * 1000f / ENGINE_SR
        val out = FloatArray(block * 2); val input = FloatArray(block)
        track.play()
        var lastUnder = track.underrunCount
        while (running) {
            while (true) { val c = commands.poll() ?: break; runCatching { c() } }
            readInput(input, block)
            mixer.render(out, input, block)
            track.write(out, 0, block * 2, AudioTrack.WRITE_BLOCKING)
            val u = track.underrunCount; if (u != lastUnder) { xruns += u - lastUnder; lastUnder = u
                if (track.bufferSizeInFrames < block * 6) runCatching { track.bufferSizeInFrames = track.bufferSizeInFrames + block; outputLatencyMs = track.bufferSizeInFrames * 1000f / ENGINE_SR } }
            cpuLoad = mixer.lastBlockNanos / (block * 1e9f / ENGINE_SR)
        }
        track.stop(); track.release()
    }

    private fun readInput(dst: FloatArray, n: Int) {
        val w = wIdx.get(); var avail = (w - rIdx + ring.size) % ring.size
        if (avail > n * 8) { rIdx = (w - n * 2 + ring.size) % ring.size; avail = n * 2 } // drift guard
        var pk = 0f
        for (i in 0 until n) { if (avail > 0) { dst[i] = ring[rIdx]; rIdx = (rIdx + 1) % ring.size; avail-- } else dst[i] = 0f; val a = kotlin.math.abs(dst[i]); if (a > pk) pk = a }
        inputPeak = kotlin.math.max(pk, inputPeak * 0.9f)
    }

    @SuppressLint("MissingPermission")
    private fun inputLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        var rec: AudioRecord? = null; var device: Int? = null
        val buf = FloatArray(96)
        while (running) {
            val need = wantInput && ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (need && (rec == null || device != preferredInput)) {
                rec?.run { runCatching { stop() }; release() }
                rec = runCatching {
                    val min = AudioRecord.getMinBufferSize(ENGINE_SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
                    AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_PERFORMANCE)
                        .setAudioFormat(AudioFormat.Builder().setSampleRate(ENGINE_SR).setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
                        .setBufferSizeInBytes(maxOf(min, 96 * 4 * 4)).build().also { r ->
                            preferredInput?.let { id -> am.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.id == id }?.let { r.preferredDevice = it } }
                            r.startRecording(); inputLatencyMs = maxOf(min / 4, 96) * 1000f / ENGINE_SR }
                }.getOrNull()
                device = preferredInput; inputActive = rec != null
            } else if (!need && rec != null) { val old = rec; runCatching { old.stop() }; old.release(); rec = null; inputActive = false; inputPeak = 0f }
            val r = rec
            if (r == null) { Thread.sleep(20); continue }
            val got = r.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)
            if (got <= 0) { Thread.sleep(2); continue }
            var w = wIdx.get(); for (i in 0 until got) { ring[w] = buf[i]; w = (w + 1) % ring.size; tunerBuf[tunerWrite] = buf[i]; tunerWrite = (tunerWrite + 1) % tunerBuf.size }; wIdx.set(w)
        }
        rec?.run { runCatching { stop() }; release() }
    }

    fun inputDevices(): List<InputDevice> = am.getDevices(AudioManager.GET_DEVICES_INPUTS).filter { it.isSource }.map { d ->
        val ext = d.type in setOf(AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_LINE_DIGITAL, AudioDeviceInfo.TYPE_USB_ACCESSORY)
        val kind = when (d.type) { AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Phone microphone"; AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB audio interface"; AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset mic"; AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset mic"; AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth mic (higher latency)"; else -> "Input" }
        InputDevice(d.id, if (d.productName.isNullOrBlank() || d.type == AudioDeviceInfo.TYPE_BUILTIN_MIC) kind else "$kind · ${d.productName}" + if (d.address.isNotBlank() && d.type == AudioDeviceInfo.TYPE_BUILTIN_MIC) " (${d.address})" else "", d.type, ext)
    }.distinctBy { it.name }

    // ------------------------------------------------------------ transport commands (executed on the audio thread)
    fun play() = post { mixer.resetCursors(); mixer.session.play() }
    fun stopTransport() = post {
        val ses = mixer.session; val wasRec = ses.state == TransportState.RECORDING
        ses.stop(); mixer.allNotesOff()
        if (wasRec) collectPasses("Recording")
    }
    fun locate(pos: Long) = post { mixer.session.locate(pos); mixer.resetCursors() }
    fun record(external: Boolean = false) = post {
        val s = mixer.snap ?: return@post
        when (val r = mixer.session.record(s.project, external)) { is TrackingSession.StartResult.Refused -> main.post { onRecordRefused?.invoke(r.why) }; else -> { mixer.resetCursors() } }
    }
    fun noteOn(trackId: String?, pitch: Int, vel: Int) = post { mixer.liveNoteOn(trackId, pitch, vel) }
    fun noteOff(trackId: String?, pitch: Int) = post { mixer.liveNoteOff(trackId, pitch) }

    /** Called on the audio thread at stop or at each loop-record wrap. */
    private fun collectPasses(source: String) {
        val s = mixer.snap ?: return
        val passes = mutableListOf<CapturedPass>()
        val comp = mixer.latencyCompFrames
        for (t in s.tracks) { val ch = mixer.channels[t.id] ?: continue
            if (ch.captureLen > 0) { val audio = ch.takeCapture(); val notes = ch.capturedNotes.map { it.copy(start = it.start - mixer.session.passStart) }.filter { it.start >= 0 }; ch.capturedNotes.clear(); ch.liveStarts.clear()
                passes += CapturedPass(t.id, audio, notes, if (t.micInput) (mixer.session.passStart - comp).coerceAtLeast(0) else mixer.session.passStart, if (t.micInput) "Mic" else "Instrument") } }
        if (passes.isNotEmpty()) main.post { onPasses?.invoke(passes) }
    }
    init { mixer.onLoopWrap = { if (mixer.session.state == TransportState.RECORDING) collectPasses("Loop pass") } }

    fun startRoomRecording() = post { val r = mixer.room(); r.takeCapture(); r.capturedNotes.clear(); mixer.roomPos = 0; mixer.roomRecording = true }
    fun stopRoomRecording() = post {
        if (!mixer.roomRecording) return@post
        mixer.roomRecording = false; val r = mixer.room(); val audio = r.takeCapture(); val notes = r.capturedNotes.toList(); r.capturedNotes.clear()
        if (audio.isNotEmpty()) main.post { onPasses?.invoke(listOf(CapturedPass(null, audio, notes, mixer.session.position, "Jam Room"))) }
    }
    fun preview(audio: ShortArray?, rig: com.studiospark.app.dsp.RigProcessor? = null, loop: Boolean = false) = post { mixer.previewRig = rig; mixer.previewLoop = loop; mixer.previewPos = 0; mixer.previewAudio = audio }
}
