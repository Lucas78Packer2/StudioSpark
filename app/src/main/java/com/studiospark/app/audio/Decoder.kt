package com.studiospark.app.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder

/** Decodes a user-chosen local audio file (content Uri) to interleaved float PCM via the platform codecs. */
object Decoder {
    class Decoded(val sr: Int, val ch: Int, val pcm: FloatArray) { val frames get() = pcm.size / ch }

    fun decode(ctx: Context, uri: Uri, maxSeconds: Int = 900, progress: (Float) -> Unit = {}): Decoded {
        // WAV fast path keeps full precision and avoids codec quirks
        ctx.contentResolver.openInputStream(uri)?.use { s -> val head = ByteArray(12); if (s.read(head) == 12 && String(head, 0, 4) == "RIFF" && String(head, 8, 4) == "WAVE") {
            val all = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }; val w = Wav.parse(all); return Decoded(w.sr, w.ch, w.samples) } }
        val ex = MediaExtractor(); ex.setDataSource(ctx, uri, null)
        val ti = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: error("No audio track in file")
        ex.selectTrack(ti); val fmt = ex.getTrackFormat(ti); val mime = fmt.getString(MediaFormat.KEY_MIME)!!
        var sr = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE); var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val durUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
        val codec = MediaCodec.createDecoderByType(mime); codec.configure(fmt, null, null, 0); codec.start()
        val out = FloatArrayBuilder(); val info = MediaCodec.BufferInfo(); var inDone = false; var outDone = false; var floatOut = false
        try {
            while (!outDone) {
                if (!inDone) { val ii = codec.dequeueInputBuffer(5000); if (ii >= 0) { val b = codec.getInputBuffer(ii)!!; val n = ex.readSampleData(b, 0)
                    if (n < 0) { codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true } else { codec.queueInputBuffer(ii, 0, n, ex.sampleTime, 0); if (durUs > 0) progress((ex.sampleTime.toFloat() / durUs).coerceIn(0f, 1f)); ex.advance() } } }
                val oi = codec.dequeueOutputBuffer(info, 5000)
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { val of = codec.outputFormat; sr = of.getInteger(MediaFormat.KEY_SAMPLE_RATE); ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    floatOut = of.containsKey(MediaFormat.KEY_PCM_ENCODING) && of.getInteger(MediaFormat.KEY_PCM_ENCODING) == android.media.AudioFormat.ENCODING_PCM_FLOAT }
                else if (oi >= 0) { val b = codec.getOutputBuffer(oi)!!.order(ByteOrder.LITTLE_ENDIAN); b.position(info.offset); b.limit(info.offset + info.size)
                    if (floatOut) { val fb = b.asFloatBuffer(); while (fb.hasRemaining()) out.add(fb.get()) } else { val sb = b.asShortBuffer(); while (sb.hasRemaining()) out.add(sb.get() / 32768f) }
                    codec.releaseOutputBuffer(oi, false); if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                    if (out.size > maxSeconds.toLong() * sr * ch) outDone = true }
            }
        } finally { runCatching { codec.stop() }; codec.release(); ex.release() }
        return Decoded(sr, ch, out.toArray())
    }

    fun toEngineMono(d: Decoded, engineSr: Int): ShortArray { val mono = if (d.ch == 1) d.pcm else FloatArray(d.frames) { f -> var s = 0f; for (c in 0 until d.ch) s += d.pcm[f * d.ch + c]; s / d.ch }
        return Wav.toShorts(Wav.resample(mono, d.sr, engineSr)) }
    fun toStereo44k(d: Decoded): FloatArray { val l = FloatArray(d.frames) { d.pcm[it * d.ch] }; val r = FloatArray(d.frames) { d.pcm[it * d.ch + if (d.ch > 1) 1 else 0] }
        val l2 = Wav.resample(l, d.sr, 44100); val r2 = Wav.resample(r, d.sr, 44100); return FloatArray(l2.size * 2) { if (it % 2 == 0) l2[it / 2] else r2[it / 2] } }
}

class FloatArrayBuilder { private var a = FloatArray(1 shl 16); var size = 0; private set
    fun add(v: Float) { if (size == a.size) a = a.copyOf(a.size * 2); a[size++] = v }
    fun toArray() = a.copyOf(size) }
