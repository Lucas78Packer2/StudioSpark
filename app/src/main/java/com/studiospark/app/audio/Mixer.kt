package com.studiospark.app.audio

import com.studiospark.app.core.*
import com.studiospark.app.dsp.*
import kotlin.math.*

/** Immutable per-track view handed to the audio thread. */
class TrackSnap(
    val id: String, val family: Family, val voice: String, val kit: DrumKit?, val clips: Array<Clip>, val notes: Array<MidiNote>, val vowels: CharArray,
    val gain: Float, val pan: Float, val mute: Boolean, val solo: Boolean, val monitor: Boolean, val armed: Boolean, val micInput: Boolean,
    val rig: RigProcessor?, val automation: Array<AutoPoint>, val autoOn: Boolean, val brightness: Float, val voiceStyle: Int,
    /** Right-channel rig instance for stereo drum kits (same Rig and knob arrays, independent DSP state) so per-piece pan survives the rig. */
    val rigR: RigProcessor? = null,
    /** Mic capture on this track is printed post-rig ("Print processed tone"). */
    val printTone: Boolean = false,
)

class MixSnap(val project: Project, val tracks: List<TrackSnap>) { val anySolo = tracks.any { it.solo } }

/** Note/voice routing and per-channel state. Owned by the audio thread (or an offline render thread). */
class Channel(val id: String) {
    /** Consecutive blocks with silent rig input; rigs stop processing once their tails have rung out (saves CPU and battery while idle). */
    var silentBlocks = 0
    val voices = ArrayList<Voice>(64)
    val buf = FloatArray(Mixer.MAX_BLOCK); val dry = FloatArray(Mixer.MAX_BLOCK); val post = FloatArray(Mixer.MAX_BLOCK)
    val bufR = FloatArray(Mixer.MAX_BLOCK); val dryR = FloatArray(Mixer.MAX_BLOCK); val vtmp = FloatArray(Mixer.MAX_BLOCK); var silentBlocksR = 0
    var rr = 0
    var noteCursor = 0
    var expectedPos = -1L
    val held = HashMap<Int, Voice>()           // pitch -> voice for MIDI-scheduled notes
    var peak = 0f
    // recording
    val capture = ArrayList<ShortArray>(); var captureLen = 0
    val capturedNotes = ArrayList<MidiNote>(); val liveStarts = HashMap<Int, Pair<Long, Int>>()
    fun capture(src: FloatArray, n: Int) { var i = 0
        while (i < n) { if (captureLen / Mixer.CHUNK >= capture.size) capture.add(ShortArray(Mixer.CHUNK))
            val chunk = capture[captureLen / Mixer.CHUNK]; val off = captureLen % Mixer.CHUNK; val c = min(n - i, Mixer.CHUNK - off)
            for (k in 0 until c) chunk[off + k] = (src[i + k].coerceIn(-1f, 1f) * 32767f).toInt().toShort(); i += c; captureLen += c } }
    fun takeCapture(): ShortArray { val out = ShortArray(captureLen); var p = 0; for (c in capture) { val m = min(Mixer.CHUNK, captureLen - p); if (m <= 0) break; System.arraycopy(c, 0, out, p, m); p += m }; capture.clear(); captureLen = 0; return out }
}

/**
 * The mixing graph: clip playback, MIDI-scheduled notes, live instrument voices, input monitoring, per-track rigs,
 * volume/pan/automation, metronome and the private take/upload preview bus.
 */
class Mixer(val bank: SampleBank, val pool: AudioPool) {
    companion object { const val MAX_BLOCK = 1024; const val CHUNK = ENGINE_SR }
    @Volatile var snap: MixSnap? = null
    val session = TrackingSession()
    val channels = HashMap<String, Channel>()
    fun channel(id: String) = channels.getOrPut(id) { Channel(id) }

    // live-room bus (jam room) and preview bus
    @Volatile var roomRig: RigProcessor? = null
    @Volatile var roomVoice: String = "guitar"
    @Volatile var roomBrightness = 0.6f
    @Volatile var roomKit: DrumKit? = null
    @Volatile var roomGain = 1f
    var roomRecording = false
    @Volatile var previewAudio: ShortArray? = null
    @Volatile var previewRig: RigProcessor? = null
    @Volatile var previewLoop = false
    var previewPos = 0L
    @Volatile var previewGain = 1f
    @Volatile var metronomeFree = false   // click while stopped (jam room / practice)
    @Volatile var clickGain = 0.6f
    private var freeClickPos = 0L
    @Volatile var masterGain = 1f
    @Volatile var latencyCompFrames = 0
    var mixPeakL = 0f; var mixPeakR = 0f
    val trackPeaks = HashMap<String, Float>()
    var lastBlockNanos = 0L

