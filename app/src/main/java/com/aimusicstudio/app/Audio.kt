package com.aimusicstudio.app

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaRecorder
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

const val SR = 44100

/** Decodes audio files to mono float PCM at 44.1 kHz (WAV fast path, MediaCodec for m4a/mp3/aiff etc.). */
object AudioDecoder {
    private val cache = HashMap<String, FloatArray>()

    fun durationOf(f: File): Double = runCatching { decode(f).size.toDouble() / SR }.getOrDefault(0.0)

    @Synchronized fun decode(f: File): FloatArray = cache.getOrPut(f.absolutePath + f.lastModified()) {
        runCatching { if (f.extension.equals("wav", true)) readWav(f) else decodeCodec(f) }.getOrDefault(FloatArray(0))
    }

    private fun readWav(f: File): FloatArray {
        val b = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        var ch = 1; var rate = SR; var bits = 16; var pos = 12
        while (pos + 8 <= b.limit()) {
            val id = String(ByteArray(4) { b.get(pos + it) }); val size = b.getInt(pos + 4)
            if (id == "fmt ") { ch = b.getShort(pos + 10).toInt(); rate = b.getInt(pos + 12); bits = b.getShort(pos + 22).toInt() }
            if (id == "data") {
                val bytesPer = bits / 8; val frames = min(size, b.limit() - pos - 8) / (bytesPer * ch)
                val out = FloatArray(frames)
                for (i in 0 until frames) {
                    var s = 0f
                    for (c in 0 until ch) {
                        val o = pos + 8 + (i * ch + c) * bytesPer
                        s += when (bits) { 8 -> ((b.get(o).toInt() and 0xff) - 128) / 128f; 32 -> b.getFloat(o); else -> b.getShort(o) / 32768f }
                    }
                    out[i] = s / ch
                }
                return resample(out, rate)
            }
            pos += 8 + size + (size and 1)
        }
        return FloatArray(0)
    }

    private fun decodeCodec(f: File): FloatArray {
        val ex = MediaExtractor(); ex.setDataSource(f.absolutePath)
        val ti = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("audio/") }
        ex.selectTrack(ti)
        val fmt = ex.getTrackFormat(ti)
        val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(fmt, null, null, 0); codec.start()
        val samples = ArrayList<Float>(); val info = MediaCodec.BufferInfo()
        var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT); var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var inDone = false; var outDone = false
        while (!outDone) {
            if (!inDone) {
                val ii = codec.dequeueInputBuffer(10_000)
                if (ii >= 0) {
                    val n = ex.readSampleData(codec.getInputBuffer(ii)!!, 0)
                    if (n < 0) { codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                    else { codec.queueInputBuffer(ii, 0, n, ex.sampleTime, 0); ex.advance() }
                }
            }
            val oi = codec.dequeueOutputBuffer(info, 10_000)
            if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                ch = codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT); rate = codec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else if (oi >= 0) {
                val sb = codec.getOutputBuffer(oi)!!.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                while (sb.remaining() >= ch) { var s = 0f; repeat(ch) { s += sb.get() / 32768f }; samples.add(s / ch) }
                codec.releaseOutputBuffer(oi, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
            }
        }
        codec.stop(); codec.release(); ex.release()
        return resample(samples.toFloatArray(), rate)
    }

    private fun resample(x: FloatArray, rate: Int): FloatArray {
        if (rate == SR || rate <= 0) return x
        val n = (x.size.toLong() * SR / rate).toInt(); val r = rate.toDouble() / SR
        return FloatArray(n) { i -> val p = i * r; val a = p.toInt(); val fr = (p - a).toFloat()
            x[min(a, x.size - 1)] * (1 - fr) + x[min(a + 1, x.size - 1)] * fr }
    }
}

/** Port of InstrumentSoundGenerator: synthesized drum pads and synth voices. */
object InstrumentSoundGenerator {
    private val drumCache = HashMap<DrumPad, FloatArray>()

