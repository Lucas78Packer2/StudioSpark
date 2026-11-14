package com.studiospark.app.core

/**
 * Visual + sounding drum catalog for the Drum Kit tab. Original names only (type + size + finish) — no brand, model or signature names.
 * Every sounding entry resolves to an owned in-house sample in assets/original_drums (see [SoundStatus]); entries with no honest
 * sample are flagged [SoundStatus.GAP] and stay silent rather than borrowing an unrelated sound.
 */
enum class DrumCategory(val label: String) { CYMBALS("Cymbals"), DRUMS("Drums"), PEDALS("Bass pedals"), HARDWARE("Accessories & hardware"), STARTERS("Starter kits") }

enum class SoundStatus(val label: String) {
    OWN("Own in-house sample"), RETUNED("Shared sample, size-retuned"), TRIGGER("Plays the kit's bass drum"),
    SILENT("Hardware — no sound"), GAP("Gap — no matching sample yet (silent)")
}

enum class Shape { KICK, SNARE, TOM, FLOOR_TOM, HIHAT, CRASH, RIDE, CHINA, SPLASH, STACK, BELL, COWBELL, TAMBOURINE, BLOCK, GONG,
    PEDAL_SINGLE, PEDAL_DOUBLE, THRONE, STAND, BOOM, HAT_STAND, SNARE_STAND, RACK, CLAMP, RUG, BEATER, KEY, CHIMES }

/** Visual finish. [kind]: lacquer, sparkle, pearl, satin, wood, metal (snare shells), and for cymbals brilliant/traditional/dark/raw/hammered/brass. */
data class Finish(val id: String, val name: String, val base: Long, val accent: Long, val kind: String)

object Finishes {
    val shells = listOf(
        Finish("natural_maple", "Natural maple gloss", 0xFFC8924F, 0xFFE9C38A, "wood"), Finish("walnut_satin", "Walnut satin", 0xFF5A3A22, 0xFF8A6040, "wood"),
        Finish("black_sparkle", "Black sparkle", 0xFF141416, 0xFF9EA3AA, "sparkle"), Finish("red_sparkle", "Red sparkle", 0xFF8E1218, 0xFFFF7A6E, "sparkle"),
        Finish("blue_sparkle", "Blue sparkle", 0xFF123C8C, 0xFF8FB8FF, "sparkle"), Finish("silver_sparkle", "Silver sparkle", 0xFF8E9399, 0xFFF2F4F6, "sparkle"),
        Finish("gold_sparkle", "Champagne sparkle", 0xFF9C7A3C, 0xFFF5DFA0, "sparkle"), Finish("piano_black", "Piano black", 0xFF0B0B0C, 0xFF4A4C50, "lacquer"),
        Finish("arctic_white", "Arctic white", 0xFFE9E9E6, 0xFFFFFFFF, "lacquer"), Finish("white_pearl", "Vintage white marine pearl", 0xFFE6E1D3, 0xFFFFFBEF, "pearl"),
        Finish("burgundy_fade", "Burgundy fade", 0xFF4A0D16, 0xFFB02A3A, "lacquer"), Finish("ocean_burst", "Ocean blue burst", 0xFF0D2E4A, 0xFF3D8FC4, "lacquer"),
        Finish("emerald", "Emerald lacquer", 0xFF0E4A34, 0xFF39A47A, "lacquer"), Finish("copper_metal", "Copper metallic", 0xFF7A3E1E, 0xFFE09A6A, "lacquer"),
        Finish("brushed_steel", "Brushed steel", 0xFF8C9096, 0xFFE4E7EA, "metal"), Finish("hammered_brass", "Hammered brass", 0xFF8A6A2A, 0xFFE8C878, "metal"),
        Finish("bronze_shell", "Cast bronze", 0xFF6E4A22, 0xFFC8955A, "metal"),
    )
    val cymbals = listOf(
        Finish("brilliant", "Brilliant", 0xFFC9A24A, 0xFFFBE6A6, "brilliant"), Finish("traditional", "Traditional bronze", 0xFFAE843E, 0xFFE3C17C, "traditional"),
        Finish("dark", "Dark lathed", 0xFF6C5530, 0xFFA88A55, "dark"), Finish("raw", "Raw unlathed", 0xFF5A4A33, 0xFF8E7A58, "raw"),
        Finish("hammered", "Hand-hammered dark", 0xFF5E4628, 0xFF9C7E4E, "hammered"), Finish("brass", "Bright brass", 0xFFD4B048, 0xFFFFF0B0, "brass"),
    )
    val all = shells + cymbals
    fun find(id: String) = all.firstOrNull { it.id == id }
}

