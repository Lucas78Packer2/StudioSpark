package com.studiospark.app.core

import com.studiospark.app.dsp.Family
import org.json.JSONObject

/**
 * Guitar & bass builder catalog — every name, outline and finish is an original StudioSpark design (no brand, model or signature names).
 * Sound honesty: every part says how it reaches the speaker. Pickups/woods/strings "voice" the owned recorded samples through the
 * per-voice tone filter; bodies that would need a recording we don't own are flagged as sonic gaps.
 */
enum class VoiceStatus(val label: String) {
    SAMPLED("Owned recorded samples"), VOICED("Owned samples, voiced by the tone filter"), RETUNED("Owned samples, pitch-shifted"),
    MODELED("In-house modeled voice"), VISUAL("Looks only — no tonal change"), GAP("Sonic gap — plays the nearest owned samples")
}
enum class PartCategory(val label: String) { STARTERS("Starter instruments"), BODY("Body styles"), FINISH("Finishes & colors"), PICKUPS("Pickup configurations"), NECK("Necks & fretboards"), HARDWARE("Bridges & hardware"), STRINGS("String sets"), TUNING("Tunings") }

/** One choosable part. [bass]: null = both instruments. [bright] adds to the tone filter (−1…1). [colors] are ARGB for rendering. */
data class Part(val id: String, val name: String, val category: PartCategory, val group: String, val bass: Boolean?, val status: VoiceStatus,
                val bright: Float = 0f, val note: String = "", val colors: List<Long> = emptyList(), val style: String = "", val layout: List<String> = emptyList(),
                val voice: String? = null, val kind: String = "solid", val strings: Int = 0, val scale: Float = 1f)

data class StringBuild(
    val id: String = "sb_${newId()}", val name: String = "My guitar", val bass: Boolean = false,
    val body: String = "contour_dc", val finish: String = "sunburst3", val pickups: String = "sss", val bridge: String = "trem_vintage",
    val hardware: String = "hw_chrome", val guard: String = "guard_white", val fretboard: String = "fb_rosewood", val inlay: String = "in_dots",
    val strings: String = "", val tuning: String = "", val stringCount: Int = 6, val tone: Float = 0.6f, val pickupPos: Int = 0,
    val rigPreset: String = "", val starterId: String = "", val saved: Boolean = false,
)