    fun drum(pad: DrumPad): FloatArray = drumCache.getOrPut(pad) {
        val rnd = Random(pad.ordinal)
        fun gen(dur: Double, f: (Double) -> Double) = FloatArray((dur * SR).toInt()) { f(it.toDouble() / SR).toFloat() }
        fun tom(f0: Double) = gen(0.45) { t -> sin(2 * PI * (f0 + f0 * exp(-20 * t)) * t) * exp(-7 * t) * 0.9 }
        when (pad) {
            DrumPad.KICK -> gen(0.5) { t -> sin(2 * PI * (48 + 110 * exp(-35 * t)) * t) * exp(-7 * t) }
            DrumPad.SNARE -> gen(0.3) { t -> (rnd.nextDouble(-1.0, 1.0) * 0.7 + 0.4 * sin(2 * PI * 185 * t)) * exp(-16 * t) }
            DrumPad.LOW_TOM -> tom(90.0); DrumPad.MID_TOM -> tom(130.0); DrumPad.HIGH_TOM -> tom(180.0)
            DrumPad.RIMSHOT -> gen(0.08) { t -> (sin(2 * PI * 1700 * t) + rnd.nextDouble(-0.5, 0.5)) * exp(-60 * t) * 0.7 }
            DrumPad.CLAP -> gen(0.3) { t -> val burst = if (t < 0.03) exp(-((t * 1000) % 10) / 3) else exp(-14 * (t - 0.03)); rnd.nextDouble(-1.0, 1.0) * burst * 0.7 }
            DrumPad.COWBELL -> gen(0.35) { t -> (sq(545 * t) + sq(815 * t)) * 0.3 * exp(-9 * t) }
            DrumPad.CLOSED_HAT -> gen(0.06) { t -> rnd.nextDouble(-1.0, 1.0) * exp(-70 * t) * 0.5 }
            DrumPad.OPEN_HAT -> gen(0.4) { t -> rnd.nextDouble(-1.0, 1.0) * exp(-8 * t) * 0.4 }
            DrumPad.CYMBAL, DrumPad.CRASH -> gen(1.2) { t -> rnd.nextDouble(-1.0, 1.0) * exp(-3 * t) * 0.35 }
            DrumPad.RIDE -> gen(1.5) { t -> (rnd.nextDouble(-1.0, 1.0) * 0.25 + sin(2 * PI * 1435 * t) * 0.3 * exp(-6 * t)) * exp(-1.5 * t) * 0.4 }
        }
    }

    private fun sq(phase: Double) = if ((phase % 1.0) < 0.5) 1.0 else -1.0

    fun osc(w: SynthWaveform, phase: Double): Double { val p = phase % 1.0
        return when (w) { SynthWaveform.SINE -> sin(2 * PI * p); SynthWaveform.TRIANGLE -> 4 * abs(p - 0.5) - 1
            SynthWaveform.SQUARE -> if (p < 0.5) 1.0 else -1.0; SynthWaveform.PULSE -> if (p < 0.25) 1.0 else -0.33; else -> 2 * p - 1 } }

    fun freq(pitch: Int) = 440.0 * 2.0.pow((pitch - 69) / 12.0)

    /** One synth note with a simple ADSR. acid=true adds a TB-303 style decaying low-pass sweep. */
    fun note(w: SynthWaveform, pitch: Int, dur: Double, vel: Float = 0.8f, acid: Boolean = false): FloatArray {
        if (w.ordinal >= SynthWaveform.SUPERSAW.ordinal) return voice(w, pitch, dur, vel)
        val rel = 0.08; val n = ((dur + rel) * SR).toInt(); val f = freq(pitch); var lp = 0.0
        return FloatArray(n) { i -> val t = i.toDouble() / SR
            val env = min(1.0, t / 0.005) * (if (t > dur) max(0.0, 1 - (t - dur) / rel) else 1.0) * (0.7 + 0.3 * exp(-4 * t))
            var s = osc(w, f * t)
            if (acid) { val a = 0.02 + 0.5 * exp(-12 * t); lp += a * (s - lp); s = lp * 1.6 }
            (s * env * vel * 0.35).toFloat() }
    }
}