    private val inScratch = FloatArray(MAX_BLOCK); private val tmp = FloatArray(MAX_BLOCK); private val click = FloatArray(MAX_BLOCK)
    private var clickPhase = 0; private var clickLen = 0; private var clickHz = 1000f
    private var lastBeat = -1L
    private val roomCh = Channel("__room"); private val prevBuf = FloatArray(MAX_BLOCK)

    // ---------------------------------------------------------------- voices
    fun startVoice(ch: Channel, voice: String, kit: DrumKit?, pitch: Int, vel: Int, brightness: Float, vowel: Char = 'a', consonant: Boolean = false, style: Int = 0): Voice? {
        val tag = pitch
        val v: Voice? = when {
            voice.startsWith("kit:") -> {
                val k = kit ?: return null; val piece = k.pieces.firstOrNull { it.midi == pitch && it.sounding } ?: return null
                if (piece.chokeGroup > 0) for (o in ch.voices) if (o.choke == piece.chokeGroup) o.kill()
                val st = k.stick; val velo = (vel * st.velScale).toInt().coerceIn(1, 127)
                val s = bank.drum(piece.sample, velo, ch.rr++) ?: return null
                SampleVoice(s.data, 2.0.pow(piece.tune / 12.0), (velo / 127f).pow(0.8f) * Shapers.dbToLin(piece.gainDb) * 0.9f, tag, piece.chokeGroup, st.brightness * (0.6f + 0.4f * velo / 127f), st.attackMs, 80f, sustainHold = true).also { it.pan = piece.pan }
            }
            voice == "guitar" || voice == "bass" -> bank.pitched(voice, pitch, vel, ch.rr++)?.let { (s, r) -> SampleVoice(s.data, r, (vel / 127f) * 0.9f, tag, 0, 0.35f + brightness * 0.65f, 0f, 140f) }
            voice.startsWith("keys_") -> bank.pitched(voice, pitch, vel, 0)?.let { (s, r) -> SampleVoice(s.data, r, (vel / 127f).pow(1.3f) * 0.8f, tag, 0, 0.5f + 0.5f * vel / 127f, 0f, 220f) }
            voice == "voice" -> FormantVoice(pitch, vel, vowel, consonant, style, tag)
            voice == "mic" -> null
            voice.startsWith("tex") -> bank.get("textures/${voice.removePrefix("tex:")}.wav")?.let { SampleVoice(it.data, 2.0.pow((pitch - 60) / 12.0), vel / 127f, tag, 0) }
            SYNTHS.contains(voice) -> SynthVoice(voice, pitch, vel, tag)
            else -> bank.pitched(voice, pitch, vel, 0)?.let { (s, r) -> SampleVoice(s.data, r, (vel / 127f) * 0.9f, tag, 0, 1f, 3f, 180f) }
        }
        if (v != null) { if (ch.voices.size >= 48) ch.voices.minByOrNull { if (it.releasing) 0L else 1L shl 40 or -it.age }?.let { ch.voices.remove(it) }; ch.voices.add(v) }
        return v
    }
    private val SYNTHS = setOf("sub_bass", "saw_lead", "warm_pad", "square_arp", "pluck", "brass_synth", "wt_glass", "wt_vowel", "fm_ep", "fm_bell", "fm_bass", "organ")