object StringCatalog {
    val outlines: Map<String, FloatArray> = mapOf(
        "contour_dc" to floatArrayOf(0f, 0f, 0.02f, -0.5f, 0.12f, -0.86f, 0.3f, -0.95f, 0.47f, -0.8f, 0.58f, -0.62f, 0.72f, -0.66f, 0.88f, -0.8f, 0.99f, -0.78f, 0.98f, -0.6f, 0.85f, -0.42f, 0.86f, -0.22f, 1f, -0.2f, 1f, 0.2f, 0.88f, 0.3f, 0.9f, 0.48f, 0.82f, 0.62f, 0.62f, 0.66f, 0.48f, 0.82f, 0.3f, 0.97f, 0.12f, 0.9f, 0.02f, 0.55f),
        "slab_sc" to floatArrayOf(0f, 0f, 0.02f, -0.62f, 0.1f, -0.9f, 0.32f, -0.92f, 0.5f, -0.72f, 0.62f, -0.62f, 0.8f, -0.66f, 0.95f, -0.55f, 1f, -0.3f, 1f, 0.2f, 0.86f, 0.26f, 0.8f, 0.5f, 0.66f, 0.66f, 0.5f, 0.78f, 0.3f, 0.95f, 0.1f, 0.92f, 0.02f, 0.62f),
        "carved_sc" to floatArrayOf(0f, 0f, 0.03f, -0.62f, 0.14f, -0.92f, 0.34f, -0.95f, 0.52f, -0.74f, 0.66f, -0.66f, 0.84f, -0.7f, 0.97f, -0.52f, 1f, -0.25f, 1f, 0.2f, 0.88f, 0.24f, 0.82f, 0.44f, 0.72f, 0.58f, 0.54f, 0.72f, 0.34f, 0.97f, 0.14f, 0.94f, 0.03f, 0.62f),
        "offset" to floatArrayOf(0f, 0.05f, 0.03f, -0.5f, 0.16f, -0.84f, 0.36f, -0.9f, 0.52f, -0.66f, 0.64f, -0.6f, 0.8f, -0.72f, 1.02f, -0.86f, 1.02f, -0.62f, 0.88f, -0.36f, 0.9f, -0.2f, 1f, -0.2f, 1f, 0.2f, 0.9f, 0.3f, 0.9f, 0.5f, 0.78f, 0.64f, 0.56f, 0.66f, 0.42f, 0.86f, 0.22f, 1f, 0.06f, 0.86f),
        "semi_dc" to floatArrayOf(0f, 0f, 0.02f, -0.62f, 0.12f, -0.93f, 0.32f, -0.97f, 0.5f, -0.72f, 0.62f, -0.66f, 0.78f, -0.8f, 0.94f, -0.78f, 0.96f, -0.52f, 0.88f, -0.3f, 1f, -0.2f, 1f, 0.2f, 0.88f, 0.3f, 0.96f, 0.52f, 0.94f, 0.78f, 0.78f, 0.8f, 0.62f, 0.66f, 0.5f, 0.72f, 0.32f, 0.97f, 0.12f, 0.93f, 0.02f, 0.62f),
        "archtop" to floatArrayOf(0f, 0f, 0.03f, -0.68f, 0.16f, -0.97f, 0.38f, -0.97f, 0.54f, -0.72f, 0.64f, -0.66f, 0.82f, -0.76f, 0.97f, -0.56f, 1f, -0.25f, 1f, 0.2f, 0.9f, 0.26f, 0.84f, 0.48f, 0.72f, 0.62f, 0.56f, 0.72f, 0.38f, 1f, 0.16f, 1f, 0.03f, 0.68f),
        "super_dc" to floatArrayOf(0f, 0f, 0.03f, -0.48f, 0.12f, -0.82f, 0.3f, -0.9f, 0.46f, -0.72f, 0.58f, -0.56f, 0.74f, -0.62f, 0.94f, -0.84f, 1.06f, -0.86f, 1.02f, -0.66f, 0.86f, -0.4f, 0.88f, -0.2f, 1f, -0.2f, 1f, 0.2f, 0.9f, 0.28f, 0.96f, 0.5f, 0.98f, 0.66f, 0.86f, 0.64f, 0.62f, 0.62f, 0.46f, 0.8f, 0.28f, 0.94f, 0.1f, 0.86f, 0.02f, 0.52f),
        "wedge" to floatArrayOf(0f, -0.95f, 0.18f, -0.86f, 0.62f, -0.55f, 1f, -0.3f, 1f, 0.2f, 0.62f, 0.5f, 0.18f, 0.86f, 0f, 0.98f, 0.08f, 0.4f, 0.08f, -0.4f),
        "dc24" to floatArrayOf(0f, 0f, 0.02f, -0.55f, 0.12f, -0.9f, 0.32f, -0.95f, 0.5f, -0.74f, 0.62f, -0.62f, 0.78f, -0.7f, 0.95f, -0.76f, 1f, -0.62f, 0.9f, -0.42f, 0.9f, -0.22f, 1f, -0.2f, 1f, 0.2f, 0.9f, 0.28f, 0.95f, 0.5f, 0.88f, 0.62f, 0.66f, 0.64f, 0.5f, 0.76f, 0.32f, 0.96f, 0.12f, 0.91f, 0.02f, 0.56f),
        "dread" to floatArrayOf(0f, 0f, 0.03f, -0.75f, 0.14f, -1f, 0.38f, -0.98f, 0.55f, -0.8f, 0.7f, -0.78f, 0.88f, -0.78f, 0.98f, -0.55f, 1f, -0.2f, 1f, 0.2f, 0.98f, 0.55f, 0.88f, 0.78f, 0.7f, 0.78f, 0.55f, 0.8f, 0.38f, 0.98f, 0.14f, 1f, 0.03f, 0.75f),
        "auditorium" to floatArrayOf(0f, 0f, 0.03f, -0.72f, 0.16f, -0.98f, 0.38f, -0.96f, 0.54f, -0.66f, 0.66f, -0.6f, 0.86f, -0.7f, 0.98f, -0.5f, 1f, -0.2f, 1f, 0.2f, 0.94f, 0.3f, 0.86f, 0.5f, 0.72f, 0.62f, 0.58f, 0.66f, 0.4f, 0.96f, 0.16f, 0.98f, 0.03f, 0.72f),
        "headless_g" to floatArrayOf(0f, -0.6f, 0.1f, -0.74f, 0.5f, -0.66f, 0.7f, -0.6f, 0.96f, -0.7f, 0.98f, -0.52f, 0.88f, -0.3f, 1f, -0.2f, 1f, 0.2f, 0.9f, 0.3f, 0.95f, 0.58f, 0.7f, 0.6f, 0.5f, 0.68f, 0.1f, 0.76f, 0f, 0.6f),
        "split_classic" to floatArrayOf(0f, 0f, 0.02f, -0.55f, 0.12f, -0.88f, 0.3f, -0.94f, 0.46f, -0.76f, 0.58f, -0.6f, 0.74f, -0.64f, 0.9f, -0.82f, 1f, -0.8f, 0.98f, -0.6f, 0.86f, -0.4f, 0.88f, -0.2f, 1f, -0.2f, 1f, 0.2f, 0.88f, 0.3f, 0.9f, 0.5f, 0.8f, 0.62f, 0.6f, 0.64f, 0.46f, 0.82f, 0.3f, 0.96f, 0.12f, 0.9f, 0.02f, 0.56f),
        "offset_bass" to floatArrayOf(0f, 0.06f, 0.03f, -0.5f, 0.14f, -0.84f, 0.34f, -0.92f, 0.5f, -0.7f, 0.62f, -0.6f, 0.8f, -0.76f, 1.02f, -0.9f, 1.02f, -0.64f, 0.88f, -0.4f, 0.9f, -0.2f, 1f, -0.2f, 1f, 0.2f, 0.9f, 0.3f, 0.92f, 0.52f, 0.8f, 0.62f, 0.6f, 0.64f, 0.44f, 0.86f, 0.24f, 1f, 0.06f, 0.86f),
        "modern_bass" to floatArrayOf(0f, 0f, 0.03f, -0.5f, 0.12f, -0.82f, 0.3f, -0.88f, 0.46f, -0.7f, 0.58f, -0.56f, 0.76f, -0.66f, 0.98f, -0.92f, 1.1f, -0.9f, 1.02f, -0.66f, 0.88f, -0.38f, 0.88f, -0.2f, 1f, -0.2f, 1f, 0.2f, 0.9f, 0.3f, 0.96f, 0.5f, 1f, 0.64f, 0.86f, 0.62f, 0.62f, 0.6f, 0.46f, 0.8f, 0.28f, 0.92f, 0.1f, 0.84f, 0.02f, 0.5f),
        "violin_bass" to floatArrayOf(0f, 0f, 0.03f, -0.62f, 0.14f, -0.84f, 0.34f, -0.8f, 0.46f, -0.52f, 0.58f, -0.5f, 0.72f, -0.68f, 0.9f, -0.7f, 1f, -0.45f, 1f, 0.45f, 0.9f, 0.7f, 0.72f, 0.68f, 0.58f, 0.5f, 0.46f, 0.52f, 0.34f, 0.8f, 0.14f, 0.84f, 0.03f, 0.62f),
        "singlecut_bass" to floatArrayOf(0f, 0f, 0.03f, -0.6f, 0.14f, -0.9f, 0.34f, -0.92f, 0.52f, -0.72f, 0.66f, -0.62f, 0.86f, -0.7f, 0.98f, -0.5f, 1f, -0.22f, 1f, 0.2f, 0.88f, 0.26f, 0.84f, 0.46f, 0.7f, 0.6f, 0.52f, 0.7f, 0.32f, 0.95f, 0.12f, 0.92f, 0.02f, 0.6f),
        "headless_b" to floatArrayOf(0f, -0.6f, 0.1f, -0.76f, 0.5f, -0.66f, 0.72f, -0.6f, 0.98f, -0.74f, 1f, -0.54f, 0.88f, -0.3f, 1f, -0.2f, 1f, 0.2f, 0.9f, 0.3f, 0.96f, 0.6f, 0.72f, 0.6f, 0.5f, 0.68f, 0.1f, 0.78f, 0f, 0.6f),
        "acoustic_bass" to floatArrayOf(0f, 0f, 0.03f, -0.75f, 0.14f, -1f, 0.38f, -0.98f, 0.55f, -0.8f, 0.7f, -0.78f, 0.88f, -0.78f, 0.98f, -0.55f, 1f, -0.2f, 1f, 0.2f, 0.98f, 0.55f, 0.88f, 0.78f, 0.7f, 0.78f, 0.55f, 0.8f, 0.38f, 0.98f, 0.14f, 1f, 0.03f, 0.75f),
    )