/** Extended voices: additive / FM / detuned / filtered models, each with its own envelope. */
private fun voice(w: SynthWaveform, pitch: Int, dur: Double, vel: Float): FloatArray {
    val f = InstrumentSoundGenerator.freq(pitch)
    val (atk, rel) = when (w) { SynthWaveform.STRINGS, SynthWaveform.CHOIR -> 0.25 to 0.5; SynthWaveform.FLUTE -> 0.06 to 0.15; SynthWaveform.BRASS -> 0.04 to 0.15
        SynthWaveform.BELL -> 0.002 to 1.2; SynthWaveform.EPIANO -> 0.003 to 0.4; SynthWaveform.PLUCK -> 0.002 to 0.2; else -> 0.005 to 0.1 }
    val n = ((dur + rel) * SR).toInt(); var lp = 0.0; val rnd = kotlin.random.Random(pitch)
    return FloatArray(n) { i -> val t = i.toDouble() / SR
        val env = min(1.0, t / atk) * (if (t > dur) max(0.0, 1 - (t - dur) / rel) else 1.0)
        fun s(m: Double) = sin(2 * PI * f * m * t)
        fun saw(m: Double) = 2 * ((f * m * t) % 1.0) - 1
        val x = when (w) {
            SynthWaveform.SUPERSAW -> (saw(1.0) + saw(1.006) + saw(0.994) + saw(1.012) + saw(0.988)) / 3.0
            SynthWaveform.SUB_BASS -> s(1.0) + 0.15 * s(2.0)
            SynthWaveform.REESE -> { val r = (saw(1.0) + saw(1.009)) * 0.6; lp += 0.08 * (r - lp); lp * 1.6 }
            SynthWaveform.EPIANO -> sin(2 * PI * f * t + 1.6 * exp(-6 * t) * s(1.0)) * exp(-1.6 * t) + 0.2 * s(4.0) * exp(-9 * t)
            SynthWaveform.ORGAN -> (s(1.0) + 0.6 * s(2.0) + 0.4 * s(3.0) + 0.25 * s(4.0) + 0.15 * s(8.0)) / 2.0 * (1 + 0.05 * sin(2 * PI * 6.5 * t))
            SynthWaveform.STRINGS, SynthWaveform.CHOIR -> { val vib = 1 + 0.004 * sin(2 * PI * 5.2 * t)
                val r = if (w == SynthWaveform.CHOIR) (sin(2 * PI * f * vib * t) + 0.5 * sin(2 * PI * f * 2 * vib * t) + 0.3 * sin(2 * PI * f * 3.01 * t) + 0.08 * (rnd.nextDouble() - 0.5)) / 1.6
                    else (saw(vib) + saw(vib * 1.004) + saw(vib * 0.996)) / 3.0
                lp += 0.12 * (r - lp); lp * 1.4 }
            SynthWaveform.BRASS -> { val r = saw(1.0); val cut = 0.05 + 0.4 * min(1.0, t / 0.08) * exp(-2 * t); lp += cut * (r - lp); lp * 1.5 }
            SynthWaveform.PLUCK -> { val r = saw(1.0); lp += (0.02 + 0.6 * exp(-25 * t)) * (r - lp); lp * exp(-5 * t) * 1.8 }
            SynthWaveform.BELL -> sin(2 * PI * f * t + 2.5 * exp(-3 * t) * s(3.5)) * exp(-2.5 * t)
            SynthWaveform.FLUTE -> (s(1.0) * (1 + 0.006 * sin(2 * PI * 5 * t)) + 0.15 * s(2.0) + 0.06 * (rnd.nextDouble() - 0.5)) * 0.9
            else -> s(1.0) }
        (x * env * vel * 0.35).toFloat() }
}