data class CatalogItem(
    val id: String, val name: String, val category: DrumCategory, val group: String, val shape: Shape,
    val sizeIn: Float = 14f, val depthIn: Float = 0f, val sample: String = "", val tune: Float = 0f, val gainDb: Float = 0f,
    val status: SoundStatus, val finish: String = "", val note: String = "",
) { val sounding get() = sample.isNotEmpty() }

object DrumCatalog {
    private val items = ArrayList<CatalogItem>()
    private fun add(i: CatalogItem) { items += i }
    /** Size → semitone retune relative to the sample's reference size (bigger = lower), the honest next-best for size variants. */
    private fun st(size: Float, ref: Float, k: Float = 9f) = (-k * kotlin.math.ln(size / ref) / kotlin.math.ln(2f) * 12f / 12f).let { (it * 10).toInt() / 10f }

    init {
        val C = DrumCategory.CYMBALS
        // ---- hi-hats (each adds closed bow + open edge + foot chick)
        listOf(Triple(12f, "brilliant", "Mini"), Triple(13f, "brilliant", "Bright"), Triple(14f, "traditional", "Medium"), Triple(14f, "dark", "Dark"), Triple(15f, "raw", "Heavy rock"), Triple(14f, "hammered", "Hand-hammered"))
            .forEach { (sz, f, adj) -> add(CatalogItem("hh_${sz.toInt()}_$f", "$adj hi-hats ${sz.toInt()}″", C, "Hi-hats", Shape.HIHAT, sz, sample = "closed_hat-0", tune = st(sz, 14f, 4f), status = if (sz == 14f && f == "traditional") SoundStatus.OWN else SoundStatus.RETUNED, finish = f, note = "Closed, open (outer edge) and foot chick zones")) }
        // ---- crashes
        listOf(14f to "brilliant", 16f to "traditional", 17f to "brilliant", 18f to "traditional", 19f to "dark", 20f to "traditional").forEach { (sz, f) ->
            add(CatalogItem("crash_${sz.toInt()}_$f", "${if (sz <= 16) "Thin" else if (sz >= 20) "Rock" else "Medium"} crash ${sz.toInt()}″", C, "Crashes", Shape.CRASH, sz, sample = "crash-0", tune = st(sz, 18f, 5f), status = if (sz == 18f) SoundStatus.OWN else SoundStatus.RETUNED, finish = f)) }
        listOf(18f, 20f, 22f).forEach { sz -> add(CatalogItem("crash_dark_${sz.toInt()}", "Dark thin crash ${sz.toInt()}″", C, "Crashes", Shape.CRASH, sz, sample = "crash_dark-0", tune = st(sz, 20f, 5f), status = if (sz == 20f) SoundStatus.OWN else SoundStatus.RETUNED, finish = "dark")) }
        // ---- rides (bow + bell zone)
        listOf(Triple(20f, "traditional", "Medium ride"), Triple(21f, "dark", "Dry ride"), Triple(22f, "brilliant", "Heavy ride"), Triple(24f, "hammered", "Jazz thin ride"), Triple(22f, "raw", "Raw ride"))
            .forEach { (sz, f, n) -> add(CatalogItem("ride_${sz.toInt()}_$f", "$n ${sz.toInt()}″", C, "Rides", Shape.RIDE, sz, sample = "ride-0", tune = st(sz, 21f, 5f), status = if (sz == 21f) SoundStatus.OWN else SoundStatus.RETUNED, finish = f, note = "Bow plus a separate bell zone")) }
        add(CatalogItem("ride_flat_20", "Flat ride 20″ (no bell)", C, "Rides", Shape.RIDE, 20f, sample = "ride-0", tune = 0.6f, gainDb = -2f, status = SoundStatus.RETUNED, finish = "raw"))
        listOf(16f, 18f, 20f).forEach { sz -> add(CatalogItem("china_${sz.toInt()}", "China ${sz.toInt()}″", C, "Chinas", Shape.CHINA, sz, sample = "china-0", tune = st(sz, 18f, 5f), status = if (sz == 18f) SoundStatus.OWN else SoundStatus.RETUNED, finish = "raw")) }
        listOf(6f, 8f, 10f, 12f).forEach { sz -> add(CatalogItem("splash_${sz.toInt()}", "Splash ${sz.toInt()}″", C, "Splashes", Shape.SPLASH, sz, sample = "splash-0", tune = st(sz, 10f, 5f), status = if (sz == 10f) SoundStatus.OWN else SoundStatus.RETUNED, finish = "brilliant")) }
        listOf(10f to "Mini stack", 14f to "Trash stack", 18f to "Crash-china stack").forEach { (sz, n) -> add(CatalogItem("stack_${sz.toInt()}", "$n ${sz.toInt()}″", C, "Stacks", Shape.STACK, sz, sample = "stack-0", tune = st(sz, 14f, 5f), status = if (sz == 14f) SoundStatus.OWN else SoundStatus.RETUNED, finish = "raw")) }
        add(CatalogItem("bell_8", "Cymbal bell 8″", C, "Bells & effects", Shape.BELL, 8f, sample = "ride_bell-0", tune = 4f, status = SoundStatus.RETUNED, finish = "brass"))
        add(CatalogItem("fx_18", "Effects cymbal 18″ (holed)", C, "Bells & effects", Shape.CRASH, 18f, sample = "cymbal-0", status = SoundStatus.OWN, finish = "dark"))
        add(CatalogItem("cowbell", "Mounted cowbell", C, "Bells & effects", Shape.COWBELL, 6f, sample = "cowbell-0", status = SoundStatus.OWN))
        add(CatalogItem("tambourine", "Mounted tambourine", C, "Bells & effects", Shape.TAMBOURINE, 10f, sample = "tambourine-0", status = SoundStatus.OWN))
        add(CatalogItem("woodblock", "Wood block", C, "Bells & effects", Shape.BLOCK, 6f, sample = "woodblock-0", status = SoundStatus.OWN))
        add(CatalogItem("jamblock", "Plastic jam block (high)", C, "Bells & effects", Shape.BLOCK, 5f, sample = "woodblock-0", tune = 4f, gainDb = 1f, status = SoundStatus.RETUNED))
        add(CatalogItem("gong_30", "Orchestral gong 30″", C, "Bells & effects", Shape.GONG, 30f, status = SoundStatus.GAP, finish = "dark", note = "No owned gong sample; a retuned crash would misrepresent it"))
        add(CatalogItem("bar_chimes", "Bar chimes", C, "Bells & effects", Shape.CHIMES, 14f, status = SoundStatus.GAP, note = "No owned chime-tree sample"))

        val D = DrumCategory.DRUMS
        listOf(Triple(18f, 14f, "Bop bass drum"), Triple(20f, 16f, "Bass drum"), Triple(22f, 18f, "Bass drum"), Triple(24f, 16f, "Rock bass drum")).forEach { (d, dp, n) ->
            add(CatalogItem("kick_${d.toInt()}", "$n ${d.toInt()}×${dp.toInt()}″", D, "Bass drums", Shape.KICK, d, dp, "kick-0", st(d, 22f, 4f), status = if (d == 22f) SoundStatus.OWN else SoundStatus.RETUNED)) }
        add(CatalogItem("kick_26", "Big-room bass drum 26×14″", D, "Bass drums", Shape.KICK, 26f, 14f, "kick_deep-0", status = SoundStatus.OWN))
        add(CatalogItem("kick_22p", "Ported bass drum 22×20″", D, "Bass drums", Shape.KICK, 22f, 20f, "kick-0", -0.4f, 1f, SoundStatus.RETUNED))
        add(CatalogItem("snare_maple", "Maple snare 14×5.5″", D, "Snares", Shape.SNARE, 14f, 5.5f, "snare-0", status = SoundStatus.OWN, note = "Rim zone plays a cross-stick/rimshot"))
        add(CatalogItem("snare_brass", "Hammered brass snare 14×6.5″", D, "Snares", Shape.SNARE, 14f, 6.5f, "snare-1", status = SoundStatus.OWN, finish = "hammered_brass"))
        add(CatalogItem("snare_steel", "Steel snare 14×5″", D, "Snares", Shape.SNARE, 14f, 5f, "snare-0", 1f, 1f, SoundStatus.RETUNED, finish = "brushed_steel"))
        add(CatalogItem("snare_bronze", "Cast bronze snare 14×6.5″", D, "Snares", Shape.SNARE, 14f, 6.5f, "snare-1", 0.5f, status = SoundStatus.RETUNED, finish = "bronze_shell"))
        add(CatalogItem("snare_deep", "Deep snare 14×8″", D, "Snares", Shape.SNARE, 14f, 8f, "snare-1", -2f, status = SoundStatus.RETUNED))
        add(CatalogItem("snare_piccolo", "Piccolo snare 14×3.5″", D, "Snares", Shape.SNARE, 14f, 3.5f, "piccolo_snare-0", status = SoundStatus.OWN))
        add(CatalogItem("snare_side", "Side snare 12×5″", D, "Snares", Shape.SNARE, 12f, 5f, "piccolo_snare-0", 2f, status = SoundStatus.RETUNED))
        listOf(8f to "high_tom-0", 10f to "high_tom-1", 12f to "mid_tom-0", 13f to "mid_tom-1", 14f to "mid_tom-1").forEachIndexed { i, (d, s) ->
            add(CatalogItem("tom_${d.toInt()}", "Rack tom ${d.toInt()}×${(d * 0.65f + 2).toInt()}″", D, "Rack toms", Shape.TOM, d, d * 0.65f + 2, s, if (i == 4) -2f else 0f, status = if (i == 4) SoundStatus.RETUNED else SoundStatus.OWN)) }
        listOf(14f to "low_tom-0", 16f to "low_tom-1", 18f to "low_tom-1").forEachIndexed { i, (d, s) ->
            add(CatalogItem("floor_${d.toInt()}", "Floor tom ${d.toInt()}×${(d - 2).toInt()}″", D, "Floor toms", Shape.FLOOR_TOM, d, d - 2, s, if (i == 2) -2.5f else 0f, status = if (i == 2) SoundStatus.RETUNED else SoundStatus.OWN)) }
        add(CatalogItem("gong_drum_20", "Gong drum 20×14″", D, "Other drums", Shape.FLOOR_TOM, 20f, 14f, "low_tom-1", -5f, 2f, SoundStatus.RETUNED))
        add(CatalogItem("octoban", "Tube tom (octoban) 6″", D, "Other drums", Shape.TOM, 6f, 18f, "high_tom-0", 6f, status = SoundStatus.RETUNED))
        add(CatalogItem("roto_10", "Roto tom 10″", D, "Other drums", Shape.TOM, 10f, 2f, "high_tom-1", 3f, -1f, SoundStatus.RETUNED, note = "Shell-less tom approximated by a retuned rack tom"))
        add(CatalogItem("timbales", "Timbales 13/14″", D, "Other drums", Shape.TOM, 13f, 6f, status = SoundStatus.GAP, finish = "brushed_steel", note = "Needs a metal-shell timbale sample; not owned yet"))
        add(CatalogItem("clap", "Electronic clap pad", D, "Other drums", Shape.BLOCK, 7f, sample = "clap-0", status = SoundStatus.OWN))

        val P = DrumCategory.PEDALS
        listOf("chain" to "Chain-drive", "belt" to "Belt-drive", "direct" to "Direct-drive", "longboard" to "Long-board").forEach { (id, n) ->
            add(CatalogItem("pedal_single_$id", "$n single pedal", P, "Single kick", Shape.PEDAL_SINGLE, status = SoundStatus.TRIGGER, note = "Footboard plays the bass drum")) }
        listOf("chain" to "Chain-drive", "belt" to "Belt-drive", "direct" to "Direct-drive").forEach { (id, n) ->
            add(CatalogItem("pedal_double_$id", "$n double pedal", P, "Double kick", Shape.PEDAL_DOUBLE, status = SoundStatus.TRIGGER, note = "Two footboards = two independent bass-drum hits")) }

        val H = DrumCategory.HARDWARE
        listOf(
            Triple("throne_round", "Round throne", Shape.THRONE), Triple("throne_saddle", "Saddle throne with backrest", Shape.THRONE), Triple("stand_straight", "Straight cymbal stand", Shape.STAND),
            Triple("stand_boom", "Boom cymbal stand", Shape.BOOM), Triple("stand_hat", "Hi-hat stand (two-leg)", Shape.HAT_STAND), Triple("stand_snare", "Snare stand", Shape.SNARE_STAND),
            Triple("rack_curved", "Curved drum rack (chrome)", Shape.RACK), Triple("rack_black", "Drum rack (black)", Shape.RACK), Triple("clamp_multi", "Multi-clamp", Shape.CLAMP),
            Triple("arm_boom", "Rack cymbal boom arm", Shape.CLAMP), Triple("tom_mount", "Tom mount with ball clamp", Shape.CLAMP), Triple("stacker", "Cymbal stacker", Shape.CLAMP),
            Triple("cowbell_mount", "Cowbell mount", Shape.CLAMP), Triple("rug", "Drum rug", Shape.RUG), Triple("beater_felt", "Felt beater", Shape.BEATER),
            Triple("beater_plastic", "Plastic beater", Shape.BEATER), Triple("beater_wood", "Wood beater", Shape.BEATER), Triple("drum_key", "Drum key", Shape.KEY),
        ).forEach { (id, n, sh) -> add(CatalogItem(id, n, H, when (sh) { Shape.THRONE -> "Thrones"; Shape.RACK, Shape.CLAMP -> "Racks & mounts"; Shape.BEATER, Shape.KEY -> "Beaters & tools"; Shape.RUG -> "Floor"; else -> "Stands" }, sh,
            status = SoundStatus.SILENT, finish = if (id == "rack_black") "piano_black" else "")) }
    }