    private fun body(id: String, name: String, group: String, bass: Boolean, status: VoiceStatus, bright: Float, kind: String, note: String, strings: Int = if (bass) 4 else 6, voice: String? = null, scale: Float = 1f) =
        Part(id, name, PartCategory.BODY, group, bass, status, bright, note, kind = kind, strings = strings, voice = voice, scale = scale)
    val bodies = listOf(
        body("contour_dc", "Contour Double-Cut", "Solid body", false, VoiceStatus.SAMPLED, 0.05f, "solid", "Balanced, articulate solid body"),
        body("slab_sc", "Slab Single-Cut", "Solid body", false, VoiceStatus.VOICED, 0.12f, "solid", "Bright, snappy slab"),
        body("carved_sc", "Carved-Top Single-Cut", "Solid body", false, VoiceStatus.VOICED, -0.1f, "carved", "Thick, sustaining carved top"),
        body("offset", "Offset Waist", "Solid body", false, VoiceStatus.VOICED, 0.04f, "solid", "Jangly offset"),
        body("dc24", "Twin-Horn 24", "Solid body", false, VoiceStatus.VOICED, 0f, "carved", "Versatile carved double-cut"),
        body("super_dc", "Pointed Double-Cut", "Solid body", false, VoiceStatus.VOICED, 0.06f, "solid", "Fast, high-gain friendly", strings = 6),
        body("super_dc7", "Pointed Double-Cut 7", "Extended range", false, VoiceStatus.RETUNED, 0.02f, "solid", "Seven strings: low B is the owned low-E sample pitched down", strings = 7),
        body("baritone", "Long-Scale Baritone", "Extended range", false, VoiceStatus.RETUNED, -0.08f, "solid", "Baritone B tuning — owned samples pitched down", scale = 1.12f),
        body("wedge", "Arrow Wedge", "Solid body", false, VoiceStatus.VOICED, 0.04f, "solid", "Aggressive wedge"),
        body("headless_g", "Headless Travel", "Solid body", false, VoiceStatus.VOICED, 0.05f, "headless", "Compact headless body"),
        body("semi_dc", "Semi-Hollow Double-Cut", "Semi & hollow", false, VoiceStatus.VOICED, -0.06f, "semi", "Airy semi-hollow with center block"),
        body("archtop", "Full Hollow Archtop", "Semi & hollow", false, VoiceStatus.VOICED, -0.18f, "hollow", "Warm jazz archtop"),
        body("dread", "Spruce Dreadnought", "Acoustic", false, VoiceStatus.MODELED, 0.1f, "acoustic", "In-house modeled acoustic voice (no acoustic recordings owned)", voice = "nylon_like"),
        body("auditorium", "Cutaway Auditorium", "Acoustic", false, VoiceStatus.MODELED, 0.12f, "acoustic", "In-house modeled acoustic voice", voice = "nylon_like"),
        body("jumbo12", "Twelve-String Jumbo", "Acoustic", false, VoiceStatus.GAP, 0.15f, "acoustic", "GAP: no octave-course recordings — plays as a 6-string modeled acoustic", voice = "nylon_like"),
        body("split_classic", "Split-Coil Classic", "Solid body", true, VoiceStatus.SAMPLED, 0f, "solid", "Round, punchy classic bass"),
        body("offset_bass", "Offset Waist Bass", "Solid body", true, VoiceStatus.VOICED, 0.08f, "solid", "Growly offset bass"),
        body("modern_bass", "Modern Active Bass", "Solid body", true, VoiceStatus.VOICED, 0.12f, "solid", "Hi-fi modern bass"),
        body("modern_bass5", "Modern Active Five", "Extended range", true, VoiceStatus.SAMPLED, 0.1f, "solid", "Five strings with owned low-B samples", strings = 5),
        body("violin_bass", "Short-Scale Violin Bass", "Semi & hollow", true, VoiceStatus.VOICED, -0.15f, "hollow", "Thumpy short-scale hollow", scale = 0.8f),
        body("singlecut_bass", "Single-Cut Boutique Bass", "Solid body", true, VoiceStatus.VOICED, 0.02f, "carved", "Boutique single-cut"),
        body("headless_b", "Headless Bass", "Solid body", true, VoiceStatus.VOICED, 0.06f, "headless", "Compact headless bass"),
        body("fretless", "Fretless Offset", "Specialty", true, VoiceStatus.GAP, -0.05f, "solid", "GAP: fretted samples — no fretless glide/mwah recordings"),
        body("acoustic_bass", "Acoustic Bass", "Specialty", true, VoiceStatus.GAP, -0.2f, "acoustic", "GAP: no acoustic bass recordings — darker voicing of owned electric bass"),
    )
    private fun fin(id: String, name: String, group: String, style: String, vararg c: Long) = Part(id, name, PartCategory.FINISH, group, null, VoiceStatus.VISUAL, colors = c.toList(), style = style)
    val finishes = listOf(
        fin("sunburst3", "Three-Tone Sunburst", "Bursts", "burst", 0xFFE9B04AL, 0xFFA2401BL, 0xFF1A0C06L),
        fin("sunburst2", "Tobacco Burst", "Bursts", "burst", 0xFFD49A3CL, 0xFF5A2A10L, 0xFF140A05L),
        fin("cherryburst", "Cherry Burst", "Bursts", "burst", 0xFFE7A25AL, 0xFFB01C1CL, 0xFF3A0606L),
        fin("honeyburst", "Honey Burst", "Bursts", "burst", 0xFFF4CF7AL, 0xFFC27A2AL, 0xFF6A3A12L),
        fin("blueburst", "Ocean Burst", "Bursts", "burst", 0xFF7FC8E8L, 0xFF1D5D9BL, 0xFF06142CL),
        fin("jet", "Jet Black", "Solid colors", "solid", 0xFF141416L, 0xFF050506L),
        fin("arctic", "Arctic White", "Solid colors", "solid", 0xFFF2F0E8L, 0xFFCFCBC0L),
        fin("vintage_cream", "Vintage Cream", "Solid colors", "solid", 0xFFEDE0B8L, 0xFFC9B888L),
        fin("fire_red", "Fire Red", "Solid colors", "solid", 0xFFD0201CL, 0xFF7A0A08L),
        fin("surf_green", "Seafoam", "Solid colors", "solid", 0xFF9ED8C0L, 0xFF5FA088L),
        fin("lake_blue", "Lake Blue", "Solid colors", "solid", 0xFF6FA6CFL, 0xFF35628AL),
        fin("candy_apple", "Candy Red Metallic", "Metallics & sparkles", "metal", 0xFFE0283AL, 0xFF6A0612L),
        fin("gold_top", "Gold Top", "Metallics & sparkles", "metal", 0xFFE8C766L, 0xFF8C6A20L),
        fin("silver_flake", "Silver Flake", "Metallics & sparkles", "metal", 0xFFD8DCE2L, 0xFF6E747CL),
        fin("purple_sparkle", "Violet Sparkle", "Metallics & sparkles", "metal", 0xFF9A5ACFL, 0xFF3A1268L),
        fin("natural_ash", "Natural Ash", "Natural woods", "wood", 0xFFE6CFA2L, 0xFFB89260L),
        fin("natural_mahog", "Natural Mahogany", "Natural woods", "wood", 0xFF8A4420L, 0xFF4A200CL),
        fin("walnut", "Oiled Walnut", "Natural woods", "wood", 0xFF6A4228L, 0xFF3A2212L),
        fin("quilt_blue", "Blue Quilt Top", "Figured tops", "quilt", 0xFF3D8FD8L, 0xFF0E2E66L),
        fin("flame_amber", "Amber Flame Top", "Figured tops", "flame", 0xFFF0A03AL, 0xFF8A3A0EL),
        fin("flame_violet", "Violet Flame Top", "Figured tops", "flame", 0xFFB06AD8L, 0xFF3C1060L),
        fin("relic_red", "Road-Worn Red", "Aged", "relic", 0xFFB82A22L, 0xFFD8B880L),
        fin("relic_black", "Road-Worn Black", "Aged", "relic", 0xFF1C1C1EL, 0xFFC8A870L),
    )
    private fun pu(id: String, name: String, group: String, bass: Boolean, bright: Float, layout: List<String>, status: VoiceStatus = VoiceStatus.VOICED, note: String = "") =
        Part(id, name, PartCategory.PICKUPS, group, bass, status, bright, note, layout = layout)
    val pickups = listOf(
        Part("none", "No pickups yet", PartCategory.PICKUPS, "Empty", null, VoiceStatus.VOICED, -0.12f, "Unplugged string sound — owned samples, darker filter", layout = emptyList()),
        pu("sss", "Three Single-Coils", "Single-coil", false, 0.12f, listOf("S", "S", "S"), VoiceStatus.SAMPLED, "Glassy, five positions"),
        pu("ss", "Two Single-Coils", "Single-coil", false, 0.15f, listOf("S", "S"), note = "Twang and spank"),
        pu("p90x2", "Twin Soapbars", "Single-coil", false, 0.02f, listOf("P90", "P90"), note = "Gritty, fat single-coils"),
        pu("hh", "Twin Humbuckers", "Humbucker", false, -0.08f, listOf("H", "H"), note = "Warm, high output"),
        pu("hss", "Humbucker + Two Singles", "Mixed", false, 0.04f, listOf("S", "S", "H")),
        pu("hsh", "Humbucker / Single / Humbucker", "Mixed", false, 0f, listOf("H", "S", "H")),
        pu("h", "Single Bridge Humbucker", "Humbucker", false, -0.02f, listOf("H"), note = "One knob, all attitude"),
        pu("active_hh", "Active Twin Humbuckers", "Active", false, 0.06f, listOf("AH", "AH"), note = "Tight, compressed high gain"),
        pu("mini_hh", "Twin Mini-Humbuckers", "Humbucker", false, 0.06f, listOf("MH", "MH")),
        pu("float", "Floating Neck Pickup", "Archtop", false, -0.2f, listOf("FH")),
        pu("piezo", "Under-Saddle Piezo", "Acoustic", false, 0.2f, listOf("PZ"), VoiceStatus.GAP, "GAP: no piezo recordings — bright voicing only"),
        pu("mag_soundhole", "Soundhole Magnetic", "Acoustic", false, 0.05f, listOf("SH")),
        pu("split", "Split-Coil", "Bass", true, -0.02f, listOf("P"), VoiceStatus.SAMPLED, "Round and punchy"),
        pu("jj", "Twin Bar Single-Coils", "Bass", true, 0.12f, listOf("J", "J"), note = "Growl and bite"),
        pu("pj", "Split-Coil + Bar", "Bass", true, 0.05f, listOf("P", "J")),
        pu("mm", "Big Humbucker", "Bass", true, 0.14f, listOf("MM"), note = "Aggressive active hum"),
        pu("soapbar2", "Twin Soapbar Active", "Bass", true, 0.1f, listOf("SB", "SB")),
        pu("bass_piezo", "Bass Piezo Bridge", "Bass", true, 0.15f, listOf("PZ"), VoiceStatus.GAP, "GAP: no piezo bass recordings"),
    )
    private fun nk(id: String, name: String, group: String, bright: Float, style: String, vararg c: Long, status: VoiceStatus = VoiceStatus.VISUAL) = Part(id, name, PartCategory.NECK, group, null, status, bright, colors = c.toList(), style = style)
    val necks = listOf(
        nk("fb_rosewood", "Dark Rosewood-Style Board", "Fretboard", -0.03f, "board", 0xFF3A2216L, 0xFF24140CL, status = VoiceStatus.VOICED),
        nk("fb_maple", "Maple Board", "Fretboard", 0.05f, "board", 0xFFE8C88CL, 0xFFC9A066L, status = VoiceStatus.VOICED),
        nk("fb_ebony", "Ebony Board", "Fretboard", 0.02f, "board", 0xFF161210L, 0xFF0A0806L, status = VoiceStatus.VOICED),
        nk("fb_roasted", "Roasted Maple Board", "Fretboard", 0.04f, "board", 0xFFA86A34L, 0xFF7A4A20L, status = VoiceStatus.VOICED),
        nk("fb_pau", "Pale Hardwood Board", "Fretboard", 0f, "board", 0xFF6A4A30L, 0xFF4A3020L),
        nk("in_dots", "Pearl Dots", "Inlays", 0f, "dots", 0xFFEDE6D6L),
        nk("in_blocks", "Pearl Blocks", "Inlays", 0f, "blocks", 0xFFEDE6D6L),
        nk("in_trap", "Trapezoids", "Inlays", 0f, "trap", 0xFFE4DCC8L),
        nk("in_shark", "Shark Fins", "Inlays", 0f, "shark", 0xFFEDE6D6L),
        nk("in_birds", "Original Swallows", "Inlays", 0f, "birds", 0xFFDDE8F0L),
        nk("in_none", "Clean Board (side dots)", "Inlays", 0f, "none"),
        nk("in_lumi", "Glow Side Dots", "Inlays", 0f, "lumi", 0xFF7CFFD0L),
    )
    private fun hw(id: String, name: String, group: String, bright: Float = 0f, style: String = "", vararg c: Long, status: VoiceStatus = VoiceStatus.VISUAL, bass: Boolean? = null) = Part(id, name, PartCategory.HARDWARE, group, bass, status, bright, colors = c.toList(), style = style)
    val hardware = listOf(
        hw("trem_vintage", "Six-Screw Vibrato", "Bridge", 0.03f, "trem", status = VoiceStatus.VOICED, bass = false),
        hw("trem_locking", "Double-Locking Vibrato", "Bridge", 0.02f, "locking", status = VoiceStatus.VOICED, bass = false),
        hw("tom_stop", "Fixed Bridge + Stop Tail", "Bridge", -0.02f, "tom", status = VoiceStatus.VOICED, bass = false),
        hw("hardtail", "Hardtail Through-Body", "Bridge", 0.05f, "hardtail", status = VoiceStatus.VOICED, bass = false),
        hw("ashtray", "Three-Saddle Plate Bridge", "Bridge", 0.08f, "ashtray", status = VoiceStatus.VOICED, bass = false),
        hw("trapeze", "Trapeze Tail + Floating Bridge", "Bridge", -0.06f, "trapeze", status = VoiceStatus.VOICED, bass = false),
        hw("pin_bridge", "Pin Bridge (acoustic)", "Bridge", 0.03f, "pin", status = VoiceStatus.VOICED),
        hw("vibrato_arm", "Arched-Arm Vibrato Tail", "Bridge", -0.02f, "armtail", status = VoiceStatus.VOICED, bass = false),
        hw("bass_vintage", "Bent-Plate Bass Bridge", "Bridge", 0f, "bassplate", status = VoiceStatus.VOICED, bass = true),
        hw("bass_heavy", "High-Mass Bass Bridge", "Bridge", 0.05f, "bassheavy", status = VoiceStatus.VOICED, bass = true),
        hw("hw_chrome", "Chrome Hardware", "Hardware finish", 0f, "metal", 0xFFE6EAEEL, 0xFF8A9096L),
        hw("hw_nickel", "Aged Nickel Hardware", "Hardware finish", 0f, "metal", 0xFFD6D0C0L, 0xFF7E786AL),
        hw("hw_gold", "Gold Hardware", "Hardware finish", 0f, "metal", 0xFFF2D27AL, 0xFF9A7428L),
        hw("hw_black", "Black Hardware", "Hardware finish", 0f, "metal", 0xFF3A3C40L, 0xFF0E0F10L),
        hw("guard_white", "White Pickguard", "Pickguard", 0f, "guard", 0xFFF4F2EAL),
        hw("guard_mint", "Mint Pickguard", "Pickguard", 0f, "guard", 0xFFE8E4C8L),
        hw("guard_tort", "Tortoise Pickguard", "Pickguard", 0f, "tort", 0xFF6A2E12L, 0xFF2A1006L),
        hw("guard_black", "Black Pickguard", "Pickguard", 0f, "guard", 0xFF141414L),
        hw("guard_pearl", "Pearloid Pickguard", "Pickguard", 0f, "pearl", 0xFFF2EEE6L, 0xFFCFC8BEL),
        hw("guard_none", "No Pickguard", "Pickguard", 0f, "none"),
    )
    private fun ss(set: StringSet, bass: Boolean) = Part("str_" + set.name.lowercase().filter { it.isLetterOrDigit() }, set.name, PartCategory.STRINGS, if (bass) "Bass strings" else "Guitar strings", bass, VoiceStatus.VOICED, (set.brightness - 0.55f) * 0.5f, set.gauges)
    val stringSets = StringSets.guitar.map { ss(it, false) } + StringSets.bass.map { ss(it, true) }
    val tunings = Tunings.guitar.map { Part("tun_" + it.name, it.name, PartCategory.TUNING, "Guitar", false, VoiceStatus.RETUNED, note = it.midi.joinToString(" ") { m -> listOf("C","C#","D","Eb","E","F","F#","G","Ab","A","Bb","B")[m % 12] + (m / 12 - 1) }) } +
        Tunings.bass.map { Part("tun_" + it.name, it.name, PartCategory.TUNING, "Bass", true, VoiceStatus.RETUNED, note = it.midi.joinToString(" ") { m -> listOf("C","C#","D","Eb","E","F","F#","G","Ab","A","Bb","B")[m % 12] + (m / 12 - 1) }) }

