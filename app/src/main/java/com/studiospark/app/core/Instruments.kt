package com.studiospark.app.core

import com.studiospark.app.dsp.Family

enum class Surface(val label: String) { STRINGS("Guitar strings"), KEYS("Piano / MIDI keys"), DRUM_KIT("Drum kit"), PADS("Drum pads") }
enum class SoundSource(val label: String) {
    RECORDED_CC0("Recorded samples (CC0)"), RENDERED("Original rendered samples — modeled, not recorded"),
    SYNTH("Real-time synthesis"), DRUM_RENDER("Original modal drum renders — modeled, not recorded"), MIC("Live input"), VOICE("On-device formant voice — synthetic")
}

data class Tuning(val name: String, val midi: List<Int>) // low string first
object Tunings {
    val guitar = listOf(
        Tuning("Standard (E A D G B E)", listOf(40, 45, 50, 55, 59, 64)),
        Tuning("Drop D (D A D G B E)", listOf(38, 45, 50, 55, 59, 64)),
        Tuning("Drop C (C G C F A D)", listOf(36, 43, 48, 53, 57, 62)),
        Tuning("Open G (D G D G B D)", listOf(38, 43, 50, 55, 59, 62)),
        Tuning("Open D (D A D F# A D)", listOf(38, 45, 50, 54, 57, 62)),
        Tuning("Open E (E B E G# B E)", listOf(40, 47, 52, 56, 59, 64)),
        Tuning("DADGAD", listOf(38, 45, 50, 55, 57, 62)),
        Tuning("Half-step down (Eb)", listOf(39, 44, 49, 54, 58, 63)),
        Tuning("7-string standard (B E A D G B E)", listOf(35, 40, 45, 50, 55, 59, 64)),
        Tuning("7-string drop A (A E A D G B E)", listOf(33, 40, 45, 50, 55, 59, 64)),
        Tuning("Baritone B (B E A D F# B)", listOf(35, 40, 45, 50, 54, 59)),
    )
    val bass = listOf(Tuning("Bass standard (E A D G)", listOf(28, 33, 38, 43)), Tuning("Bass drop D (D A D G)", listOf(26, 33, 38, 43)), Tuning("Bass 5-string (B E A D G)", listOf(23, 28, 33, 38, 43)), Tuning("Bass drop C (C G C F)", listOf(24, 31, 36, 41)), Tuning("Bass open A (E A E A)", listOf(28, 33, 40, 45)), Tuning("Bass 5-string drop A (A E A D G)", listOf(21, 28, 33, 38, 43)))
    fun find(name: String) = (guitar + bass).firstOrNull { it.name == name }
}

data class StringSet(val name: String, val gauges: String, val brightness: Float, val sustain: Float)
object StringSets {
    val guitar = listOf(StringSet("Nickel 10–46 (regular)", "10-13-17-26-36-46", 0.55f, 0.6f), StringSet("Nickel 9–42 (light)", "9-11-16-24-32-42", 0.65f, 0.5f),
        StringSet("Pure nickel 11–48 (vintage)", "11-14-18-28-38-48", 0.4f, 0.65f), StringSet("Stainless 10–52 (heavy bottom)", "10-13-17-30-42-52", 0.75f, 0.7f),
        StringSet("Flatwound 12–52 (jazz)", "12-16-20-32-42-52", 0.2f, 0.45f), StringSet("Phosphor bronze 12–53 (acoustic)", "12-16-24-32-42-53", 0.7f, 0.75f))
    val bass = listOf(StringSet("Roundwound 45–105", "45-65-80-105", 0.6f, 0.6f), StringSet("Flatwound 50–105", "50-70-85-105", 0.25f, 0.5f), StringSet("Stainless 45–130 (5)", "45-65-85-105-130", 0.75f, 0.65f))
    fun find(name: String) = (guitar + bass).firstOrNull { it.name == name }
}

/** A playable instrument definition. [voice] selects the engine voice; [art] is a drawable name from the StudioSpark photo set. */
data class InstrumentDef(
    val id: String, val name: String, val family: Family, val surface: Surface, val source: SoundSource,
    val voice: String, val art: String, val category: String, val custom: Boolean = false,
    val tuning: String? = null, val strings: String? = null, val baseId: String? = null, val brightness: Float = 0.5f,
)