    val all: List<CatalogItem> get() = items
    fun get(id: String) = items.firstOrNull { it.id == id }
    fun inCategory(c: DrumCategory) = items.filter { it.category == c }
    fun groups(c: DrumCategory) = inCategory(c).map { it.group }.distinct()
    fun gaps() = items.filter { it.status == SoundStatus.GAP }

    // ------------------------------------------------------------------ building kits
    private val gm = mapOf("kick" to listOf(36, 35), "snare" to listOf(38, 40), "rim" to listOf(37), "hh" to listOf(42), "hh_edge" to listOf(46), "hh_foot" to listOf(44),
        "tom" to listOf(50, 48, 47, 45), "floor" to listOf(43, 41), "crash" to listOf(49, 57), "ride" to listOf(51, 59), "bell" to listOf(53), "china" to listOf(52),
        "splash" to listOf(55), "cowbell" to listOf(56), "tamb" to listOf(54), "block" to listOf(76, 77), "clap" to listOf(39), "left" to listOf(35))
    private fun freeMidi(used: Set<Int>, pref: String): Int = (gm[pref].orEmpty().firstOrNull { it !in used }) ?: (60..127).first { it !in used }
    private fun freeId(kit: List<KitPiece>, base: String): String { if (kit.none { it.id == base }) return base; var n = 2; while (kit.any { it.id == "$base$n" }) n++; return "$base$n" }
    private fun freeChoke(kit: List<KitPiece>) = (1..16).first { g -> kit.none { it.chokeGroup == g } }
    fun sizeFrac(item: CatalogItem) = item.sizeIn * 0.0085f

