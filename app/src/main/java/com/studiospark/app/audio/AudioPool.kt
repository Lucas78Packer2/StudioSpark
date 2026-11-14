package com.studiospark.app.audio

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** All clip/take audio, mono PCM16 at the engine rate, persisted as raw files under the project's audio folder. */
class AudioPool(private val dir: File) {
    private val map = ConcurrentHashMap<String, ShortArray>()
    private val peaks = ConcurrentHashMap<String, FloatArray>()
    private val io = Executors.newSingleThreadExecutor { Thread(it, "audio-pool-io") }
    companion object { const val PEAK_BLOCK = 256 }
    init { dir.mkdirs() }

    fun get(id: String): ShortArray? = map[id] ?: load(id)
    fun put(id: String, data: ShortArray, persist: Boolean = true) { map[id] = data; peaks.remove(id); if (persist) io.execute { save(id, data) } }
    fun delete(id: String) { map.remove(id); peaks.remove(id); io.execute { File(dir, "$id.pcm").delete() } }
    fun has(id: String) = map.containsKey(id) || File(dir, "$id.pcm").exists()
    private fun save(id: String, d: ShortArray) { val b = ByteBuffer.allocate(d.size * 2).order(ByteOrder.LITTLE_ENDIAN); b.asShortBuffer().put(d); File(dir, "$id.pcm").writeBytes(b.array()) }
    private fun load(id: String): ShortArray? { val f = File(dir, "$id.pcm"); if (!f.exists()) return null
        val bytes = f.readBytes(); val s = ShortArray(bytes.size / 2); ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(s); map[id] = s; return s }

    /** Absolute peak per [PEAK_BLOCK] samples, for waveform drawing. */
    fun peaks(id: String): FloatArray? { peaks[id]?.let { return it }; val d = get(id) ?: return null
        val n = (d.size + PEAK_BLOCK - 1) / PEAK_BLOCK; val out = FloatArray(n)
        for (b in 0 until n) { var m = 0; val s = b * PEAK_BLOCK; val e = min(d.size, s + PEAK_BLOCK); for (i in s until e) m = max(m, abs(d[i].toInt())); out[b] = m / 32768f }
        peaks[id] = out; return out }
    fun flush() { io.submit {}.get() }
}
