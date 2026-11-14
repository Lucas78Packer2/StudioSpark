package com.studiospark.app.dsp

enum class GearKind(val label: String) { STOMP("Stomp"), AMP("Amp"), CAB("Cab"), RACK("Rack") }
enum class AmpStyle(val label: String) { HEAD("Head"), COMBO("Combo"), STACK("Full stack"), PRE("Preamp") }

data class ParamDef(val key: String, val label: String, val min: Float, val max: Float, val default: Float, val unit: String = "", val steps: List<String>? = null) {
    fun format(v: Float): String = steps?.getOrNull(v.toInt().coerceIn(0, steps.size - 1)) ?: when {
        unit == "dB" -> String.format("%+.1f dB", v)
        unit == "ms" -> String.format("%.0f ms", v)
        unit == "Hz" -> if (v >= 1000) String.format("%.1f kHz", v / 1000f) else String.format("%.0f Hz", v)
        unit == "st" -> String.format("%+.0f st", v)
        unit == ":1" -> String.format("%.1f:1", v)
        max <= 1f && min >= 0f -> String.format("%.1f", v * 10f)
        else -> String.format("%.2f", v)
    }
    fun norm(v: Float) = ((v - min) / (max - min)).coerceIn(0f, 1f)
    fun denorm(t: Float) = min + (max - min) * t.coerceIn(0f, 1f)
}

/** Catalogue entry. Names and artwork are original StudioSpark designs (see GearNames policy); no third-party marks. */
class GearDef(
    val id: String, val name: String, val kind: GearKind, val category: String, val collection: String,
    val params: List<ParamDef>, val art: String, val accent: Long = 0xFF3A3F45,
    val style: AmpStyle? = null, val era: String = "", val blurb: String = "",
    val factory: (Float, FloatArray) -> Processor,
) { fun defaults() = FloatArray(params.size) { params[it].default } }

object GearCatalog {
    private fun k(key: String, label: String, d: Float = 0.5f) = ParamDef(key, label, 0f, 1f, d)
    private val ampParams = listOf(k("gain", "Gain", 0.5f), k("bass", "Bass"), k("mid", "Middle"), k("treble", "Treble"), k("presence", "Presence"), k("master", "Master", 0.6f),
        ParamDef("channel", "Channel", 0f, 2f, 1f, steps = listOf("Clean", "Crunch", "Lead")), ParamDef("bright", "Bright", 0f, 1f, 0f, steps = listOf("Off", "On")))
    private val cabParams = listOf(k("micX", "Mic position", 0.2f), k("micDist", "Mic distance", 0.15f),
        ParamDef("micType", "Mic", 0f, 2f, 0f, steps = listOf("Dynamic 57-style", "Condenser LDC", "Ribbon")), k("room", "Room", 0.1f), ParamDef("level", "Level", -12f, 12f, 0f, "dB"))
    private val drive = listOf(k("drive", "Drive"), k("tone", "Tone"), k("level", "Level"))
    private fun amp(id: String, name: String, style: AmpStyle, era: String, coll: String, art: String, accent: Long, blurb: String, v: AmpVoicing, gain: Float = 0.5f, ch: Float = 1f) =
        GearDef(id, name, GearKind.AMP, "Amp", coll, ampParams.map { when (it.key) { "gain" -> it.copy(default = gain); "channel" -> it.copy(default = ch); else -> it } }, art, accent, style, era, blurb) { sr, p -> AmpModel(sr, p, v) }
    private fun cab(id: String, name: String, coll: String, art: String, blurb: String, v: CabVoicing) = GearDef(id, name, GearKind.CAB, "Cab", coll, cabParams, art, 0xFF202326, blurb = blurb) { sr, p -> CabModel(sr, p, v) }