    val all: List<Part> get() = bodies + finishes + pickups + necks + hardware + stringSets
    fun part(id: String) = all.firstOrNull { it.id == id }
    fun outline(body: String) = outlines[body] ?: outlines[when (body) { "super_dc7" -> "super_dc"; "baritone" -> "contour_dc"; "jumbo12" -> "dread"; "modern_bass5" -> "modern_bass"; "fretless" -> "offset_bass"; else -> if (body.contains("bass")) "split_classic" else "contour_dc" }]!!
    fun forInstrument(cat: PartCategory, bass: Boolean): List<Part> = when (cat) {
        PartCategory.BODY -> bodies; PartCategory.FINISH -> finishes; PartCategory.PICKUPS -> pickups; PartCategory.NECK -> necks
        PartCategory.HARDWARE -> hardware; PartCategory.STRINGS -> stringSets; PartCategory.TUNING -> tunings; PartCategory.STARTERS -> emptyList()
    }.filter { it.bass == null || it.bass == bass }
    fun groups(cat: PartCategory, bass: Boolean) = forInstrument(cat, bass).map { it.group }.distinct()

    fun defaultTuning(body: String, bass: Boolean, strings: Int): String = when {
        bass && strings >= 5 -> Tunings.bass.first { it.midi.size == 5 }.name
        bass -> Tunings.bass[0].name
        body == "baritone" -> Tunings.guitar.first { it.name.startsWith("Baritone") }.name
        strings == 7 -> Tunings.guitar.first { it.midi.size == 7 }.name
        else -> Tunings.guitar[0].name
    }

