package com.studiospark.app.core

enum class Stick(val label: String, val velScale: Float, val brightness: Float, val attackMs: Float) {
    S5A("5A hickory", 1.0f, 1.0f, 0f), S7A("7A light", 0.8f, 0.9f, 0f), BRUSHES("Brushes", 0.55f, 0.45f, 6f), MALLETS("Mallets", 0.75f, 0.3f, 12f)
}

/** Sample families available in the original rendered drum set (assets/original_drums). */
object DrumSounds {
    val types = linkedMapOf(
        "kick-0" to "Kick", "snare-0" to "Snare (tight)", "snare-1" to "Snare (fat)", "rimshot-0" to "Rimshot", "clap-0" to "Clap",
        "closed_hat-0" to "Hi-hat closed", "open_hat-0" to "Hi-hat open", "high_tom-0" to "Rack tom high", "high_tom-1" to "Rack tom high II",
        "mid_tom-0" to "Rack tom mid", "mid_tom-1" to "Rack tom mid II", "low_tom-0" to "Floor tom", "low_tom-1" to "Floor tom II",
        "crash-0" to "Crash", "ride-0" to "Ride", "cymbal-0" to "Effects cymbal", "cowbell-0" to "Cowbell",
        "kick_deep-0" to "Kick (deep 26-inch voicing)", "piccolo_snare-0" to "Piccolo snare", "hat_pedal-0" to "Hi-hat foot chick", "ride_bell-0" to "Ride bell",
        "crash_dark-0" to "Dark crash", "splash-0" to "Splash", "china-0" to "China", "stack-0" to "Trash stack", "tambourine-0" to "Tambourine", "woodblock-0" to "Wood block",
    )
    fun art(key: String) = when {
        key.contains("hat") || key.contains("crash") || key.contains("ride") || key.contains("cymbal") -> "gear_cymbal"
        key.contains("cowbell") -> "gear_panel_metal"
        else -> "gear_drumhead"
    }
}

/** One piece in a kit. x/y/size are layout fractions of the kit canvas. chokeGroup > 0 pieces cut each other (hi-hat open/closed, cymbal grabs). */
data class KitPiece(
    val id: String, val name: String, val sample: String, val x: Float, val y: Float, val size: Float,
    val tune: Float = 0f, val gainDb: Float = 0f, val chokeGroup: Int = 0, val midi: Int = 36, val pan: Float = 0f,
    /** Catalog entry this piece came from ("" = legacy piece; visuals are inferred from the sample). */
    val catalogId: String = "", val finish: String = "",
    /** Linked playing zone of another piece: "edge" (hi-hat open), "bell" (ride bell), "rim" (cross-stick), "foot" (hi-hat pedal), "left" (double-pedal left beater). */
    val role: String = "", val link: String = "",
) { val sounding get() = sample.isNotEmpty() }

data class DrumKit(val id: String, val name: String, val pieces: List<KitPiece>, val stick: Stick = Stick.S5A, val baseId: String? = null, val custom: Boolean = false,
                   val shellFinish: String = "natural_maple", val cymbalFinish: String = "traditional", val starterId: String = "")

object DrumKits {
    // Factory kits are built from the visual/sounding catalog (DrumCatalog) so every piece has real finishes, linked zones and pedals.
    val studio get() = DrumCatalog.starter("studio_maple")!!
    val thunder get() = DrumCatalog.starter("thunder_double")!!
    val arsenal get() = DrumCatalog.starter("prog_arsenal")!!
    val jazz get() = DrumCatalog.starter("jazz_bop")!!
    val pads = DrumKit("pads", "Performance Pads", listOf("kick-0", "snare-0", "clap-0", "rimshot-0", "closed_hat-0", "open_hat-0", "high_tom-0", "mid_tom-0", "low_tom-0", "crash-0", "ride-0", "cowbell-0", "snare-1", "high_tom-1", "low_tom-1", "cymbal-0")
        .mapIndexed { i, s -> KitPiece("pad$i", DrumSounds.types[s] ?: s, s, (i % 4) / 4f + 0.125f, (i / 4) / 4f + 0.125f, 0.22f, chokeGroup = if (s.contains("hat")) 1 else 0, midi = 36 + i) })
    val factory: List<DrumKit> get() = DrumCatalog.starters
    fun byId(id: String, customs: List<DrumKit> = emptyList()) = (factory + DrumCatalog.blank + pads + customs).firstOrNull { it.id == id }

    /** Adds any piece to a kit — not limited to stock slots. Extra pieces get unique ids and a MIDI note. */
    fun addPiece(kit: DrumKit, sample: String, name: String = DrumSounds.types[sample] ?: sample, x: Float = 0.5f, y: Float = 0.45f, choke: Int = 0): DrumKit {
        var n = 1; while (kit.pieces.any { it.id == "x$n" }) n++
        val midi = ((kit.pieces.maxOfOrNull { it.midi } ?: 59) + 1).coerceAtMost(127)
        return kit.copy(pieces = kit.pieces + KitPiece("x$n", "$name ${n}", sample, x, y, if (sample.contains("kick")) 0.18f else 0.13f, chokeGroup = choke, midi = midi))
    }
}