data class Instrument(val name: String, val group: String, val pad: DrumPad? = null, val waveform: SynthWaveform? = null, val acid: Boolean = false)

val InstrumentLibrary: List<Instrument> =
    DrumPad.values().map { Instrument(it.label, "Drum Kit", pad = it) } +
    SynthWaveform.values().map { Instrument(if (it.ordinal < 4) "${it.label} Synth" else it.label, if (it.ordinal < 4) "Synth" else "Keys / Pads / Bass", waveform = it) } +
    Instrument("Acid Bass (303-style)", "Synth", waveform = SynthWaveform.SAW, acid = true)

/** Port of MixerEngine + TrackCompressorUnit + MixBounceRenderer: offline renders the session to stereo floats. */
object MixRenderer {
    fun audible(t: Track, all: List<Track>): Boolean = !t.isMuted && (all.none { it.isSoloed } || t.isSoloed)

    fun render(s: StudioSession, stemsDir: File): Pair<FloatArray, FloatArray> {
        val n = (s.length * SR).toInt() + SR
        val l = FloatArray(n); val r = FloatArray(n); val rev = FloatArray(n); val dly = FloatArray(n)
        for (t in s.tracks) {
            if (!audible(t, s.tracks)) continue
            val buf = FloatArray(n)
            for (c in t.clips) {
                val src = AudioDecoder.decode(File(stemsDir, c.fileName)); val o = (c.startTime * SR).toInt(); val so = (c.sourceStartTime * SR).toInt()
                if (src.isEmpty()) continue
                for (i in 0 until (c.duration * SR).toInt()) { var si = so + i; val di = o + i; if (di >= n) break; if (si >= src.size) si = so + (i % kotlin.math.max(1, src.size - so)); if (si >= src.size) break; buf[di] += src[si] }
            }
            for (nt in t.notes) { val v = if (t.kind == TrackKind.GUITAR) Fx.guitarNote(t.guitar, nt.pitch, nt.duration, nt.velocity) else InstrumentSoundGenerator.note(t.waveform, nt.pitch, nt.duration, nt.velocity); val o = (nt.startTime * SR).toInt()
                for (i in v.indices) { if (o + i >= n) break; buf[o + i] += v[i] } }
            // Insert rack: pedals -> amp -> EQ -> comp
            t.pedals.forEach { Fx.pedal(buf, it, s.tempoBPM) }; Fx.amp(buf, t)
            applyEQ(buf, t.eq); if (t.compressor.isEnabled) applyCompressor(buf, t.compressor)
            val blk = 512
            for (i0 in 0 until n step blk) { val tt = i0.toDouble() / SR
                val vol = Fx.autoValue(t.volAuto, tt, t.volume); val pan = Fx.autoValue(t.panAuto, tt, t.pan)
                val gl = vol * min(1f, 1 - pan); val gr = vol * min(1f, 1 + pan)
                for (i in i0 until min(n, i0 + blk)) { l[i] += buf[i] * gl; r[i] += buf[i] * gr; rev[i] += buf[i] * vol * t.reverbSend; dly[i] += buf[i] * vol * t.delaySend } }
        }
        if (s.drumPattern.isEnabled) {
            var time = 0.0; var step = 0
            while (time < s.length) {
                val st = step % 16
                for ((pad, row) in s.drumPattern.steps) if (row[st]) {
                    // Accent groove: downbeats strongest, off-8ths medium, 16ths lighter; seeded per hit so loops breathe but stay repeatable.
                    val vel = when { st % 4 == 0 -> 0.95f; st % 2 == 0 -> 0.82f; else -> 0.7f } * (if (pad == DrumPad.CLOSED_HAT || pad == DrumPad.RIDE) 0.92f else 1f)
                    var v = Fx.drumHit(s.kit, pad, vel, step); val o = (time * SR).toInt()
                    // Hi-hat choke: a closed hat later in the loop cuts a ringing open hat (short 6 ms fade, like the pedal closing).
                    if (pad == DrumPad.OPEN_HAT) { val cr = s.drumPattern.steps[DrumPad.CLOSED_HAT]
                        if (cr != null) { var k = 1; while (k < 16 && !cr[(st + k) % 16]) k++
                            if (k < 16) { val cut = (k * s.stepSeconds * SR).toInt(); if (cut < v.size) { val fade = (0.006 * SR).toInt(); v = v.copyOf(cut + fade); for (i in 0 until fade) v[cut + i] *= 1f - i.toFloat() / fade } } } }
                    for (i in v.indices) { if (o + i >= n) break; l[o + i] += v[i] * 0.7f; r[o + i] += v[i] * 0.7f } }
                time += s.stepSeconds; step++
            }
        }
        // Return buses
        if (rev.any { it != 0f }) { Fx.reverb(rev, 1f); for (i in 0 until n) { l[i] += rev[i] * s.reverbReturn; r[i] += rev[i] * s.reverbReturn } }
        if (dly.any { it != 0f }) { val d = (60.0 / s.tempoBPM * s.delayTimeBeats * SR).toInt(); val y = dly.copyOf(); for (i in d until n) y[i] += y[i - d] * 0.45f
            for (i in 0 until n) { val w = (y[i] - dly[i]) * s.delayReturn; l[i] += w; r[i] += w } }
        for (i in 0 until n) { l[i] = softClip(l[i] * s.masterVolume); r[i] = softClip(r[i] * s.masterVolume) }
        return l to r
    }

