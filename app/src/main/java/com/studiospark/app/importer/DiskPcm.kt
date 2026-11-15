package com.studiospark.app.importer

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Disk-backed PCM for the Import split (audit fix: a 6-minute song no longer sits in the Java heap several times over).
 * Decoded audio is streamed straight to a 44.1 kHz stereo float file; the engine reads chunks from a memory map and
 * writes stems into memory-mapped PCM16 files that become the stem-library entry. Mapped pages are file-backed and
 * reclaimable by the OS, so heap use during a split is a few chunk buffers plus the model.
 */
object DiskPcm {
    const val SR = 44100

    /** Streaming linear stereo resampler. Pure; unit-tested. */
    class Resampler(private val from: Int, private val to: Int) {
        private val step = from.toDouble() / to; private var pos = 0.0; private var pl = 0f; private var pr = 0f; private var started = false
        /** Feeds one input frame; emits 0+ output frames. */
        fun push(l: Float, r: Float, emit: (Float, Float) -> Unit) {
            if (from == to) { emit(l, r); return }
            if (!started) { started = true; pl = l; pr = r; emit(l, r); pos = step; return }
            while (pos <= 1.0) { val t = pos.toFloat(); emit(pl + (l - pl) * t, pr + (r - pr) * t); pos += step }
            pos -= 1.0; pl = l; pr = r
        }
    }

    /** Writes interleaved stereo floats (little-endian) to [out]. Returns the frame count. */
    class StereoWriter(out: File) : AutoCloseable {
        private val os = BufferedOutputStream(FileOutputStream(out), 1 shl 16)
        private val bb = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        var frames = 0; private set
        fun frame(l: Float, r: Float) { bb.clear(); bb.putFloat(l).putFloat(r); os.write(bb.array(), 0, 8); frames++ }
        override fun close() = os.close()
    }

    /**
     * Decodes [uri] (MP3/WAV/FLAC/AAC via MediaExtractor + MediaCodec) to a 44.1 kHz stereo float file without holding
     * the song in memory. Mono files are duplicated to both channels. Stops at [maxSeconds]. Returns frames written.
     */
    fun decodeToFile(ctx: Context, uri: Uri, out: File, maxSeconds: Int, progress: (Float) -> Unit = {}): Int {
        val ex = MediaExtractor(); ex.setDataSource(ctx, uri, null)
        val ti = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: run { ex.release(); error("No audio track in file") }
        ex.selectTrack(ti); val fmt = ex.getTrackFormat(ti); val mime = fmt.getString(MediaFormat.KEY_MIME)!!
        var sr = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE); var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val durUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
        val codec = MediaCodec.createDecoderByType(mime); codec.configure(fmt, null, null, 0); codec.start()
        val info = MediaCodec.BufferInfo(); var inDone = false; var outDone = false; var floatOut = false
        val maxFrames = maxSeconds.toLong() * SR
        var rs = Resampler(sr, SR)
        StereoWriter(out).use { w ->
            val emit: (Float, Float) -> Unit = { a, b -> if (w.frames < maxFrames) w.frame(a, b) else outDone = true }
            fun sample(l: Float, r: Float) = rs.push(l, r, emit)
            try {
                while (!outDone) {
                    if (!inDone) { val ii = codec.dequeueInputBuffer(5000); if (ii >= 0) { val b = codec.getInputBuffer(ii)!!; val n = ex.readSampleData(b, 0)
                        if (n < 0) { codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                        else { codec.queueInputBuffer(ii, 0, n, ex.sampleTime, 0); if (durUs > 0) progress((ex.sampleTime.toFloat() / durUs).coerceIn(0f, 1f)); ex.advance() } } }
                    val oi = codec.dequeueOutputBuffer(info, 5000)
                    if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { val of = codec.outputFormat
                        val nsr = of.getInteger(MediaFormat.KEY_SAMPLE_RATE); ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        if (nsr != sr) { sr = nsr; rs = Resampler(sr, SR) }
                        floatOut = of.containsKey(MediaFormat.KEY_PCM_ENCODING) && of.getInteger(MediaFormat.KEY_PCM_ENCODING) == android.media.AudioFormat.ENCODING_PCM_FLOAT }
                    else if (oi >= 0) { val b = codec.getOutputBuffer(oi)!!.order(ByteOrder.LITTLE_ENDIAN); b.position(info.offset); b.limit(info.offset + info.size)
                        if (floatOut) { val fb = b.asFloatBuffer(); while (fb.remaining() >= ch) { val l = fb.get(); val r = if (ch > 1) fb.get() else l; repeat(maxOf(0, ch - 2)) { fb.get() }; if (l.isFinite() && r.isFinite()) sample(l, r) else sample(0f, 0f) } }
                        else { val sb = b.asShortBuffer(); while (sb.remaining() >= ch) { val l = sb.get() / 32768f; val r = if (ch > 1) sb.get() / 32768f else l; repeat(maxOf(0, ch - 2)) { sb.get() }; sample(l, r) } }
                        codec.releaseOutputBuffer(oi, false); if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true }
                }
            } finally { runCatching { codec.stop() }; codec.release(); ex.release() }
            return w.frames
        }
    }

    private fun map(f: File, mode: FileChannel.MapMode, bytes: Long): MappedByteBuffer = RandomAccessFile(f, if (mode == FileChannel.MapMode.READ_ONLY) "r" else "rw").use { raf ->
        if (mode != FileChannel.MapMode.READ_ONLY) raf.setLength(bytes); raf.channel.map(mode, 0, bytes) }.order(ByteOrder.LITTLE_ENDIAN) as MappedByteBuffer

    /** Memory-mapped view of a [StereoWriter] file. */
    class MappedStereoSource(f: File) : StereoSource {
        private val fb = map(f, FileChannel.MapMode.READ_ONLY, f.length()).asFloatBuffer()
        override val frames = (f.length() / 8).toInt()
        override fun read(startFrame: Int, frames: Int, dst: FloatArray) { java.util.Arrays.fill(dst, 0, frames * 2, 0f)
            val n = minOf(frames, this.frames - startFrame); if (n <= 0) return
            val d = fb.duplicate(); d.position(startFrame * 2); d.get(dst, 0, n * 2) }
    }

    /** Writes each stem as raw mono PCM16 LE (the stem library's format) into memory-mapped files in [dir]. */
    class MappedStemSink(dir: File, val names: List<String>, val frames: Int) : StemSink {
        val files = names.map { File(dir, "$it.pcm") }
        private val bufs = files.map { map(it, FileChannel.MapMode.READ_WRITE, frames * 2L).asShortBuffer() }
        override fun write(stem: Int, frame: Int, value: Float) { bufs[stem].put(frame, (value.coerceIn(-1f, 1f) * 32767f).toInt().toShort()) }
    }

    /** Reads up to [maxFrames] frames of a PCM16 stem file centred on the middle of the song (for tags/tempo/key). */
    fun excerpt(f: File, maxFrames: Int): ShortArray {
        val total = (f.length() / 2).toInt(); val n = minOf(total, maxFrames); val start = (total - n) / 2
        val out = ShortArray(n); RandomAccessFile(f, "r").use { raf -> val bb = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
            raf.channel.read(bb, start * 2L); bb.flip(); bb.asShortBuffer().get(out, 0, minOf(n, bb.remaining() / 2)) }
        return out
    }
}
