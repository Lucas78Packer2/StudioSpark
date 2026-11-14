package com.studiospark.app.audio

import android.content.res.AssetManager
import com.studiospark.app.core.ENGINE_SR
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.*

/** Mono PCM16 sample resampled to the engine rate. */
class Sample(val data: ShortArray, val rootMidi: Int = 60)

object Wav {
    data class Pcm(val sr: Int, val ch: Int, val frames: Int, val samples: FloatArray) // interleaved
    fun parse(bytes: ByteArray): Pcm {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WAVE") { "Not WAV" }
        var pos = 12; var sr = 44100; var ch = 1; var bits = 16; var fmt = 1
        while (pos + 8 <= bytes.size) { val id = String(bytes, pos, 4); val len = b.getInt(pos + 4); val body = pos + 8
            if (id == "fmt ") { fmt = b.getShort(body).toInt(); ch = b.getShort(body + 2).toInt(); sr = b.getInt(body + 4); bits = b.getShort(body + 14).toInt() }
            if (id == "data") { val n = min(len, bytes.size - body); val bps = bits / 8; val frames = n / (bps * ch); val out = FloatArray(frames * ch)
                for (i in 0 until frames * ch) { val o = body + i * bps; out[i] = when { fmt == 3 && bits == 32 -> b.getFloat(o); bits == 16 -> b.getShort(o) / 32768f; bits == 24 -> ((bytes[o].toInt() and 255) or ((bytes[o + 1].toInt() and 255) shl 8) or (bytes[o + 2].toInt() shl 16)) / 8388608f; bits == 8 -> ((bytes[o].toInt() and 255) - 128) / 128f; else -> b.getInt(o) / 2147483648f } }
                return Pcm(sr, ch, frames, out) }
            pos = body + len + (len and 1) }
        error("No data chunk")
    }
    fun toMono(p: Pcm): FloatArray = if (p.ch == 1) p.samples else FloatArray(p.frames) { f -> var s = 0f; for (c in 0 until p.ch) s += p.samples[f * p.ch + c]; s / p.ch }
    /** Windowed-sinc-lite (cubic Hermite) resampler. */
    fun resample(x: FloatArray, from: Int, to: Int): FloatArray {
        if (from == to || x.isEmpty()) return x
        val ratio = from.toDouble() / to; val n = (x.size / ratio).toInt(); val out = FloatArray(n)
        for (i in 0 until n) { val pos = i * ratio; val k = pos.toInt(); val t = (pos - k).toFloat()
            val xm1 = x[max(k - 1, 0)]; val x0 = x[k]; val x1 = x[min(k + 1, x.size - 1)]; val x2 = x[min(k + 2, x.size - 1)]
            val c1 = 0.5f * (x1 - xm1); val c2 = xm1 - 2.5f * x0 + 2f * x1 - 0.5f * x2; val c3 = 0.5f * (x2 - xm1) + 1.5f * (x0 - x1)
            out[i] = ((c3 * t + c2) * t + c1) * t + x0 }
        return out
    }
    fun toShorts(x: FloatArray) = ShortArray(x.size) { (x[it].coerceIn(-1f, 1f) * 32767f).roundToInt().toShort() }
    fun encode(mono: ShortArray, sr: Int = ENGINE_SR): ByteArray { val b = ByteBuffer.allocate(44 + mono.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()); b.putInt(36 + mono.size * 2); b.put("WAVEfmt ".toByteArray()); b.putInt(16); b.putShort(1); b.putShort(1); b.putInt(sr); b.putInt(sr * 2); b.putShort(2); b.putShort(16)
        b.put("data".toByteArray()); b.putInt(mono.size * 2); mono.forEach { b.putShort(it) }; return b.array() }
}

/** Lazy, thread-safe sample bank over the shipped original/CC0 asset folders. */
class SampleBank(private val assets: AssetManager) {
    private val cache = ConcurrentHashMap<String, Sample>()
    private val groups = ConcurrentHashMap<String, Boolean>()
    private val loader = Executors.newSingleThreadExecutor { r -> Thread(r, "sample-loader").apply { priority = Thread.NORM_PRIORITY - 1 } }
    @Volatile var loadingGroup: String? = null; private set

