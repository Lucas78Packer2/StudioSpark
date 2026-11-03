package com.aimusicstudio.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Port of TrackStorage + StudioSessionStorage + MixExportStorage. */
object TrackStorage {
    fun stemsDir(ctx: Context) = File(ctx.filesDir, "stems").apply { mkdirs() }
    fun exportsDir(ctx: Context) = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "exports").apply { mkdirs() }

    /** Copy bundled demo stems from assets/stems into the app stems folder once. */
    fun seedBundledStems(ctx: Context) {
        val dir = stemsDir(ctx)
        ctx.assets.list("stems")?.forEach { n ->
            val out = File(dir, n)
            if (!out.exists()) ctx.assets.open("stems/$n").use { i -> out.outputStream().use { i.copyTo(it) } }
        }
    }
}

object StudioSessionStorage {
    private fun file(ctx: Context) = File(ctx.filesDir, "sessions.json")

    fun load(ctx: Context): Pair<List<StudioSession>, String?> = runCatching {
        val o = JSONObject(file(ctx).readText())
        val arr = o.getJSONArray("sessions")
        (0 until arr.length()).map { SessionJson.decode(arr.getJSONObject(it)) } to o.optString("active")
    }.getOrDefault(emptyList<StudioSession>() to null)

    fun save(ctx: Context, sessions: List<StudioSession>, activeId: String) {
        val o = JSONObject().put("active", activeId).put("sessions", JSONArray(sessions.map { SessionJson.encode(it) }))
        file(ctx).writeText(o.toString())
    }
}