    /** Instantiate [item] into [kit] at (x, y). Adds linked zones (hi-hat edge/foot, ride bell, snare rim, double-pedal left beater) and returns the new kit. */
    fun addTo(kit: DrumKit, item: CatalogItem, x: Float? = null, y: Float? = null, finish: String? = null): DrumKit {
        val ps = kit.pieces.toMutableList(); val used = ps.map { it.midi }.toMutableSet()
        fun put(id: String, name: String, sample: String, px: Float, py: Float, size: Float, pref: String, tune: Float = item.tune, gain: Float = item.gainDb, choke: Int = 0, role: String = "", link: String = "", cat: String = item.id, fin: String = finish ?: item.finish): KitPiece {
            val pid = freeId(ps, id); val m = if (sample.isEmpty()) 0 else freeMidi(used, pref); if (m > 0) used += m
            return KitPiece(pid, name, sample, px, py, size, tune, gain, choke, m, 0f, cat, fin, role, link).also { ps += it } }
        val sz = sizeFrac(item)
        val (dx, dy) = defaultSpot(item, ps)
        val px = x ?: dx; val py = y ?: dy
        when (item.shape) {
            Shape.HIHAT -> { val g = freeChoke(ps); val host = put("hh", item.name, item.sample, px, py, sz, "hh", choke = g)
                put("hh_open", "${item.name} — open", "open_hat-0", px, py, sz, "hh_edge", choke = g, role = "edge", link = host.id)
                put("hh_foot", "${item.name} — foot", "hat_pedal-0", px, py, sz, "hh_foot", choke = g, role = "foot", link = host.id) }
            Shape.RIDE -> { val g = freeChoke(ps); val host = put("ride", item.name, item.sample, px, py, sz, "ride", choke = g)
                if (item.id != "ride_flat_20") put("ride_bell", "${item.name} — bell", "ride_bell-0", px, py, sz, "bell", tune = item.tune * 0.5f, choke = g, role = "bell", link = host.id) }
            Shape.SNARE -> { val host = put("snare", item.name, item.sample, px, py, sz, "snare")
                put("snare_rim", "${item.name} — rim", "rimshot-0", px, py, sz, "rim", tune = item.tune * 0.5f, gain = 0f, role = "rim", link = host.id) }
            Shape.PEDAL_SINGLE, Shape.PEDAL_DOUBLE -> {
                ps.removeAll { it.catalogId.startsWith("pedal_") || it.role == "left" }
                var kick = ps.firstOrNull { DrumCatalog.shapeOf(it) == Shape.KICK && it.role.isEmpty() }
                if (kick == null) { val k = get("kick_22")!!; kick = put("kick", k.name, k.sample, 0.5f, 0.66f, sizeFrac(k), "kick", tune = k.tune, gain = 0f, cat = k.id, fin = kit.shellFinish) }
                val ky = kick.y + kick.size * 0.62f + 0.08f
                put("pedal", item.name, "", kick.x, ky, 0.07f, "kick", cat = item.id, fin = "")
                if (item.shape == Shape.PEDAL_DOUBLE) put("pedal_left", "${item.name} — left beater", kick.sample, kick.x, ky, 0.07f, "left", tune = kick.tune - 0.1f, gain = kick.gainDb - 0.6f, role = "left", link = kick.id, fin = "") }
            else -> { val pref = when (item.shape) { Shape.KICK -> "kick"; Shape.TOM -> "tom"; Shape.FLOOR_TOM -> "floor"; Shape.CRASH -> "crash"; Shape.CHINA -> "china"; Shape.SPLASH -> "splash"; Shape.STACK -> "crash"
                Shape.BELL -> "bell"; Shape.COWBELL -> "cowbell"; Shape.TAMBOURINE -> "tamb"; Shape.BLOCK -> if (item.id == "clap") "clap" else "block"; else -> "" }
                val base = item.id.substringBefore('_').let { if (item.shape == Shape.KICK && ps.any { it.id == "kick" }) "kick" else it }
                put(base, item.name, item.sample, px, py, if (item.shape == Shape.RUG) 1f else sz, pref,
                    choke = if (item.shape in setOf(Shape.CRASH, Shape.CHINA, Shape.SPLASH, Shape.STACK)) freeChoke(ps) else 0) }
        }
        return kit.copy(pieces = ps)
    }