    fun liveNoteOn(trackId: String?, pitch: Int, vel: Int) {
        val s = snap
        if (trackId == null) { val ch = roomCh; val v = startVoice(ch, roomVoice, roomKit, pitch, vel, roomBrightness); if (v != null) ch.held[pitch]?.release(); if (v != null) ch.held[pitch] = v
            if (roomRecording) ch.liveStarts[pitch] = roomPos to vel; return }
        val t = s?.tracks?.firstOrNull { it.id == trackId } ?: return
        val ch = channel(t.id); ch.held[pitch]?.release()
        val v = startVoice(ch, t.voice, t.kit, pitch, vel, t.brightness, vowelFor(t, pitch), false, t.voiceStyle) ?: return
        ch.held[pitch] = v
        if (t.armed && session.capturing(s.project)) ch.liveStarts[pitch] = session.position to vel
    }
    fun liveNoteOff(trackId: String?, pitch: Int) {
        val ch = if (trackId == null) roomCh else channels[trackId] ?: return
        ch.held.remove(pitch)?.release()
        ch.liveStarts.remove(pitch)?.let { (st, vel) -> val now = if (trackId == null) roomPos else session.position; ch.capturedNotes.add(MidiNote(pitch, st, max(240L, now - st), vel)) }
    }
    private fun vowelFor(t: TrackSnap, pitch: Int) = 'a'
    var roomPos = 0L