    private fun softClip(x: Float) = if (abs(x) < 0.9f) x else (x / (1 + abs(x) - 0.9f)).coerceIn(-1f, 1f)

    /** 3-band split via one-pole crossovers at ~250 Hz and ~4 kHz, then per-band gain. */
    fun applyEQ(x: FloatArray, eq: TrackEQSettings) {
        if (eq.lowGain == 0f && eq.midGain == 0f && eq.highGain == 0f) return
        val gL = db(eq.lowGain); val gM = db(eq.midGain); val gH = db(eq.highGain)
        val aL = (1 - exp(-2 * PI * 250 / SR)).toFloat(); val aH = (1 - exp(-2 * PI * 4000 / SR)).toFloat()
        var lo = 0f; var lp4 = 0f
        for (i in x.indices) { val s = x[i]; lo += aL * (s - lo); lp4 += aH * (s - lp4)
            val mid = lp4 - lo; val hi = s - lp4; x[i] = lo * gL + mid * gM + hi * gH }
    }

    fun applyCompressor(x: FloatArray, c: TrackCompressorSettings) {
        val att = exp(-1.0 / (c.attackMs / 1000.0 * SR)).toFloat(); val rel = exp(-1.0 / (c.releaseMs / 1000.0 * SR)).toFloat()
        var env = 0f; val mk = db(c.makeupGain)
        for (i in x.indices) { val a = abs(x[i]); env = if (a > env) att * env + (1 - att) * a else rel * env + (1 - rel) * a
            val lvl = 20 * log10(max(env, 1e-6f)); val over = lvl - c.threshold
            val gainDb = if (over > 0) -over * (1 - 1 / c.ratio) else 0f
            x[i] = x[i] * db(gainDb) * mk }
    }

    private fun db(d: Float) = 10f.pow(d / 20f)

    /** MixExportStorage: write stereo 16-bit WAV. */
    fun writeWav(f: File, l: FloatArray, r: FloatArray) = writeWavInterleaved(f, FloatArray(l.size * 2) { if (it % 2 == 0) l[it / 2] else r[it / 2] }, 2)