    /** Applies a catalog part to a build (body changes keep the string count/tuning valid). */
    fun apply(b: StringBuild, p: Part): StringBuild = when (p.category) {
        PartCategory.BODY -> { val n = p.strings; val tun = Tunings.find(b.tuning); val keep = tun != null && tun.midi.size == n && p.id != "baritone" && b.body != "baritone"
            val pick = if (p.kind == "acoustic") (if (b.bass) "bass_piezo" else "mag_soundhole") else if (b.pickups == "mag_soundhole" || b.pickups == "piezo") "hh" else b.pickups
            val bridge = if (p.kind == "acoustic") "pin_bridge" else if (b.bridge == "pin_bridge") (if (b.bass) "bass_vintage" else "hardtail") else b.bridge
            b.copy(body = p.id, stringCount = n, tuning = if (keep) b.tuning else defaultTuning(p.id, b.bass, n), pickups = pick, bridge = bridge) }
        PartCategory.FINISH -> b.copy(finish = p.id)
        PartCategory.PICKUPS -> b.copy(pickups = p.id, pickupPos = 0)
        PartCategory.NECK -> if (p.group == "Inlays") b.copy(inlay = p.id) else b.copy(fretboard = p.id)
        PartCategory.HARDWARE -> when (p.group) { "Bridge" -> b.copy(bridge = p.id); "Pickguard" -> b.copy(guard = p.id); else -> b.copy(hardware = p.id) }
        PartCategory.STRINGS -> b.copy(strings = p.name)
        PartCategory.TUNING -> { val t = Tunings.find(p.name); if (t != null) b.copy(tuning = p.name, stringCount = t.midi.size) else b }
        PartCategory.STARTERS -> b
    }