    /** Removes a piece and every zone linked to it (and pedals if the last bass drum goes). */
    fun remove(kit: DrumKit, pieceId: String): DrumKit {
        val target = kit.pieces.firstOrNull { it.id == pieceId } ?: return kit
        val host = if (target.link.isNotEmpty() && target.role != "left") target.link else target.id
        var ps = kit.pieces.filter { it.id != host && it.link != host }
        if (target.catalogId.startsWith("pedal_")) ps = ps.filter { it.role != "left" }
        if (ps.none { shapeOf(it) == Shape.KICK && it.role.isEmpty() }) ps = ps.filter { !it.catalogId.startsWith("pedal_") && it.role != "left" }
        return kit.copy(pieces = ps)
    }

    /** Move a piece; linked zones follow their host. */
    fun move(kit: DrumKit, pieceId: String, x: Float, y: Float): DrumKit {
        val p = kit.pieces.firstOrNull { it.id == pieceId } ?: return kit
        val host = if (p.link.isNotEmpty() && p.role != "left") kit.pieces.firstOrNull { it.id == p.link } ?: p else p
        val nx = x.coerceIn(0.04f, 0.96f); val ny = y.coerceIn(0.04f, 0.98f); val dx = nx - host.x; val dy = ny - host.y
        return kit.copy(pieces = kit.pieces.map { if (it.id == host.id || it.link == host.id) it.copy(x = it.x + dx, y = it.y + dy) else it })
    }

