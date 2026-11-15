package com.studiospark.app.importer

import com.studiospark.app.dsp.Family

/** Where a separated stem lands in the project. */
enum class Destination(val label: String, val family: Family, private val fixedInstrument: String?) {
    DRUM_TRACK("Drum track", Family.DRUMS, null),   // the first factory drum kit (audit fix: was "vox_mic")
    BASS_TRACK("Bass track", Family.BASS, "bass_0"),
    GUITAR_TRACK("Guitar track", Family.GUITAR, "gtr_0"),
    VOCAL_TRACK("Vocal track", Family.VOCAL, "vox_mic"),
    KEYS_TRACK("Keys track", Family.KEYS, "keys_grand"),
    AUDIO_TRACK("Audio track", Family.AUDIO, "vox_mic"),
    SKIP("Don't import", Family.AUDIO, "vox_mic");
    val instrumentId: String get() = fixedInstrument ?: com.studiospark.app.core.DrumKits.factory.firstOrNull()?.let { "kit_${it.id}" } ?: "pads_main"
}

/**
 * Auto-routing for the four UMX stems. Drums, bass and vocals have fixed homes; the "other" stem goes to a guitar
 * track only when YAMNet's guitar classes clearly lead, to keys when keyboard classes lead, else to a plain audio track.
 * Pure, so the rules are unit-tested. Users can always reassign by dragging.
 */
object StemRouter {
    const val GUITAR_MIN = 0.10f
    data class InstrumentScores(val guitar: Float, val keys: Float, val other: Float)

    fun route(stem: String, tags: InstrumentScores? = null): Destination = when (stem) {
        "drums" -> Destination.DRUM_TRACK
        "bass" -> Destination.BASS_TRACK
        "vocals" -> Destination.VOCAL_TRACK
        "guitar" -> Destination.GUITAR_TRACK   // six-stem model only
        "piano" -> Destination.KEYS_TRACK
        "other" -> routeOther(tags)
        else -> Destination.AUDIO_TRACK
    }

    fun routeOther(t: InstrumentScores?): Destination = when {
        t == null -> Destination.AUDIO_TRACK
        t.guitar >= GUITAR_MIN && t.guitar >= t.keys && t.guitar >= t.other -> Destination.GUITAR_TRACK
        t.keys >= GUITAR_MIN && t.keys > t.guitar && t.keys >= t.other -> Destination.KEYS_TRACK
        else -> Destination.AUDIO_TRACK
    }

    fun routeAll(tags: InstrumentScores?, stems: List<String> = listOf("vocals", "drums", "bass", "other")): Map<String, Destination> =
        stems.associateWith { if (it == "other" && "guitar" in stems) Destination.AUDIO_TRACK else route(it, tags) }

    /** Drag-to-reassign: drop [stem] on [target]'s strip. Swaps destinations so no two stems silently merge. */
    fun swap(current: Map<String, Destination>, stem: String, target: String): Map<String, Destination> {
        if (stem == target) return current
        val a = current[stem] ?: return current; val b = current[target] ?: return current
        return current + (stem to b) + (target to a)
    }
}