    /** Pickup selector positions for a layout (e.g. 5-way for three singles). */
    fun positions(b: StringBuild): List<String> { val l = part(b.pickups)?.layout ?: listOf("H"); return when (l.size) {
        0 -> listOf("Unplugged")
        1 -> listOf(if (b.bass) "Pickup" else "Bridge"); 2 -> if (b.bass) listOf("Both", "Neck", "Bridge") else listOf("Bridge", "Both", "Neck")
        else -> listOf("Bridge", "Bridge + middle", "Middle", "Middle + neck", "Neck") } }

    data class Sound(val voice: String, val brightness: Float, val status: VoiceStatus, val notes: List<String>)
    /** How this build sounds, honestly: which owned voice plays, the tone-filter brightness it gets, and any sonic gaps. */
    fun sound(b: StringBuild): Sound {
        val body = part(b.body); val pick = part(b.pickups); val pos = positions(b)
        val posBright = if (pos.size <= 1) 0.05f else { val i = b.pickupPos.coerceIn(0, pos.size - 1); val name = pos[i]; when { name == "Both" -> 0f; name.startsWith("Bridge") && name.contains("+") -> 0.05f; name.startsWith("Bridge") -> 0.12f; name.startsWith("Middle +") -> -0.02f; name == "Middle" -> 0.02f; else -> -0.12f } }
        val set = StringSets.find(b.strings); val strBright = if (set == null) 0f else (set.brightness - 0.55f) * 0.5f
        val br = (0.5f + (body?.bright ?: 0f) + (pick?.bright ?: 0f) + (part(b.fretboard)?.bright ?: 0f) + (part(b.bridge)?.bright ?: 0f) + strBright + posBright + (b.tone - 0.6f) * 0.8f).coerceIn(0.05f, 1f)
        val voice = body?.voice ?: if (b.bass) "bass" else "guitar"
        val parts = listOfNotNull(body, pick, part(b.bridge), part(b.fretboard))
        val gaps = parts.filter { it.status == VoiceStatus.GAP }.map { "${it.name}: ${it.note.removePrefix("GAP: ")}" }
        val status = when { gaps.isNotEmpty() -> VoiceStatus.GAP; voice == "nylon_like" -> VoiceStatus.MODELED; parts.any { it.status == VoiceStatus.RETUNED } -> VoiceStatus.RETUNED; br in 0.42f..0.68f && pick?.status == VoiceStatus.SAMPLED -> VoiceStatus.SAMPLED; else -> VoiceStatus.VOICED }
        return Sound(voice, br, status, gaps)
    }