    fun get(path: String): Sample? = cache[path]
    private fun load(path: String, root: Int) { if (cache.containsKey(path)) return
        runCatching { val pcm = assets.open(path).use { Wav.parse(it.readBytes()) }; cache[path] = Sample(Wav.toShorts(Wav.resample(Wav.toMono(pcm), pcm.sr, ENGINE_SR)), root) } }

    /** Ensure a voice group is resident; loads on a background thread. */
    fun ensure(group: String, done: (() -> Unit)? = null) {
        if (groups.putIfAbsent(group, true) != null) { done?.invoke(); return }
        loader.execute { loadingGroup = group; runCatching { files(group).forEach { (path, root) -> load(path, root) }; index(group) }; loadingGroup = null; done?.invoke() }
    }
    fun ensureBlocking(group: String) { files(group).forEach { (p, r) -> load(p, r) }; index(group); groups[group] = true }
    private val groupIndex = ConcurrentHashMap<String, List<Pair<String, Sample>>>()
    private fun index(group: String) { groupIndex[group] = cache.entries.filter { belongs(group, it.key) }.map { it.key to it.value }.sortedBy { it.first } }
    fun ready(group: String) = groupIndex.containsKey(group) || (group == "drums" && cache.isNotEmpty())

    fun files(group: String): List<Pair<String, Int>> = when {
        group == "drums" -> assets.list("original_drums")!!.filter { it.endsWith(".wav") }.map { "original_drums/$it" to 60 }
        group == "guitar" || group == "bass" -> assets.list("strings")!!.filter { it.startsWith("$group-") }.map { "strings/$it" to it.split('-')[1].toInt() }
        group.startsWith("keys_") -> { val lib = group.removePrefix("keys_"); assets.list("keys")!!.filter { it.startsWith("rendered-$lib-") }.map { "keys/$it" to it.removeSuffix(".wav").substringAfterLast('-').toInt() } }
        group == "textures" -> assets.list("textures")!!.filter { it.endsWith(".wav") }.map { "textures/$it" to 60 }
        else -> assets.list("pitched")!!.filter { it.startsWith("$group-") }.map { "pitched/$it" to it.split('-')[it.split('-').size - 2].toInt() }
    }

    /** Pick a sample for a pitched voice: nearest root, velocity layer, round-robin. Returns sample and playback rate. */
    fun pitched(group: String, midi: Int, velocity: Int, rr: Int): Pair<Sample, Double>? {
        val candidates = groupIndex[group] ?: return null
        if (candidates.isEmpty()) return null
        var nearestRoot = candidates[0].second.rootMidi; var bestD = Double.MAX_VALUE
        for (c in candidates) { val d = abs(c.second.rootMidi - midi) + if (c.second.rootMidi > midi) 0.5 else 0.0; if (d < bestD) { bestD = d; nearestRoot = c.second.rootMidi } }
        val atRoot = candidates.filter { it.second.rootMidi == nearestRoot }
        val pick = when (group) {
            "guitar", "bass" -> { val vl = if (velocity > 80) 1 else 0; atRoot.firstOrNull { it.first.endsWith("-$nearestRoot-$vl-${rr and 1}.wav") } ?: atRoot.first() }
            else -> if (group.startsWith("keys_")) atRoot.first() else { val layer = when { velocity < 48 -> 1; velocity < 96 -> 2; else -> 3 }; atRoot.firstOrNull { it.first.endsWith("-v$layer.wav") } ?: atRoot.first() }
        }
        return pick.second to 2.0.pow((midi - nearestRoot) / 12.0)
    }
    private fun belongs(group: String, key: String) = when {
        group == "guitar" || group == "bass" -> key.startsWith("strings/$group-")
        group.startsWith("keys_") -> key.startsWith("keys/rendered-${group.removePrefix("keys_")}-")
        else -> key.startsWith("pitched/$group-")
    }
    fun drum(key: String, velocity: Int, rr: Int): Sample? {
        val layer = (((velocity.coerceIn(1, 127) - 1) * 5) / 127 + 1).coerceIn(1, 5)
        val alt = ((layer - 1 + (rr % 2)) .coerceIn(0, 4)) + 1
        return cache["original_drums/$key-$alt.wav"] ?: cache["original_drums/$key-$layer.wav"] ?: cache["original_drums/$key-3.wav"]
    }
}
