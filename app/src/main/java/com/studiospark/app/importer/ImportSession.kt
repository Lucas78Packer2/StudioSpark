package com.studiospark.app.importer

import com.studiospark.app.core.*
import com.studiospark.app.dsp.*

/** One separated stem shown on an Import strip (name, fader, tag) before anything is committed. */
class ImportStrip(val stem: String, val audio: ShortArray, var destination: Destination, var volumeDb: Float = 0f, var tag: String = "")

/**
 * The Import tab's own blank, isolated session. It never holds a reference to the active Studio [Project];
 * the only way its stems reach a project is [commitInto], which runs after the user's one-tap confirm.
 */
class ImportSession(val sourceName: String, val hash: String, val meta: StemMeta?, stems: Map<String, ShortArray>, tags: StemRouter.InstrumentScores?) {
    val strips: List<ImportStrip>
    init {
        val order = listOf("vocals", "drums", "bass", "guitar", "piano", "other").filter { stems.containsKey(it) }
        val routes = StemRouter.routeAll(tags, order)
        strips = order.map { s ->
            ImportStrip(s, stems.getValue(s), routes.getValue(s), tag = when (s) { "other" -> meta?.otherTags?.firstOrNull()?.first ?: "untagged"; else -> s.replaceFirstChar { it.uppercase() } }) }
    }
    var confirmed = false; private set

    fun routes(): Map<String, Destination> = strips.associate { it.stem to it.destination }
    fun reassign(stem: String, to: Destination) { strips.first { it.stem == stem }.destination = to }
    /** Drag one strip onto another: their destinations swap. */
    fun dragOnto(stem: String, target: String) { val m = StemRouter.swap(routes(), stem, target); strips.forEach { it.destination = m.getValue(it.stem) } }
    fun confirm() { confirmed = true }

    /**
     * Adds one new track per strip (except SKIP) to [p], with clips that start at 0 so stems stay sample-aligned.
     * [putAudio] stores the PCM in the project's audio pool and returns its id. Refuses unless [confirm] was called.
     */
    fun commitInto(p: Project, resample: (ShortArray) -> ShortArray, putAudio: (ShortArray) -> String): List<Track> {
        check(confirmed) { "Import must be confirmed before it is committed" }
        val added = ArrayList<Track>()
        for (s in strips) {
            if (s.destination == Destination.SKIP) continue
            val d = s.destination; val pcm = resample(s.audio); val id = putAudio(pcm)
            val t = Track(name = "${sourceName.substringBeforeLast('.').take(16)} · ${s.stem.replaceFirstChar { it.uppercase() }}", family = d.family, instrumentId = d.instrumentId,
                color = TrackColors.all[p.tracks.size % TrackColors.all.size], volumeDb = s.volumeDb,
                rig = RigPresets.defaultFor(d.family).also { r -> r.slots.forEach { sl -> sl.enabled = sl.gear.kind == GearKind.RACK } })
            t.clips.add(Clip(audioId = id, start = 0, length = pcm.size.toLong(), name = "$sourceName · ${s.stem}"))
            p.tracks.add(t); added += t
        }
        return added
    }
}