    private fun defaultSpot(item: CatalogItem, ps: List<KitPiece>): Pair<Float, Float> {
        val taken = { x: Float, y: Float -> ps.any { kotlin.math.hypot(it.x - x, it.y - y) < 0.09f && it.role.isEmpty() } }
        val cands = when (item.shape) {
            Shape.KICK -> listOf(0.5f to 0.66f, 0.62f to 0.68f, 0.38f to 0.68f)
            Shape.SNARE -> listOf(0.32f to 0.58f, 0.30f to 0.40f, 0.22f to 0.70f)
            Shape.TOM -> listOf(0.40f to 0.30f, 0.58f to 0.30f, 0.30f to 0.26f, 0.68f to 0.36f, 0.49f to 0.20f)
            Shape.FLOOR_TOM -> listOf(0.74f to 0.62f, 0.86f to 0.70f, 0.20f to 0.80f)
            Shape.HIHAT -> listOf(0.12f to 0.52f, 0.88f to 0.52f)
            Shape.RIDE -> listOf(0.84f to 0.30f, 0.16f to 0.28f)
            Shape.THRONE -> listOf(0.5f to 0.95f)
            Shape.RUG -> listOf(0.5f to 0.55f)
            Shape.RACK -> listOf(0.5f to 0.14f)
            Shape.PEDAL_SINGLE, Shape.PEDAL_DOUBLE -> listOf(0.5f to 0.86f)
            else -> listOf(0.18f to 0.16f, 0.64f to 0.10f, 0.88f to 0.14f, 0.32f to 0.08f, 0.92f to 0.44f, 0.06f to 0.30f, 0.50f to 0.06f, 0.08f to 0.76f)
        }
        return cands.firstOrNull { (x, y) -> !taken(x, y) } ?: cands.first().let { (x, y) -> (x + 0.04f * (ps.size % 5)).coerceAtMost(0.95f) to (y + 0.03f * (ps.size % 4)).coerceAtMost(0.95f) }
    }