object InstrumentLibrary {
    val stock: List<InstrumentDef> = buildList {
        val gArt = listOf("gear_guitar_electric", "gear_ga_0", "gear_gb_8", "gear_ga_6", "gear_gb_6", "gear_ga_2", "gear_gb_2", "gear_gc_0", "gear_gb_4", "gear_gb_0")
        val gNames = listOf("Sunburst S-24", "Cream Offset Classic", "Ember Single-Cut", "Graphite Pointed 7", "Hollow Archtop Semi", "Butterscotch Slab", "Arrow Wedge", "Emerald Contour", "Violet Quilt HH", "Steel Resonator")
        gNames.forEachIndexed { i, n -> add(InstrumentDef("gtr_$i", n, Family.GUITAR, Surface.STRINGS, SoundSource.RECORDED_CC0, "guitar", gArt[i], "Guitars", tuning = Tunings.guitar[0].name, strings = StringSets.guitar[0].name, brightness = 0.35f + i * 0.06f)) }
        add(InstrumentDef("gtr_acoustic", "Spruce Dreadnought", Family.GUITAR, Surface.STRINGS, SoundSource.RENDERED, "nylon_like", "gear_ga_4", "Guitars", tuning = Tunings.guitar[0].name, strings = StringSets.guitar[5].name))
        val bArt = listOf("gear_bass_classic", "gear_bass_modern", "gear_bass_five", "gear_bass_violin", "gear_bass_wing", "gear_ba_2")
        val bNames = listOf("Classic Split-Coil Bass", "Modern Active Bass", "Five-String Ebony", "Violin Bass", "Wing Bass", "Fretless Ash")
        bNames.forEachIndexed { i, n -> add(InstrumentDef("bass_$i", n, Family.BASS, Surface.STRINGS, SoundSource.RECORDED_CC0, "bass", bArt[i], "Basses", tuning = if (i == 2) Tunings.bass[2].name else Tunings.bass[0].name, strings = StringSets.bass[0].name, brightness = 0.4f + i * 0.05f)) }
        add(InstrumentDef("keys_grand", "Studio Grand (rendered)", Family.KEYS, Surface.KEYS, SoundSource.RENDERED, "keys_mellow", "gear_play_keys", "Keys"))
        add(InstrumentDef("keys_tines", "Tine Electric Piano (rendered)", Family.KEYS, Surface.KEYS, SoundSource.RENDERED, "keys_tines", "gear_keyboard", "Keys"))
        listOf("Analog Bass" to "sub_bass", "Saw Lead" to "saw_lead", "Warm Pad" to "warm_pad", "Glass Wavetable" to "wt_glass", "Vocal Wavetable" to "wt_vowel", "FM Electric Piano" to "fm_ep", "FM Bell" to "fm_bell", "FM Bass" to "fm_bass", "Pluck" to "pluck", "Brass Stack" to "brass_synth", "Organ Drawbars" to "organ", "Arp Square" to "square_arp")
            .forEach { (n, v) -> add(InstrumentDef("syn_$v", n, Family.SYNTH, Surface.KEYS, SoundSource.SYNTH, v, "gear_keyboard", "Synths")) }
        listOf("air_flute" to "Air Flute", "warm_clarinet" to "Warm Clarinet", "double_reed" to "Double Reed", "bright_trumpet" to "Bright Trumpet", "round_horn" to "Round Horn", "dark_cello" to "Dark Cello", "solo_bow" to "Solo Bow", "hollow_marimba" to "Hollow Marimba", "wood_bars" to "Wood Bars", "metal_bars" to "Metal Bars", "wire_zither" to "Wire Zither", "nylon_like" to "Nylon Pluck")
            .forEach { (v, n) -> add(InstrumentDef("orc_$v", "$n (modeled)", Family.ORCHESTRAL, Surface.KEYS, SoundSource.RENDERED, v, "gear_keyboard", "Orchestral & world")) }
        DrumKits.factory.forEach { add(InstrumentDef("kit_${it.id}", it.name, Family.DRUMS, Surface.DRUM_KIT, SoundSource.DRUM_RENDER, "kit:${it.id}", "gear_kit_overhead", "Drum kits")) }
        add(InstrumentDef("pads_main", "Performance Pads", Family.DRUMS, Surface.PADS, SoundSource.DRUM_RENDER, "kit:pads", "gear_rubber_pad_board", "Drum kits"))
        add(InstrumentDef("vox_mic", "Vocal Mic (live input)", Family.VOCAL, Surface.KEYS, SoundSource.MIC, "mic", "gear_microphone", "Vocals"))
        add(InstrumentDef("vox_ai", "AI Vocal (on-device voice)", Family.VOCAL, Surface.KEYS, SoundSource.VOICE, "voice", "gear_microphone", "Vocals"))
    }
    fun categories(all: List<InstrumentDef>) = all.map { it.category }.distinct()
}