    fun instrumentId(b: StringBuild) = "inst_${b.id}"
    /** The build as a first-class instrument: usable on tracks, in the jam room, upload/place and every gallery. */
    fun toInstrument(b: StringBuild): InstrumentDef { val s = sound(b)
        return InstrumentDef(instrumentId(b), b.name, if (b.bass) Family.BASS else Family.GUITAR, Surface.STRINGS, if (s.voice == "nylon_like") SoundSource.RENDERED else SoundSource.RECORDED_CC0,
            s.voice, "build:${b.id}", if (b.bass) "My basses" else "My guitars", custom = true, tuning = b.tuning.ifEmpty { defaultTuning(b.body, b.bass, b.stringCount) },
            strings = b.strings.ifEmpty { if (b.bass) StringSets.bass[0].name else StringSets.guitar[0].name }, baseId = b.id, brightness = s.brightness) }

    fun blank(bass: Boolean) = StringBuild(id = "blank_${if (bass) "b" else "g"}", name = if (bass) "My bass" else "My guitar", bass = bass,
        body = if (bass) "split_classic" else "contour_dc", finish = "natural_ash", pickups = "none", bridge = if (bass) "bass_vintage" else "hardtail", hardware = "hw_chrome", guard = "guard_none",
        fretboard = "fb_maple", inlay = "in_none", strings = if (bass) StringSets.bass[0].name else StringSets.guitar[0].name, tuning = if (bass) Tunings.bass[0].name else Tunings.guitar[0].name, stringCount = if (bass) 4 else 6, starterId = "blank")