    /** Visual/physical shape of any piece, including legacy pieces without a catalog id (inferred from the sample). */
    fun shapeOf(p: KitPiece): Shape = get(p.catalogId)?.shape ?: when {
        p.sample.startsWith("kick") -> Shape.KICK; p.sample.startsWith("snare") || p.sample.startsWith("piccolo") || p.sample.startsWith("rimshot") -> Shape.SNARE
        p.sample.contains("hat") -> Shape.HIHAT; p.sample.startsWith("crash") || p.sample.startsWith("cymbal") -> Shape.CRASH; p.sample.startsWith("ride") -> Shape.RIDE
        p.sample.startsWith("china") -> Shape.CHINA; p.sample.startsWith("splash") -> Shape.SPLASH; p.sample.startsWith("stack") -> Shape.STACK
        p.sample.startsWith("low_tom") -> Shape.FLOOR_TOM; p.sample.contains("tom") -> Shape.TOM; p.sample.startsWith("cowbell") -> Shape.COWBELL
        p.sample.startsWith("tambourine") -> Shape.TAMBOURINE; p.sample.startsWith("woodblock") || p.sample.startsWith("clap") -> Shape.BLOCK
        else -> Shape.TOM
    }
    fun statusOf(p: KitPiece): SoundStatus = get(p.catalogId)?.status ?: if (p.sounding) SoundStatus.OWN else SoundStatus.SILENT