    // ---------------------------------------------------------------- render
    /** Render one block. [input] holds n mono input frames (may be silence). Writes interleaved stereo to [out]. */
    private fun rigInputActive(ch: Channel, buf: FloatArray, n: Int): Boolean {
        var live = false; for (i in 0 until n) if (abs(buf[i]) > 1e-6f) { live = true; break }
        ch.silentBlocks = if (live) 0 else ch.silentBlocks + 1
        return ch.silentBlocks * n < ENGINE_SR * 6 // keep processing 6 s after the last signal so delay/reverb tails finish
    }
    fun render(out: FloatArray, input: FloatArray, n: Int) {
        val t0 = System.nanoTime()
        val s = snap; val p = s?.project; val ses = session
        for (i in 0 until 2 * n) out[i] = 0f
        click.fill(0f, 0, n)
        val rolling = ses.state == TransportState.PLAYING || ses.state == TransportState.RECORDING
        val capturing = p != null && ses.capturing(p)
        val pos = ses.position
        // metronome / count-in
        if (p != null) {
            val spb = p.samplesPerBeat
            val clickOn = (ses.metronome && (rolling || ses.state == TransportState.COUNT_IN)) || metronomeFree
            if (clickOn) {
                for (i in 0 until n) {
                    val beatPos = when { ses.state == TransportState.COUNT_IN -> p.samplesPerBar * ses.countInBars - ses.countInRemaining + i; rolling -> pos + i; else -> freeClickPos + i }
                    if (beatPos % spb == 0L) { val beat = beatPos / spb; clickHz = if (beat % p.sigNum == 0L) 1600f else 1050f; clickPhase = 0; clickLen = ENGINE_SR / 30 }
                    if (clickPhase < clickLen) { val e = 1f - clickPhase.toFloat() / clickLen; click[i] = sin(2f * PI.toFloat() * clickHz * clickPhase / ENGINE_SR) * e * e * clickGain; clickPhase++ }
                }
            }
            if (!rolling && ses.state != TransportState.COUNT_IN) freeClickPos += n
        }
        // tracks
        if (s != null && p != null) {
            for (t in s.tracks) {
                val ch = channel(t.id); val buf = ch.buf; val dry = ch.dry
                for (i in 0 until n) { buf[i] = 0f; dry[i] = 0f }
                val captureThis = capturing && t.armed
                // Print processed tone: the mic is captured after the rig (what you hear), so it is fed to the rig even when not monitored.
                val printCap = captureThis && t.micInput && t.printTone
                if (rolling && !(captureThis)) mixClips(t, pos, buf, n, printedOnly = false)
                if (rolling) scheduleNotes(t, ch, pos, n)
                // voices
                val stereo = t.rigR != null; val bufR = ch.bufR; val dryR = ch.dryR
                if (stereo) for (i in 0 until n) { bufR[i] = buf[i]; dryR[i] = 0f }
                if (ch.voices.isNotEmpty()) { val it = ch.voices.iterator(); while (it.hasNext()) { val v = it.next()
                    if (stereo && v.pan != 0f) { val tmp = ch.vtmp; for (i in 0 until n) tmp[i] = 0f; val alive = v.render(tmp, n); val gl = min(1f, 1f - v.pan); val gr = min(1f, 1f + v.pan)
                        for (i in 0 until n) { dry[i] += tmp[i] * gl; dryR[i] += tmp[i] * gr }; if (!alive) it.remove() }
                    else if (stereo) { val tmp = ch.vtmp; for (i in 0 until n) tmp[i] = 0f; val alive = v.render(tmp, n); for (i in 0 until n) { dry[i] += tmp[i]; dryR[i] += tmp[i] }; if (!alive) it.remove() }
                    else if (!v.render(dry, n)) it.remove() } }
                for (i in 0 until n) buf[i] += dry[i]
                if (stereo) for (i in 0 until n) bufR[i] += dryR[i]
                if (t.micInput && (t.monitor || printCap)) for (i in 0 until n) { buf[i] += input[i]; if (stereo) bufR[i] += input[i] }
                if (captureThis && !printCap) { if (t.micInput) ch.capture(input, n) else if (stereo) { val tmp = ch.vtmp; for (i in 0 until n) tmp[i] = (dry[i] + dryR[i]) * 0.5f; ch.capture(tmp, n) } else ch.capture(dry, n) }
                if (t.rig != null && rigInputActive(ch, buf, n)) t.rig.process(buf, n)
                if (stereo) { var live = false; for (i in 0 until n) if (abs(bufR[i]) > 1e-6f) { live = true; break }; ch.silentBlocksR = if (live) 0 else ch.silentBlocksR + 1
                    if (ch.silentBlocksR * n < ENGINE_SR * 6) t.rigR!!.process(bufR, n) }
                if (printCap) { if (stereo) { val tmp = ch.vtmp; for (i in 0 until n) tmp[i] = (buf[i] + bufR[i]) * 0.5f; ch.capture(tmp, n) } else ch.capture(buf, n) }
                // printed clips already carry their tone: they join after the rig so it is not applied twice
                if (rolling && !captureThis) { val post = ch.post; for (i in 0 until n) post[i] = 0f
                    if (mixClips(t, pos, post, n, printedOnly = true)) for (i in 0 until n) { buf[i] += post[i]; if (stereo) bufR[i] += post[i] } }
                var g = t.gain; if (t.autoOn && t.automation.isNotEmpty()) g = Shapers.dbToLin(autoDb(t.automation, pos))
                val audible = !t.mute && (!s.anySolo || t.solo) && ses.previewTakeId == null && !(printCap && !t.monitor)
                var pk = 0f
                if (audible) { val l = g * min(1f, 1f - t.pan); val r = g * min(1f, 1f + t.pan)
                    if (stereo) for (i in 0 until n) { val x = buf[i]; val y = bufR[i]; out[2 * i] += x * l; out[2 * i + 1] += y * r; val a = max(abs(x), abs(y)) * g; if (a > pk) pk = a }
                    else for (i in 0 until n) { val x = buf[i]; out[2 * i] += x * l; out[2 * i + 1] += x * r; val a = abs(x * g); if (a > pk) pk = a } }
                ch.peak = max(pk, ch.peak * 0.85f); trackPeaks[t.id] = ch.peak
            }
        }
        // jam room bus
        run {
            val buf = roomCh.buf; val dry = roomCh.dry; for (i in 0 until n) { buf[i] = 0f; dry[i] = 0f }
            if (roomCh.voices.isNotEmpty()) { val it = roomCh.voices.iterator(); while (it.hasNext()) if (!it.next().render(dry, n)) it.remove() }
            for (i in 0 until n) buf[i] = dry[i]
            if (rigInputActive(roomCh, buf, n)) roomRig?.process(buf, n)
            if (roomRecording) { roomCh.capture(buf, n); roomPos += n }
            var pk = 0f; for (i in 0 until n) { val x = buf[i] * roomGain; out[2 * i] += x; out[2 * i + 1] += x; pk = max(pk, abs(x)) }
            roomCh.peak = max(pk, roomCh.peak * 0.85f); trackPeaks["__room"] = roomCh.peak
        }
        // private preview (take Listen, upload rig audition, stems)
        previewAudio?.let { a ->
            var i = 0; while (i < n) { if (previewPos >= a.size) { if (previewLoop) previewPos = 0 else { previewAudio = null; break } }; prevBuf[i] = a[previewPos.toInt()] / 32768f; previewPos++; i++ }
            for (k in i until n) prevBuf[k] = 0f
            previewRig?.process(prevBuf, n)
            var pk = 0f; for (k in 0 until n) { val x = prevBuf[k] * previewGain; out[2 * k] += x; out[2 * k + 1] += x; pk = max(pk, abs(x)) }; trackPeaks["__preview"] = pk
        }
        // master
        var pl = 0f; var pr = 0f
        for (i in 0 until n) { var l = out[2 * i] * masterGain; var r = out[2 * i + 1] * masterGain
            if (abs(l) > 0.9f) l = sign(l) * (0.9f + 0.1f * tanh((abs(l) - 0.9f) * 10f)); if (abs(r) > 0.9f) r = sign(r) * (0.9f + 0.1f * tanh((abs(r) - 0.9f) * 10f))
            pl = max(pl, abs(l)); pr = max(pr, abs(r)); out[2 * i] = l + click[i]; out[2 * i + 1] = r + click[i] }
        mixPeakL = max(pl, mixPeakL * 0.85f); mixPeakR = max(pr, mixPeakR * 0.85f)
        if (p != null) { val wrapped = ses.advance(p, n); if (wrapped) onLoopWrap?.invoke() }
        lastBlockNanos = System.nanoTime() - t0
    }
    var onLoopWrap: (() -> Unit)? = null