    private fun st(id: String, name: String, bass: Boolean, body: String, finish: String, pickups: String, bridge: String, hw: String, guard: String, fb: String, inlay: String, rig: String, strings: Int = if (bass) 4 else 6, setIdx: Int = 0) =
        StringBuild(id = id, name = name, bass = bass, body = body, finish = finish, pickups = pickups, bridge = bridge, hardware = hw, guard = guard, fretboard = fb, inlay = inlay,
            strings = (if (bass) StringSets.bass else StringSets.guitar)[setIdx].name, tuning = defaultTuning(body, bass, strings), stringCount = strings, rigPreset = rig, starterId = id)
    val guitarStarters = listOf(
        st("g_glass", "Glass Contour", false, "contour_dc", "sunburst3", "sss", "trem_vintage", "hw_chrome", "guard_white", "fb_rosewood", "in_dots", "Glass Clean + Chorus"),
        st("g_twang", "Butterscotch Slab", false, "slab_sc", "vintage_cream", "ss", "ashtray", "hw_nickel", "guard_black", "fb_maple", "in_dots", "Tweed Blues", setIdx = 2),
        st("g_carved", "Gold Carved Top", false, "carved_sc", "gold_top", "hh", "tom_stop", "hw_nickel", "guard_none", "fb_rosewood", "in_trap", "Citrus Crunch", setIdx = 2),
        st("g_shred", "Violet Shred 24", false, "super_dc", "purple_sparkle", "hsh", "trem_locking", "hw_black", "guard_none", "fb_ebony", "in_shark", "Modern Metal Rhythm", setIdx = 1),
        st("g_seven", "Graphite Seven", false, "super_dc7", "jet", "active_hh", "hardtail", "hw_black", "guard_none", "fb_ebony", "in_lumi", "Modern Metal Rhythm", strings = 7, setIdx = 3),
        st("g_quilt", "Blue Quilt Twin-Horn", false, "dc24", "quilt_blue", "hh", "trem_vintage", "hw_nickel", "guard_none", "fb_rosewood", "in_birds", "Fuzz Wah Lead"),
        st("g_semi", "Cherry Semi-Hollow", false, "semi_dc", "cherryburst", "hh", "tom_stop", "hw_chrome", "guard_tort", "fb_rosewood", "in_blocks", "Tweed Blues"),
        st("g_jazz", "Honey Archtop", false, "archtop", "honeyburst", "float", "trapeze", "hw_gold", "guard_tort", "fb_ebony", "in_blocks", "Glass Clean + Chorus", setIdx = 4),
        st("g_offset", "Seafoam Offset", false, "offset", "surf_green", "ss", "trem_vintage", "hw_chrome", "guard_tort", "fb_rosewood", "in_dots", "Glass Clean + Chorus"),
        st("g_bari", "Smoke Baritone", false, "baritone", "relic_black", "hh", "hardtail", "hw_nickel", "guard_black", "fb_roasted", "in_dots", "Modern Metal Rhythm", setIdx = 3),
        st("g_acoustic", "Spruce Dreadnought", false, "dread", "natural_ash", "mag_soundhole", "pin_bridge", "hw_gold", "guard_tort", "fb_rosewood", "in_dots", "", setIdx = 5),
    )
    val bassStarters = listOf(
        st("b_classic", "Classic Split-Coil", true, "split_classic", "sunburst3", "split", "bass_vintage", "hw_chrome", "guard_tort", "fb_rosewood", "in_dots", "Tube Bass DI+Amp"),
        st("b_offset", "Natural Offset Bass", true, "offset_bass", "natural_ash", "jj", "bass_vintage", "hw_chrome", "guard_black", "fb_maple", "in_blocks", "Growl Bass"),
        st("b_modern", "Modern Active", true, "modern_bass", "flame_amber", "mm", "bass_heavy", "hw_black", "guard_none", "fb_ebony", "in_none", "Growl Bass"),
        st("b_five", "Ebony Five", true, "modern_bass5", "jet", "soapbar2", "bass_heavy", "hw_black", "guard_none", "fb_ebony", "in_lumi", "Growl Bass", strings = 5),
        st("b_violin", "Violin Bass", true, "violin_bass", "sunburst2", "split", "bass_vintage", "hw_chrome", "guard_none", "fb_rosewood", "in_dots", "Tube Bass DI+Amp", setIdx = 1),
        st("b_pj", "Fire Red PJ", true, "split_classic", "fire_red", "pj", "bass_vintage", "hw_chrome", "guard_white", "fb_rosewood", "in_dots", "Tube Bass DI+Amp"),
        st("b_fretless", "Fretless Walnut", true, "fretless", "walnut", "jj", "bass_vintage", "hw_nickel", "guard_tort", "fb_ebony", "in_none", "Tube Bass DI+Amp", setIdx = 1),
    )
    fun starters(bass: Boolean) = if (bass) bassStarters else guitarStarters
    fun starter(id: String) = (guitarStarters + bassStarters + listOf(blank(false), blank(true))).firstOrNull { it.id == id }
    /** Every gap across the catalog (for the report and the UI). */
    fun gaps() = all.filter { it.status == VoiceStatus.GAP }

    fun toJson(b: StringBuild) = JSONObject().apply { put("id", b.id); put("name", b.name); put("bass", b.bass); put("body", b.body); put("finish", b.finish); put("pu", b.pickups); put("bridge", b.bridge)
        put("hw", b.hardware); put("guard", b.guard); put("fb", b.fretboard); put("inlay", b.inlay); put("strings", b.strings); put("tuning", b.tuning); put("n", b.stringCount); put("tone", b.tone.toDouble()); put("pos", b.pickupPos); put("rig", b.rigPreset); put("starter", b.starterId); put("saved", b.saved) }
    fun fromJson(o: JSONObject) = StringBuild(o.getString("id"), o.optString("name", "My guitar"), o.optBoolean("bass"), o.optString("body", "contour_dc"), o.optString("finish", "sunburst3"), o.optString("pu", "sss"), o.optString("bridge", "hardtail"),
        o.optString("hw", "hw_chrome"), o.optString("guard", "guard_none"), o.optString("fb", "fb_rosewood"), o.optString("inlay", "in_dots"), o.optString("strings"), o.optString("tuning"), o.optInt("n", 6), o.optDouble("tone", 0.6).toFloat(), o.optInt("pos"), o.optString("rig"), o.optString("starter"), o.optBoolean("saved"))
}