    fun writeWavInterleaved(f: File, x: FloatArray, ch: Int) {
        val data = ByteBuffer.allocate(x.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        x.forEach { data.putShort((it.coerceIn(-1f, 1f) * 32767).toInt().toShort()) }
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt(36 + x.size * 2).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(ch.toShort())
            .putInt(SR).putInt(SR * ch * 2).putShort((ch * 2).toShort()).putShort(16).put("data".toByteArray()).putInt(x.size * 2)
        RandomAccessFile(f, "rw").use { it.setLength(0); it.write(h.array()); it.write(data.array()) }
    }
}

/** Plays rendered mixes (transport) and one-shot previews; reports level + playhead. */
class AudioPlayer {
    @Volatile var playhead = 0.0; @Volatile var level = 0f; @Volatile var isPlaying = false
    private var thread: Thread? = null
    @Volatile private var stopFlag = false

    private fun newTrack(ch: Int, mode: Int, bytes: Int) = AudioTrack.Builder()
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
        .setAudioFormat(AudioFormat.Builder().setSampleRate(SR).setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setChannelMask(if (ch == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO).build())
        .setTransferMode(mode).setBufferSizeInBytes(bytes).build()

    // Live voices: ONE always-running stream track mixes every tap (pads, keys, strings, previews).
    // v1.2.5 built a new MODE_STATIC float AudioTrack per tap; many devices reject/never start those
    // (static float + exhausted track slots), so taps were silent. A streaming mixer is reliable.
    private class Voice(val x: FloatArray, val g: Float, val tag: String?) { var pos = 0; @Volatile var fade = -1 }
    private val voices = java.util.concurrent.ConcurrentLinkedQueue<Voice>()
    @Volatile private var liveThread: Thread? = null
    private fun ensureLive() { if (liveThread?.isAlive == true) return; synchronized(this) { if (liveThread?.isAlive == true) return
        liveThread = Thread {
            val block = 512
            val minB = AudioTrack.getMinBufferSize(SR, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
            val at = newTrack(1, AudioTrack.MODE_STREAM, max(minB, block * 4 * 2)); at.play()
            val buf = FloatArray(block); var idle = 0
            while (true) {
                java.util.Arrays.fill(buf, 0f); var peak = 0f
                val it = voices.iterator()
                while (it.hasNext()) { val v = it.next(); val n = min(block, v.x.size - v.pos); val fl = CHOKE_FADE
                    for (k in 0 until n) { var g = v.g; if (v.fade >= 0) { g *= max(0f, 1f - v.fade.toFloat() / fl); v.fade++ }; buf[k] += v.x[v.pos + k] * g }
                    v.pos += n; if (v.pos >= v.x.size || v.fade >= fl) it.remove() }
                for (k in 0 until block) { val y = buf[k].coerceIn(-1f, 1f); buf[k] = y; peak = max(peak, abs(y)) }
                if (peak > 0f) { level = peak; idle = 0 } else idle++
                at.write(buf, 0, block, AudioTrack.WRITE_BLOCKING)
                if (idle > SR * 20 / block && voices.isEmpty()) break   // release after 20 s silence
            }
            at.stop(); at.release(); level = 0f
        }.also { it.priority = Thread.MAX_PRIORITY; it.start() } } }

    /** Fire-and-forget trigger for pads, synth keys, strings and sample previews — sounds with transport stopped or playing. */
    fun oneShot(x: FloatArray, gain: Float = 1f, tag: String? = null, choke: String? = null) { if (x.isEmpty()) return
        if (choke != null) voices.forEach { if (it.tag == choke && it.fade < 0) it.fade = 0 }
        voices.add(Voice(x, gain, tag)); ensureLive() }
    private val CHOKE_FADE = (0.008 * SR).toInt()
    /** Private review of a recorded take: stops other preview voices first. */
    fun audition(x: FloatArray, gain: Float = 1f) { voices.clear(); oneShot(x, gain) }
    fun stopPreview() = voices.clear()

    @Volatile private var curL = FloatArray(0); @Volatile private var curR = FloatArray(0)
    @Volatile private var curLoop: ClosedFloatingPointRange<Double>? = null
    /** Hot-swap the rendered mix while playing so pads/keys/takes recorded during playback are heard on the next pass. */
    fun swap(l: FloatArray, r: FloatArray, loop: ClosedFloatingPointRange<Double>?) { curL = l; curR = r; curLoop = loop }

    fun play(l0: FloatArray, r0: FloatArray, from: Double, loop0: ClosedFloatingPointRange<Double>?, onEnd: () -> Unit) {
        stop(); stopFlag = false; isPlaying = true; curL = l0; curR = r0; curLoop = loop0
        thread = Thread {
            val block = 2048; val at = newTrack(2, AudioTrack.MODE_STREAM, block * 8 * 2); at.play()
            var i = (from * SR).toInt().coerceIn(0, l0.size)
            val buf = FloatArray(block * 2)
            while (!stopFlag) {
                val l = curL; val r = curR; val loop = curLoop
                if (loop != null && i < (loop.start * SR).toInt() - SR || loop != null && i > (loop.endInclusive * SR).toInt()) i = (loop.start * SR).toInt()
                val end = loop?.let { min((it.endInclusive * SR).toInt(), l.size) } ?: l.size
                if (i >= end) { if (loop != null) i = (loop.start * SR).toInt() else break }
                val cnt = min(block, end - i); if (cnt <= 0) { if (loop == null) break; i = (loop.start * SR).toInt(); continue }; var peak = 0f; var sum = 0f
                for (k in 0 until cnt) { buf[2 * k] = l[i + k]; buf[2 * k + 1] = r[i + k]; val a = abs(l[i + k]) + abs(r[i + k]); peak = max(peak, a / 2); sum += a * a / 4 }
                at.write(buf, 0, cnt * 2, AudioTrack.WRITE_BLOCKING)
                i += cnt; playhead = i.toDouble() / SR; level = max(peak * 0.6f, sqrt(sum / max(cnt, 1)) * 1.4f).coerceAtMost(1f)
            }
            at.stop(); at.release(); isPlaying = false; level = 0f; onEnd()
        }.also { it.start() }
    }

    fun stop() { stopFlag = true; thread?.join(500); thread = null; isPlaying = false }
}

/** Port of InstrumentTakeRecorder: mono 44.1 kHz mic capture into a WAV stem. */
class TakeRecorder {
    @Volatile var isRecording = false; @Volatile var level = 0f
    private var thread: Thread? = null

    @SuppressLint("MissingPermission")
    @Volatile var monitor = true
    fun start(out: File, onDone: (File) -> Unit) {
        if (isRecording) return
        isRecording = true
        thread = Thread {
            val min = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = AudioRecord(MediaRecorder.AudioSource.MIC, SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(min, 8192))
            val all = ArrayList<Float>(); val sb = ShortArray(2048)
            // Live monitoring: mic -> speaker/headphones so the singer hears themselves (use headphones to avoid feedback).
            val mon = runCatching { AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(SR).setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(8192 * 4).build().also { it.play() } }.getOrNull()
            val fb = FloatArray(2048)
            rec.startRecording()
            while (isRecording) { val n = rec.read(sb, 0, sb.size); var p = 0f
                for (k in 0 until max(n, 0)) { val v = sb[k] / 32768f; all.add(v); fb[k] = v; p = max(p, abs(v)) }; level = p
                if (monitor && n > 0) mon?.write(fb, 0, n, AudioTrack.WRITE_NON_BLOCKING) }
            rec.stop(); rec.release(); level = 0f; runCatching { mon?.stop(); mon?.release() }
            MixRenderer.writeWavInterleaved(out, all.toFloatArray(), 1); onDone(out)
        }.also { it.start() }
    }

    fun stop() { isRecording = false; thread?.join(2000); thread = null }
}