    val all: List<GearDef> = listOf(
        // ---- Stomps
        GearDef("quiet_gate", "Quiet Gate", GearKind.STOMP, "Dynamics", "Core", listOf(ParamDef("thr", "Threshold", -80f, -10f, -55f, "dB"), ParamDef("rel", "Release", 10f, 500f, 80f, "ms"), ParamDef("range", "Range", 6f, 80f, 60f, "dB")), "gear_pedal_green", 0xFF5D6B52) { sr, p -> NoiseGate(sr, p) },
        GearDef("squeeze_comp", "Squeeze Comp", GearKind.STOMP, "Dynamics", "Core", listOf(ParamDef("thr", "Sustain", -50f, 0f, -22f, "dB"), ParamDef("ratio", "Ratio", 1f, 20f, 4f, ":1"), ParamDef("att", "Attack", 0.1f, 80f, 8f, "ms"), ParamDef("rel", "Release", 20f, 800f, 160f, "ms"), ParamDef("gain", "Level", 0f, 24f, 6f, "dB"), k("mix", "Blend", 1f)), "gear_pedal_blue", 0xFF2F5A93) { sr, p -> Compressor(sr, p) },
        GearDef("tread_boost", "Tread Booster", GearKind.STOMP, "Distortion", "Core", listOf(ParamDef("gain", "Boost", 0f, 20f, 8f, "dB"), k("tone", "Tone")), "gear_pedal_green", 0xFFB8B4A6) { sr, p -> Booster(sr, p) },
        GearDef("mid_push", "Mid Push Overdrive", GearKind.STOMP, "Distortion", "Core", drive, "gear_pedal_green", 0xFFD9C531) { sr, p -> Overdrive(sr, p) },
        GearDef("iron_dist", "Iron Distortion", GearKind.STOMP, "Distortion", "Core", drive, "gear_pedal_blue", 0xFFE07B23) { sr, p -> Overdrive(sr, p, hard = true) },
        GearDef("pit_metal", "Black Pit Metal", GearKind.STOMP, "Distortion", "Modern", drive, "gear_pedal_blue", 0xFF2A2A2C) { sr, p -> Overdrive(sr, p, hard = true, scoop = true) },
        GearDef("velvet_fuzz", "Velvet Fuzz", GearKind.STOMP, "Fuzz", "Vintage", listOf(k("fuzz", "Fuzz", 0.7f), k("tone", "Tone"), k("vol", "Volume")), "gear_pedal_green", 0xFF8C2B2B) { sr, p -> Fuzz(sr, p) },
        GearDef("crush_box", "Lo-Fi Crusher", GearKind.STOMP, "Other", "Modern", listOf(ParamDef("bits", "Bits", 2f, 16f, 8f), k("down", "Downsample", 0.2f), k("mix", "Mix", 0.6f)), "gear_pedal_blue", 0xFF6A6A6A) { sr, p -> BitCrusher(sr, p) },
        GearDef("throat_wah", "Throat Wah", GearKind.STOMP, "Filter", "Vintage", listOf(k("pedal", "Pedal"), k("q", "Q", 0.4f), k("auto", "Auto", 0f), k("mix", "Mix", 1f)), "gear_pedal_green", 0xFF2E2E2E) { sr, p -> Wah(sr, p) },
        GearDef("env_filter", "Funnel Envelope Filter", GearKind.STOMP, "Filter", "Modern", listOf(k("sens", "Sensitivity", 0.6f), k("freq", "Range", 0.3f), k("q", "Peak", 0.5f)), "gear_pedal_blue", 0xFF6B3FA0) { sr, p -> EnvelopeFilter(sr, p) },
        GearDef("seven_eq", "Seven Band EQ", GearKind.STOMP, "EQ", "Core", listOf(100f, 200f, 400f, 800f, 1600f, 3200f, 6400f).map { ParamDef("b$it", if (it >= 1000) "${(it / 1000).toInt()}.${((it % 1000) / 100).toInt()}k" else "${it.toInt()}", -15f, 15f, 0f, "dB") } + ParamDef("out", "Level", -12f, 12f, 0f, "dB"), "gear_pedal_blue", 0xFFD0D0D0) { sr, p -> GraphicEq(sr, p, floatArrayOf(100f, 200f, 400f, 800f, 1600f, 3200f, 6400f)) },
        GearDef("lagoon_chorus", "Lagoon Chorus", GearKind.STOMP, "Modulation", "Core", listOf(ParamDef("rate", "Rate", 0.05f, 6f, 0.8f, "Hz"), k("depth", "Depth"), k("mix", "Mix", 0.5f), k("fb", "Feedback", 0f)), "gear_pedal_blue", 0xFF4AA3C7) { sr, p -> Chorus(sr, p) },
        GearDef("jet_flanger", "Jet Flanger", GearKind.STOMP, "Modulation", "Vintage", listOf(ParamDef("rate", "Rate", 0.05f, 4f, 0.3f, "Hz"), k("depth", "Depth", 0.7f), k("mix", "Mix", 0.5f), k("fb", "Regen", 0.6f)), "gear_pedal_green", 0xFF8E8E8E) { sr, p -> Chorus(sr, p, flanger = true) },
        GearDef("orbit_phaser", "Orbit Phaser", GearKind.STOMP, "Modulation", "Vintage", listOf(ParamDef("rate", "Speed", 0.05f, 8f, 0.6f, "Hz"), k("depth", "Depth", 0.8f), k("fb", "Feedback", 0.4f), k("mix", "Mix", 0.7f)), "gear_pedal_green", 0xFFE36E1E) { sr, p -> Phaser(sr, p) },
        GearDef("pulse_trem", "Pulse Tremolo", GearKind.STOMP, "Modulation", "Vintage", listOf(ParamDef("rate", "Rate", 0.5f, 12f, 5f, "Hz"), k("depth", "Depth", 0.6f), k("shape", "Shape", 0f)), "gear_pedal_green", 0xFF4F6B4A) { sr, p -> Tremolo(sr, p) },
        GearDef("spin_cab", "Spin Rotary", GearKind.STOMP, "Modulation", "Keys & Vocal", listOf(ParamDef("speed", "Speed", 0f, 1f, 0f, steps = listOf("Slow", "Fast")), k("drive", "Drive", 0.2f), k("mix", "Mix", 1f)), "gear_pedal_blue", 0xFF7A4B2A) { sr, p -> Rotary(sr, p) },
        GearDef("sub_octave", "Sub Octave", GearKind.STOMP, "Pitch", "Core", listOf(k("sub", "Sub", 0.6f), k("dry", "Dry", 0.8f), k("tone", "Tone", 0.4f)), "gear_pedal_blue", 0xFF3B4E8C) { sr, p -> Octaver(sr, p) },
        GearDef("shift_harm", "Shift Harmonizer", GearKind.STOMP, "Pitch", "Modern", listOf(ParamDef("semi", "Interval", -12f, 12f, 7f, "st"), k("mix", "Mix", 0.4f), ParamDef("det", "Detune", -50f, 50f, 0f)), "gear_pedal_blue", 0xFF2C7F78) { sr, p -> PitchShifter(sr, p) },
        GearDef("digi_delay", "Clear Digital Delay", GearKind.STOMP, "Delay", "Core", listOf(ParamDef("time", "Time", 20f, 2000f, 380f, "ms"), k("fb", "Repeats", 0.35f), k("mix", "Mix", 0.3f), k("tone", "Tone", 0.8f)), "gear_pedal_blue", 0xFFCFCFCF) { sr, p -> Delay(sr, p) },
        GearDef("tape_echo", "Tape Echo 3", GearKind.STOMP, "Delay", "Vintage", listOf(ParamDef("time", "Time", 40f, 1200f, 320f, "ms"), k("fb", "Intensity", 0.45f), k("mix", "Echo", 0.35f), k("tone", "Tone", 0.35f)), "gear_pedal_green", 0xFF5A6E3A) { sr, p -> Delay(sr, p, tape = true) },
        GearDef("spring_tank", "Spring Tank", GearKind.STOMP, "Reverb", "Vintage", listOf(k("size", "Dwell", 0.5f), k("damp", "Tone", 0.5f), k("mix", "Mix", 0.3f), ParamDef("pre", "Pre-delay", 0f, 120f, 5f, "ms")), "gear_pedal_green", 0xFF7E6A4C) { sr, p -> Reverb(sr, p, spring = true) },
        GearDef("hall_verb", "Cathedral Hall", GearKind.STOMP, "Reverb", "Core", listOf(k("size", "Size", 0.75f), k("damp", "Damping", 0.4f), k("mix", "Mix", 0.3f), ParamDef("pre", "Pre-delay", 0f, 200f, 25f, "ms")), "gear_pedal_blue", 0xFF395B7A) { sr, p -> Reverb(sr, p) },
        GearDef("snap_drum", "Drum Snap", GearKind.STOMP, "Dynamics", "Drums & Bus", listOf(ParamDef("att", "Attack", -1f, 1f, 0.4f), ParamDef("sus", "Sustain", -1f, 1f, -0.1f), ParamDef("out", "Output", -12f, 12f, 0f, "dB")), "gear_pedal_blue", 0xFFB33A3A) { sr, p -> TransientShaper(sr, p) },
        // ---- Amps (original names)
        amp("citrus_valve", "Citrus Valve 50", AmpStyle.HEAD, "Vintage tube", "Vintage", "gear_amp_head_vintage", 0xFFE5732A, "British-voiced dual-channel high-gain head", AmpVoicing(90f, 40f, 2, 0.22f, 110f, 650f, 2800f, 0.8f, 0.4f, 4f), 0.6f),
        amp("honey_tweed", "Honey Tweed 15", AmpStyle.COMBO, "Vintage tube", "Vintage", "gear_amp_face_vintage", 0xFFD6B36A, "Loose, touch-sensitive 1x12 breakup combo", AmpVoicing(70f, 18f, 1, 0.35f, 140f, 900f, 2400f, 0.5f, 0.7f, 6f), 0.45f, 1f),
        amp("silverline", "Silverline Clean 2x12", AmpStyle.COMBO, "Vintage tube", "Core", "gear_amp_modern", 0xFFBFC3C7, "Glassy American clean with deep headroom", AmpVoicing(60f, 6f, 1, 0.05f, 100f, 500f, 3200f, 0.6f, 0.15f, 5f), 0.35f, 0f),
        amp("crown_plex", "Crown Stack 100", AmpStyle.STACK, "Vintage tube", "Vintage", "gear_amp_stack_vintage", 0xFFC9A44B, "Classic British crunch stack", AmpVoicing(100f, 28f, 2, 0.18f, 120f, 700f, 2600f, 0.9f, 0.35f, 4f), 0.55f),
        amp("dual_furnace", "Dual Furnace", AmpStyle.STACK, "Modern tube", "Modern", "gear_amp_stack_modern", 0xFF8A8F96, "Saturated modern high-gain 3-channel stack", AmpVoicing(120f, 70f, 3, 0.15f, 90f, 600f, 3000f, 1.1f, 0.25f, 3f), 0.6f, 2f),
        amp("obsidian_5k", "Obsidian High-Gain", AmpStyle.HEAD, "Modern tube", "Modern", "gear_amp_head_modern", 0xFF505459, "Tight modern metal head", AmpVoicing(140f, 90f, 3, 0.1f, 85f, 750f, 3300f, 1.0f, 0.2f, 2f), 0.65f, 2f),
        amp("glass_jazz", "Glass Clean 100", AmpStyle.COMBO, "Solid-state", "Keys & Vocal", "gear_amp_modern", 0xFF9AA0A6, "Pristine solid-state 2x12 clean", AmpVoicing(50f, 4f, 1, 0f, 100f, 800f, 3500f, 0.5f, 0f, 3f, solidState = true), 0.3f, 0f),
        amp("fathom_bass", "Fathom Bass 300", AmpStyle.STACK, "Vintage tube", "Bass", "gear_amp_stack_modern", 0xFF3C3F44, "All-tube bass head into 8x10", AmpVoicing(30f, 14f, 1, 0.2f, 70f, 500f, 2000f, 0.6f, 0.5f, 4f, bass = true), 0.45f, 1f),
        amp("deepwell_b8", "Deepwell B800", AmpStyle.HEAD, "Solid-state", "Bass", "gear_amp_head_modern", 0xFF2E3237, "Punchy solid-state bass head with growl", AmpVoicing(28f, 10f, 1, 0.05f, 60f, 800f, 2400f, 0.7f, 0.1f, 3f, solidState = true, bass = true), 0.4f, 1f),
        amp("ivory_keys", "Ivory Keys 140", AmpStyle.COMBO, "Solid-state", "Keys & Vocal", "gear_amp_face_vintage", 0xFFE8E1D0, "Full-range keyboard combo", AmpVoicing(35f, 3f, 1, 0f, 90f, 900f, 4000f, 0.5f, 0f, 2f, solidState = true), 0.3f, 0f),
        amp("atlas_pre", "Atlas Console Pre", AmpStyle.PRE, "Console", "Drums & Bus", "gear_panel_metal", 0xFF6C7A8A, "Transformer console preamp for vocals, drums and DI", AmpVoicing(30f, 5f, 1, 0.08f, 80f, 1600f, 6000f, 0.5f, 0.05f, 2f, bass = true), 0.35f, 0f),
        // ---- Cabs
        cab("cab_412", "4x12 Closed Vintage", "Core", "gear_amp_cab_vintage", "Closed-back 4x12, tight lows", CabVoicing(100f, 5f, 75f, 5200f, 2600f, true)),
        cab("cab_212", "2x12 Open Alnico", "Vintage", "gear_amp_cab_modern", "Open-back 2x12, airy", CabVoicing(120f, 3f, 85f, 6200f, 3000f, false)),
        cab("cab_112", "1x12 Tweed Open", "Vintage", "gear_amp_face_vintage", "Small open 1x12, honky mids", CabVoicing(140f, 3f, 100f, 5000f, 1800f, false)),
        cab("cab_810", "8x10 Bass Fridge", "Bass", "gear_amp_cab_modern", "Classic 8x10 bass cabinet", CabVoicing(60f, 4f, 35f, 4200f, 1200f, true)),
        cab("cab_115", "1x15 Bass Deep", "Bass", "gear_amp_cab_vintage", "1x15 deep bass", CabVoicing(50f, 5f, 30f, 3000f, 900f, true)),
        cab("cab_fullrange", "Full-Range Monitor", "Keys & Vocal", "gear_amp_cab_modern", "Flat full-range for keys, drums and vocals", CabVoicing(70f, 1f, 30f, 16000f, 3000f, true)),
        // ---- Rack (500-series style, StudioSpark branding)
        GearDef("rack_geq", "SparkRack GEQ-10", GearKind.RACK, "EQ", "Core", listOf(31f, 63f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f).map { ParamDef("b$it", if (it >= 1000) "${(it / 1000).toInt()}k" else "${it.toInt()}", -12f, 12f, 0f, "dB") } + ParamDef("out", "Out", -12f, 12f, 0f, "dB"), "gear_panel_metal", 0xFF3E62A8) { sr, p -> GraphicEq(sr, p, floatArrayOf(31f, 63f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)) },
        GearDef("rack_peq", "SparkRack PEQ-4", GearKind.RACK, "EQ", "Core", listOf(ParamDef("hpf", "HPF", 20f, 400f, 40f, "Hz"), ParamDef("lo", "Low", -15f, 15f, 0f, "dB"), ParamDef("lmf", "LM Freq", 150f, 2000f, 400f, "Hz"), ParamDef("lmg", "LM Gain", -15f, 15f, 0f, "dB"), ParamDef("hmf", "HM Freq", 1000f, 8000f, 3000f, "Hz"), ParamDef("hmg", "HM Gain", -15f, 15f, 0f, "dB"), ParamDef("hi", "High", -15f, 15f, 0f, "dB")), "gear_panel_metal", 0xFF4A5560) { sr, p -> ParametricEq(sr, p) },
        GearDef("rack_opto", "SparkRack Opto Leveler", GearKind.RACK, "Dynamics", "Core", listOf(ParamDef("thr", "Peak Red.", -40f, 0f, -18f, "dB"), ParamDef("ratio", "Ratio", 1f, 10f, 3f, ":1"), ParamDef("att", "Attack", 1f, 60f, 10f, "ms"), ParamDef("rel", "Release", 60f, 1500f, 400f, "ms"), ParamDef("gain", "Gain", 0f, 24f, 4f, "dB"), k("mix", "Mix", 1f)), "gear_panel_cream", 0xFFD8D2C2) { sr, p -> Compressor(sr, p) },
        GearDef("rack_bus", "SparkRack Bus Glue", GearKind.RACK, "Dynamics", "Drums & Bus", listOf(ParamDef("thr", "Threshold", -40f, 0f, -14f, "dB"), ParamDef("ratio", "Ratio", 1f, 10f, 2f, ":1"), ParamDef("att", "Attack", 0.1f, 30f, 10f, "ms"), ParamDef("rel", "Release", 50f, 1200f, 300f, "ms"), ParamDef("gain", "Makeup", 0f, 18f, 3f, "dB"), k("mix", "Parallel", 1f)), "gear_panel_metal", 0xFF2D2F33) { sr, p -> Compressor(sr, p) },
        GearDef("rack_gate", "SparkRack Gate", GearKind.RACK, "Dynamics", "Drums & Bus", listOf(ParamDef("thr", "Threshold", -80f, -10f, -45f, "dB"), ParamDef("rel", "Hold/Rel", 10f, 600f, 120f, "ms"), ParamDef("range", "Range", 6f, 80f, 40f, "dB")), "gear_panel_metal", 0xFF2D2F33) { sr, p -> NoiseGate(sr, p) },
        GearDef("rack_tape", "SparkRack Tape Glue", GearKind.RACK, "Other", "Vintage", listOf(k("drive", "Drive", 0.3f), k("warm", "Warmth", 0.4f), ParamDef("out", "Output", -12f, 6f, 0f, "dB")), "gear_panel_cream", 0xFFB59B6A) { sr, p -> TapeSaturator(sr, p) },
        GearDef("rack_limit", "SparkRack Brickwall", GearKind.RACK, "Dynamics", "Core", listOf(ParamDef("ceil", "Ceiling", -12f, 0f, -0.5f, "dB"), ParamDef("rel", "Release", 5f, 500f, 60f, "ms"), ParamDef("in", "Input", 0f, 18f, 0f, "dB")), "gear_panel_metal", 0xFF1E1F22) { sr, p -> Limiter(sr, p) },
        GearDef("rack_plate", "SparkRack Plate", GearKind.RACK, "Reverb", "Keys & Vocal", listOf(k("size", "Decay", 0.6f), k("damp", "Damping", 0.3f), k("mix", "Mix", 0.25f), ParamDef("pre", "Pre-delay", 0f, 150f, 20f, "ms")), "gear_panel_metal", 0xFF8B8F94) { sr, p -> Reverb(sr, p) },
        GearDef("rack_delay", "SparkRack Echo", GearKind.RACK, "Delay", "Keys & Vocal", listOf(ParamDef("time", "Time", 20f, 2000f, 250f, "ms"), k("fb", "Feedback", 0.3f), k("mix", "Mix", 0.2f), k("tone", "Tone", 0.6f)), "gear_panel_metal", 0xFF5C6670) { sr, p -> Delay(sr, p) },
    )
    private val byId = all.associateBy { it.id }
    fun get(id: String): GearDef = byId[id] ?: error("Unknown gear $id")
    fun find(id: String): GearDef? = byId[id]
    val stompCategories = listOf("Delay", "Distortion", "Dynamics", "EQ", "Filter", "Fuzz", "Modulation", "Pitch", "Reverb", "Other")
    val collections get() = all.map { it.collection }.distinct()
    fun search(query: String, kind: GearKind? = null, category: String? = null, collection: String? = null) = all.filter {
        (kind == null || it.kind == kind) && (category == null || it.category == category) && (collection == null || it.collection == collection) &&
            (query.isBlank() || it.name.contains(query, true) || it.category.contains(query, true) || it.blurb.contains(query, true))
    }
}