    // ------------------------------------------------------------------ starter kits
    private fun build(id: String, name: String, shell: String, cym: String, stick: Stick, vararg parts: Triple<String, Float, Float>): DrumKit {
        var k = DrumKit(id, name, emptyList(), stick, shellFinish = shell, cymbalFinish = cym, starterId = id)
        for ((item, x, y) in parts) { val ci = get(item)!!; k = addTo(k, ci, x, y, if (ci.category == DrumCategory.DRUMS && ci.finish.isEmpty()) shell else null) }
        return k
    }
    private fun t(id: String, x: Float, y: Float) = Triple(id, x, y)
    val blank = DrumKit("blank", "Blank kit — build it yourself", emptyList(), starterId = "blank")
    val starters: List<DrumKit> by lazy { listOf(
        build("studio_maple", "Studio Maple 5-pc", "natural_maple", "traditional", Stick.S5A, t("rug", .5f, .55f), t("throne_round", .5f, .95f), t("kick_22", .5f, .66f), t("pedal_single_chain", .5f, .86f), t("snare_maple", .30f, .56f),
            t("hh_14_traditional", .11f, .50f), t("tom_10", .38f, .30f), t("tom_12", .58f, .30f), t("floor_16", .75f, .60f), t("crash_18_traditional", .17f, .18f), t("ride_20_traditional", .85f, .28f)),
        build("thunder_double", "Thunder Double-Kick Metal", "black_sparkle", "brilliant", Stick.S5A, t("rug", .5f, .55f), t("throne_round", .5f, .96f), t("kick_22", .40f, .70f), t("kick_24", .62f, .70f), t("snare_steel", .26f, .54f),
            t("hh_14_dark", .08f, .44f), t("tom_10", .34f, .26f), t("tom_12", .50f, .24f), t("tom_13", .66f, .30f), t("floor_16", .80f, .58f), t("floor_18", .90f, .74f), t("crash_19_dark", .12f, .14f), t("crash_20_traditional", .62f, .08f),
            t("china_18", .90f, .14f), t("ride_22_brilliant", .92f, .40f), t("splash_10", .30f, .08f)),
        build("prog_arsenal", "Progressive Arsenal (large)", "burgundy_fade", "brilliant", Stick.S5A, t("rug", .5f, .55f), t("throne_saddle", .5f, .96f), t("rack_curved", .5f, .12f), t("kick_24", .5f, .70f), t("pedal_double_direct", .5f, .90f),
            t("snare_bronze", .26f, .54f), t("hh_13_brilliant", .07f, .44f), t("octoban", .22f, .30f), t("tom_8", .33f, .26f), t("tom_10", .45f, .22f), t("tom_12", .58f, .24f), t("tom_13", .70f, .30f), t("floor_14", .78f, .56f),
            t("floor_16", .90f, .68f), t("splash_8", .30f, .06f), t("crash_16_traditional", .13f, .14f), t("crash_18_traditional", .70f, .06f), t("china_20", .92f, .16f), t("ride_22_brilliant", .92f, .42f), t("cowbell", .50f, .05f), t("stack_14", .40f, .06f)),
        build("jazz_bop", "Bop Kit 4-pc", "white_pearl", "hammered", Stick.BRUSHES, t("rug", .5f, .55f), t("throne_round", .5f, .95f), t("kick_18", .5f, .66f), t("pedal_single_belt", .5f, .84f), t("snare_maple", .32f, .56f),
            t("hh_14_hammered", .11f, .52f), t("tom_12", .48f, .28f), t("floor_14", .73f, .58f), t("ride_24_hammered", .80f, .22f), t("crash_18_traditional", .20f, .18f)),
        build("rock_four", "Rock 4-pc", "black_sparkle", "traditional", Stick.S5A, t("rug", .5f, .55f), t("throne_round", .5f, .95f), t("kick_24", .5f, .66f), t("pedal_single_chain", .5f, .87f), t("snare_deep", .30f, .56f),
            t("hh_15_raw", .11f, .50f), t("tom_13", .46f, .28f), t("floor_16", .75f, .60f), t("crash_19_dark", .18f, .18f), t("crash_20_traditional", .62f, .12f), t("ride_22_brilliant", .86f, .30f)),
        build("fusion_six", "Fusion 6-pc", "ocean_burst", "brilliant", Stick.S7A, t("rug", .5f, .55f), t("throne_round", .5f, .95f), t("kick_20", .5f, .66f), t("pedal_single_direct", .5f, .84f), t("snare_piccolo", .30f, .56f),
            t("hh_13_brilliant", .11f, .50f), t("tom_8", .30f, .28f), t("tom_10", .44f, .26f), t("tom_12", .58f, .28f), t("floor_14", .74f, .60f), t("splash_8", .34f, .10f), t("crash_16_traditional", .17f, .18f), t("ride_20_traditional", .86f, .30f)),
        build("vintage_three", "Vintage 3-pc", "white_pearl", "dark", Stick.S7A, t("rug", .5f, .55f), t("throne_round", .5f, .95f), t("kick_20", .5f, .66f), t("pedal_single_longboard", .5f, .85f), t("snare_brass", .32f, .56f),
            t("hh_14_dark", .12f, .52f), t("tom_12", .48f, .28f), t("floor_16", .74f, .60f), t("ride_22_raw", .84f, .26f)),
        build("funk_five", "Funk 5-pc", "red_sparkle", "brilliant", Stick.S7A, t("rug", .5f, .55f), t("throne_round", .5f, .95f), t("kick_20", .5f, .66f), t("pedal_single_belt", .5f, .84f), t("snare_side", .30f, .58f),
            t("hh_13_brilliant", .11f, .50f), t("tom_10", .42f, .28f), t("floor_14", .74f, .60f), t("cowbell", .56f, .16f), t("tambourine", .18f, .34f), t("crash_17_brilliant", .20f, .14f), t("ride_20_traditional", .86f, .30f)),
        build("big_room", "Big Room 7-pc", "copper_metal", "dark", Stick.S5A, t("rug", .5f, .55f), t("throne_saddle", .5f, .96f), t("kick_26", .5f, .68f), t("pedal_double_chain", .5f, .90f), t("snare_deep", .28f, .56f),
            t("hh_15_raw", .09f, .48f), t("tom_12", .36f, .26f), t("tom_13", .54f, .24f), t("tom_14", .70f, .30f), t("floor_16", .78f, .60f), t("floor_18", .90f, .72f), t("gong_drum_20", .14f, .76f),
            t("crash_dark_20", .16f, .14f), t("crash_dark_22", .64f, .08f), t("china_20", .92f, .18f), t("ride_24_hammered", .92f, .42f)),
    ) }
    fun starter(id: String) = if (id == "blank") blank else starters.firstOrNull { it.id == id }
}
