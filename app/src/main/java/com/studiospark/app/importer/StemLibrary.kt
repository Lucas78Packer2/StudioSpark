package com.studiospark.app.importer

import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** What the split pass learned about a file, stored beside its stems. */
data class StemMeta(
    val hash: String, val sourceName: String, val modelId: String, val frames: Int, val sampleRate: Int,
    val tempoBpm: Float?, val key: String?, val otherTags: List<Pair<String, Float>>,
    val createdAt: Long = System.currentTimeMillis(), var lastUsed: Long = createdAt,
    val stems: List<String> = StemLibrary.STEMS,
) {
    fun toJson(): JSONObject = JSONObject().put("hash", hash).put("sourceName", sourceName).put("modelId", modelId).put("frames", frames).put("sampleRate", sampleRate)
        .put("tempoBpm", tempoBpm?.toDouble() ?: JSONObject.NULL).put("key", key ?: JSONObject.NULL)
        .put("otherTags", JSONArray().apply { otherTags.forEach { (n, s) -> put(JSONObject().put("name", n).put("score", s.toDouble())) } })
        .put("createdAt", createdAt).put("lastUsed", lastUsed).put("stems", JSONArray(stems))
    companion object {
        fun fromJson(o: JSONObject) = StemMeta(o.getString("hash"), o.getString("sourceName"), o.getString("modelId"), o.getInt("frames"), o.getInt("sampleRate"),
            if (o.isNull("tempoBpm")) null else o.getDouble("tempoBpm").toFloat(), if (o.isNull("key")) null else o.getString("key"),
            o.getJSONArray("otherTags").let { a -> List(a.length()) { a.getJSONObject(it).let { t -> t.getString("name") to t.getDouble("score").toFloat() } } },
            o.getLong("createdAt"), o.getLong("lastUsed"),
            o.optJSONArray("stems")?.let { a -> List(a.length()) { a.getString(it) } } ?: StemLibrary.STEMS)
    }
}

/**
 * Local stem library: one folder per (model, file SHA-256). Re-importing the same bytes is a cache hit and skips the split.
 * Plain java.io so it is unit-tested on the JVM. Least-recently-used entries are evicted past [capBytes].
 */
/** Folder per (model, SHA-256 of the file's bytes); the same file is never split twice by the same model. */
class StemLibrary(private val root: File, val modelId: String, private val capBytes: Long = 4L shl 30) {
    companion object {
        val STEMS = listOf("vocals", "drums", "bass", "other")
        fun sha256(input: InputStream): String { val md = MessageDigest.getInstance("SHA-256"); val buf = ByteArray(1 shl 16)
            input.use { while (true) { val n = it.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
            return md.digest().joinToString("") { "%02x".format(it) } }
    }
    private fun dir(hash: String) = File(root, "${modelId}_$hash")

    fun contains(hash: String) = meta(hash)?.let { m -> m.stems.all { File(dir(hash), "$it.pcm").exists() } } ?: false

    fun meta(hash: String): StemMeta? = File(dir(hash), "meta.json").takeIf { it.exists() }?.let { runCatching { StemMeta.fromJson(JSONObject(it.readText())) }.getOrNull() }

    /** Loads cached stems (mono PCM16) and marks the entry used. Null on a miss or a damaged entry. */
    fun load(hash: String): Pair<StemMeta, Map<String, ShortArray>>? {
        if (!contains(hash)) return null
        val m = meta(hash) ?: return null
        val stems = m.stems.associateWith { readPcm(File(dir(hash), "$it.pcm")) }
        if (stems.values.any { it.size != m.frames }) return null
        m.lastUsed = System.currentTimeMillis(); File(dir(hash), "meta.json").writeText(m.toJson().toString())
        return m to stems
    }

    fun store(meta: StemMeta, stems: Map<String, ShortArray>) {
        require(meta.stems.all { stems[it]?.size == meta.frames }) { "every stem must have ${meta.frames} frames" }
        val tmp = workDir(meta.hash)
        meta.stems.forEach { writePcm(File(tmp, "$it.pcm"), stems.getValue(it)) }
        commit(meta, tmp)
    }

    /** Fresh scratch folder for a split in progress; engines write stem .pcm files straight into it (no heap copy). */
    fun workDir(hash: String) = File(root, ".tmp_${modelId}_$hash").apply { deleteRecursively(); mkdirs() }

    /** Turns a finished [workDir] into a library entry (atomic rename), then evicts LRU entries past the cap. */
    fun commit(meta: StemMeta, work: File) {
        require(meta.stems.all { File(work, "$it.pcm").length() == meta.frames * 2L }) { "every stem must have ${meta.frames} frames" }
        File(work, "meta.json").writeText(meta.toJson().toString())
        val d = dir(meta.hash); d.deleteRecursively(); check(work.renameTo(d)) { "could not commit stem cache entry" }
        evict(keep = meta.hash)
    }

    fun entries(): List<StemMeta> = root.listFiles()?.filter { it.isDirectory && it.name.startsWith("${modelId}_") }?.mapNotNull { meta(it.name.removePrefix("${modelId}_")) }.orEmpty()
    fun sizeBytes(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    fun clear() { root.listFiles()?.forEach { it.deleteRecursively() } }

    private fun evict(keep: String) {
        var total = sizeBytes(); if (total <= capBytes) return
        for (m in entries().filter { it.hash != keep }.sortedBy { it.lastUsed }) {
            val d = dir(m.hash); val sz = d.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            d.deleteRecursively(); total -= sz; if (total <= capBytes) return
        }
    }

    private fun writePcm(f: File, a: ShortArray) { val bb = ByteBuffer.allocate(a.size * 2).order(ByteOrder.LITTLE_ENDIAN); bb.asShortBuffer().put(a); f.writeBytes(bb.array()) }
    private fun readPcm(f: File): ShortArray { val b = f.readBytes(); val out = ShortArray(b.size / 2); ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(out); return out }
}