    /** Mixes clips (pre-rig ones, or only [printedOnly] printed ones) into [buf]. Returns true if any clip overlapped the block. */
    private fun mixClips(t: TrackSnap, pos: Long, buf: FloatArray, n: Int, printedOnly: Boolean = false): Boolean {
        val end = pos + n; var any = false
        for (c in t.clips) {
            if (c.printed != printedOnly) continue
            if (c.end <= pos || c.start >= end) continue
            any = true
            val data = pool.get(c.audioId) ?: continue
            val g = Shapers.dbToLin(c.gainDb)
            val from = max(pos, c.start); val to = min(end, c.end)
            // short fades at clip edges avoid clicks
            for (tl in from until to) {
                val srcPos = c.offset + (tl - c.start) * c.rate.toDouble(); val ip = srcPos.toInt(); if (ip + 1 >= data.size || ip < 0) continue
                val fr = (srcPos - ip).toFloat(); var x = (data[ip] + (data[ip + 1] - data[ip]) * fr) / 32768f
                val edge = min(tl - c.start, c.end - tl); if (edge < 96) x *= edge / 96f
                buf[(tl - pos).toInt()] += x * g
            }
        }
        return any
    }
    private fun scheduleNotes(t: TrackSnap, ch: Channel, pos: Long, n: Int) {
        if (t.notes.isEmpty()) return
        val end = pos + n
        if (pos != ch.expectedPos || ch.noteCursor > t.notes.size) { var lo = 0; var hi = t.notes.size; while (lo < hi) { val m = (lo + hi) ushr 1; if (t.notes[m].start < pos) lo = m + 1 else hi = m }; ch.noteCursor = lo }
        ch.expectedPos = end
        while (ch.noteCursor < t.notes.size && t.notes[ch.noteCursor].start < end) { val nt = t.notes[ch.noteCursor]
            val vowel = t.vowels.getOrElse(ch.noteCursor) { 'a' }
            ch.held[nt.pitch]?.release(); startVoice(ch, t.voice, t.kit, nt.pitch, nt.velocity, t.brightness, vowel, ch.noteCursor % 2 == 0, t.voiceStyle)?.let { ch.held[nt.pitch] = it }
            ch.noteCursor++ }
        // note-offs for scheduled notes
        for (nt in t.notes) { val off = nt.start + nt.length; if (off in pos until end) ch.held[nt.pitch]?.let { if (!t.voice.startsWith("kit:")) { it.release(); ch.held.remove(nt.pitch) } } }
    }
    private fun autoDb(a: Array<AutoPoint>, pos: Long): Float { if (pos <= a[0].at) return a[0].db; for (i in 1 until a.size) if (pos < a[i].at) { val p0 = a[i - 1]; val p1 = a[i]; val t = (pos - p0.at).toFloat() / (p1.at - p0.at); return p0.db + (p1.db - p0.db) * t }; return a.last().db }

    fun allNotesOff() { for (c in channels.values) { c.voices.forEach { it.release() }; c.held.clear(); c.noteCursor = 0 }; roomCh.voices.forEach { it.release() } }
    fun resetCursors() { channels.values.forEach { it.noteCursor = 0 } }
    fun room() = roomCh
}
