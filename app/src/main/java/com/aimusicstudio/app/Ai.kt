package com.aimusicstudio.app

import kotlin.math.*
import kotlin.random.Random

/** On-device "AI" songwriting assistant: key detection, role-aware phrase generation, next-note hints, chord guidance, song-type analysis, lyrics. */
object SongAI {
    enum class Role(val label: String) { BASS("Bassline"), LEAD("Melody"), CHORDS("Chords"), ARP("Arpeggio") }
    data class Key(val root: Int, val minor: Boolean) { val name get() = Tuner.names[root] + if (minor) " minor" else " major"
        val scale get() = (if (minor) intArrayOf(0, 2, 3, 5, 7, 8, 10) else intArrayOf(0, 2, 4, 5, 7, 9, 11)).map { (it + root) % 12 } }
    private val majP = doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88)
    private val minP = doubleArrayOf(6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17)

    fun detectKey(s: StudioSession): Key {
        val h = DoubleArray(12); s.tracks.flatMap { it.notes }.forEach { h[it.pitch % 12] += it.duration }
        if (h.sum() == 0.0) return Key(9, true).takeIf { s.tempoBPM in 130..160 || s.kit == DrumKit.TRAP } ?: Key(0, false)
        var best = Key(0, false); var bs = -1e9
        for (r in 0 until 12) for (m in listOf(false, true)) { val p = if (m) minP else majP; val sc = (0 until 12).sumOf { h[(it + r) % 12] * p[it] }; if (sc > bs) { bs = sc; best = Key(r, m) } }
        return best
    }
    fun role(t: Track): Role = when {
        t.guitar in listOf(GuitarModel.BASS, GuitarModel.JAZZ_BASS, GuitarModel.FRETLESS) && t.kind == TrackKind.GUITAR -> Role.BASS
        t.waveform in listOf(SynthWaveform.SUB_BASS, SynthWaveform.REESE) || t.name.contains("bass", true) || t.name.contains("303") -> Role.BASS
        t.waveform in listOf(SynthWaveform.STRINGS, SynthWaveform.CHOIR, SynthWaveform.ORGAN, SynthWaveform.EPIANO) || t.name.contains("pad", true) || t.name.contains("chord", true) -> Role.CHORDS
        t.waveform in listOf(SynthWaveform.PLUCK, SynthWaveform.BELL) -> Role.ARP
        else -> Role.LEAD }

    /** Diatonic progression (scale degrees) per mood. */
    fun progression(k: Key) = if (k.minor) listOf(0, 5, 2, 6) else listOf(0, 4, 5, 3)
    fun chordTones(k: Key, degree: Int): List<Int> = listOf(0, 2, 4).map { k.scale[(degree + it) % 7] }
    fun chordName(k: Key, degree: Int): String { val t = chordTones(k, degree); val third = (t[1] - t[0] + 12) % 12; val fifth = (t[2] - t[0] + 12) % 12
        return Tuner.names[t[0]] + when { third == 3 && fifth == 6 -> "dim"; third == 3 -> "m"; else -> "" } }
    fun chordAt(s: StudioSession, k: Key, time: Double): Int { val p = progression(k); return p[((time / s.barSeconds).toInt()) % p.size] }

    /** Generate [bars] bars for the track's role starting at [start] (bar-aligned). */
    fun generate(s: StudioSession, t: Track, start: Double, bars: Int = 4, seed: Int = Random.nextInt(), roleOverride: Role? = null): List<SynthNote> {
        val k = detectKey(s); val r = roleOverride ?: role(t); val rnd = Random(seed); val st = s.stepSeconds; val bar = s.barSeconds
        val t0 = floor(start / bar) * bar; val out = mutableListOf<SynthNote>()
        val base = when (r) { Role.BASS -> 36; Role.CHORDS -> 55; Role.ARP -> 60; Role.LEAD -> 64 }
        fun nearest(pc: Int, around: Int): Int { var best = around; var d = 99; for (o in -2..2) { val p = (around / 12 + o) * 12 + pc; if (abs(p - around) < d) { d = abs(p - around); best = p } }; return best }
        var last = base
        for (b in 0 until bars) { val deg = progression(k)[b % 4]; val tones = chordTones(k, deg); val bt = t0 + b * bar
            when (r) {
                Role.BASS -> { val pat = listOf(listOf(0, 0, 8, 10, 12), listOf(0, 6, 8, 14), listOf(0, 3, 6, 8, 11, 14), listOf(0, 4, 8, 12))[rnd.nextInt(4)]
                    pat.forEachIndexed { i, stp -> val pc = if (i == pat.size - 1 && rnd.nextBoolean()) tones[2] else if (i % 3 == 2) tones[2] else tones[0]
                        val p = nearest(pc, base); val nx = pat.getOrNull(i + 1) ?: 16; out += SynthNote(pitch = p, startTime = bt + stp * st, duration = (nx - stp) * st * 0.9, velocity = if (stp == 0) 0.95f else 0.75f) } }
                Role.CHORDS -> tones.forEach { pc -> out += SynthNote(pitch = nearest(pc, base), startTime = bt, duration = bar * 0.98, velocity = 0.6f) }
                Role.ARP -> for (i in 0 until 8) { val pc = tones[listOf(0, 1, 2, 1)[i % 4]]; out += SynthNote(pitch = nearest(pc, base) + if (i >= 4 && rnd.nextBoolean()) 12 else 0, startTime = bt + i * 2 * st, duration = st * 1.8, velocity = 0.7f) }
                Role.LEAD -> { var stp = 0; while (stp < 16) { val len = listOf(2, 2, 4, 1, 3, 6)[rnd.nextInt(6)].coerceAtMost(16 - stp)
                        val strong = stp % 4 == 0; val cand = if (strong) tones else k.scale
                        val p = cand.map { nearest(it, last) }.filter { abs(it - last) <= 5 }.ifEmpty { cand.map { nearest(it, base) } }.random(rnd)
                        if (rnd.nextFloat() > 0.15f || strong) out += SynthNote(pitch = p, startTime = bt + stp * st, duration = len * st * 0.92, velocity = if (strong) 0.85f else 0.7f)
                        last = p.coerceIn(base - 7, base + 12); stp += len } }
            } }
        return out
    }

    /** Up to 4 suggested next pitches after [last], weighted toward chord tones at [time]. */
    fun nextHints(s: StudioSession, last: Int?, time: Double): List<Int> {
        val k = detectKey(s); val tones = chordTones(k, chordAt(s, k, time)); val ref = last ?: 60
        val c = (-7..7).map { ref + it }.filter { (it % 12) in k.scale && it != ref }
        return c.sortedBy { abs(it - ref) * 2 - (if (it % 12 in tones) 5 else 0) }.take(4)
    }

    /** Genre / mood / form estimate from tempo, key, kit and instruments. */
    fun analyze(s: StudioSession): Triple<String, String, String> {
        val k = detectKey(s); val bpm = s.tempoBPM; val g = s.tracks.map { it.amp }; val w = s.tracks.map { it.waveform }
        val genre = when {
            g.any { it == AmpModel.METAL || it == AmpModel.RECTO } || s.kit == DrumKit.METAL -> "Metal"
            s.kit == DrumKit.TRAP || (bpm in 130..160 && w.contains(SynthWaveform.SUB_BASS)) -> "Trap / Hip-hop"
            s.kit == DrumKit.DNB || bpm >= 165 -> "Drum & Bass"
            s.kit == DrumKit.HOUSE || s.kit == DrumKit.VINTAGE909 || (bpm in 118..130 && w.contains(SynthWaveform.SUPERSAW)) -> "House / EDM"
            s.kit == DrumKit.JAZZ_BRUSH || g.contains(AmpModel.JAZZ) -> "Jazz / Neo-soul"
            s.kit == DrumKit.LOFI || bpm in 70..92 -> "Lo-fi / R&B"
            g.any { it == AmpModel.BRIT || it == AmpModel.PLEXI || it == AmpModel.HIGAIN } -> "Rock"
            s.tracks.any { it.guitar == GuitarModel.ACOUSTIC || it.guitar == GuitarModel.RESO } -> "Country / Folk"
            else -> "Pop" }
        val mood = (if (k.minor) "moody, emotional" else "uplifting, bright") + if (bpm > 125) ", high energy" else if (bpm < 90) ", laid-back" else ""
        val form = when (genre) { "Trap / Hip-hop" -> "Intro · Hook · Verse · Hook · Verse · Hook · Outro"; "House / EDM", "Drum & Bass" -> "Intro · Build · Drop · Break · Build · Drop · Outro"
            "Jazz / Neo-soul" -> "Head · Solos · Head (AABA)"; else -> "Intro · Verse · Chorus · Verse · Chorus · Bridge · Chorus" }
        return Triple(genre, "${k.name}, $bpm BPM – $mood", form)
    }

    private val themes = mapOf(
        "love" to listOf("your heart", "the light", "tonight", "forever", "so close", "the fire", "your eyes", "the rain"),
        "night" to listOf("the city", "neon lights", "midnight", "the streets", "the stars", "the radio", "the highway", "the moon"),
        "struggle" to listOf("the grind", "the pressure", "the climb", "the storm", "my chains", "the ground", "the edge", "the fight"),
        "freedom" to listOf("the open road", "the sky", "the wind", "the ocean", "my wings", "the sunrise", "the line", "the wild"))
    private val rhymes = listOf(listOf("night", "light", "fight", "right", "bright", "sight"), listOf("fire", "higher", "wire", "desire"), listOf("away", "stay", "today", "okay", "say"),
        listOf("ground", "sound", "around", "found", "down"), listOf("heart", "start", "apart", "art"), listOf("go", "slow", "know", "glow", "show"))

    /** Template-based lyric writer with sections and end rhymes; result is plain editable text. */
    fun writeLyrics(theme: String, genre: String, seed: Int = Random.nextInt()): String {
        val rnd = Random(seed); val key = themes.keys.firstOrNull { theme.contains(it, true) } ?: themes.keys.random(rnd)
        val img = themes[key]!!; val subj = theme.ifBlank { key }
        val starts = listOf("I keep running through", "We were dancing in", "Can you feel", "Hold on to", "Lost inside", "I can hear", "Every time I see", "Take me back to", "Falling into", "Burning like")
        fun line(end: String) = "${starts.random(rnd)} ${img.random(rnd)}, ${listOf("I won't let it", "it's coming", "we're never", "you know it's", "don't let it").random(rnd)} $end"
        fun couplet(): List<String> { val r = rhymes.random(rnd).shuffled(rnd); return listOf(line(r[0]), line(r[1])) }
        val hook = couplet() + listOf("Oh, ${subj.lowercase()}, ${img.random(rnd)} is all I know", "Oh, ${subj.lowercase()}, never let me go")
        val rap = genre.contains("Hip", true)
        val sb = StringBuilder()
        fun sec(name: String, l: List<String>) { sb.append("[$name]\n"); l.forEach { sb.append(it).append('\n') }; sb.append('\n') }
        sec("Verse 1", couplet() + couplet()); sec(if (rap) "Hook" else "Chorus", hook); sec("Verse 2", couplet() + couplet()); sec(if (rap) "Hook" else "Chorus", hook)
        if (!rap) sec("Bridge", couplet()); sec(if (rap) "Hook" else "Chorus", hook)
        return sb.toString().trim()
    }

    data class Concept(val title: String, val genre: String, val mood: String, val theme: String, val form: String, val bpm: Int, val key: String, val vocal: VocalStyle, val instruments: String) {
        fun summary() = "\"$title\" · $genre · $mood\nTheme: $theme\nForm: $form\nTempo ~$bpm BPM · Key $key\nVocal: ${vocal.label} (device speech voice, spoken in rhythm)\nInstrumentation: $instruments"
    }
    /** Invents a full song idea from a (possibly blank) prompt plus the current session (tempo, key, kit, amps). */
    fun inventConcept(s: StudioSession, prompt: String, seed: Int = Random.nextInt()): Concept {
        val rnd = Random(seed); val key = detectKey(s); val (g0, _, _) = analyze(s)
        val genre = listOf("Pop", "Rock", "Hip-hop", "R&B", "Country", "Metal", "Indie", "Electronic", "Jazz").firstOrNull { prompt.contains(it, true) } ?: g0
        val themesL = listOf("leaving a small town at midnight", "a love that survives the distance", "chasing a dream nobody else sees", "the last summer before everything changed",
            "fighting back after a fall", "a city that never sleeps", "an old photograph", "dancing alone in the kitchen", "storms and second chances")
        val theme = prompt.ifBlank { themesL.random(rnd) }
        val mood = listOf("uplifting", "bittersweet", "defiant", "dreamy", "nostalgic", "dark & driving", "euphoric").random(rnd)
        val w = theme.split(" ").filter { it.length > 3 }.shuffled(rnd).take(2).joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
        val title = listOf("$w", "Midnight $w", "$w Tonight", "The $w Song", "Back to $w").random(rnd).ifBlank { "Untitled Spark" }
        val form = listOf("Intro · Verse · Pre · Chorus · Verse · Chorus · Bridge · Chorus · Outro", "Verse · Chorus · Verse · Chorus · Bridge · Double Chorus", "Intro · Hook · Verse · Hook · Verse · Hook · Outro").random(rnd)
        val bpm = when { genre.contains("Hip") -> 88; genre.contains("Metal") -> 160; genre.contains("Rock") -> 128; genre.contains("Electronic") -> 124; genre.contains("Country") -> 100; genre.contains("Jazz") || genre.contains("R&B") -> 82; else -> s.tempoBPM }
        val vocal = when { genre.contains("Hip") -> VocalStyle.RAP; genre.contains("Rock") || genre.contains("Metal") -> VocalStyle.ROCK; genre.contains("R&B") || genre.contains("Jazz") -> VocalStyle.SOUL; genre.contains("Country") -> VocalStyle.COUNTRY; mood == "bittersweet" -> VocalStyle.BALLAD; else -> VocalStyle.POP }
        val amps = s.tracks.filter { it.amp != AmpModel.NONE }.map { it.amp.label }.distinct().take(2)
        val inst = "${s.kit.label} drum kit" + (if (amps.isNotEmpty()) ", guitars through ${amps.joinToString()}" else ", clean guitar + synth pad") + ", bass, " + listOf("piano hook", "arpeggiated synth", "string swell", "lead guitar riff").random(rnd)
        return Concept(title, genre, mood, theme, form, bpm, key.name, vocal, inst)
    }
}

data class FxPreset(val name: String, val amp: AmpModel, val pedals: List<Pair<PedalType, Float>>, val guitar: GuitarModel? = null, val waveform: SynthWaveform? = null)

val FxPresets = listOf(
    FxPreset("Clean Funk", AmpModel.CLEAN, listOf(PedalType.COMPRESSOR to 0.5f, PedalType.AUTOWAH to 0.5f), guitar = GuitarModel.STRAT),
    FxPreset("Classic Rock", AmpModel.PLEXI, listOf(PedalType.OVERDRIVE to 0.5f, PedalType.REVERB to 0.2f), guitar = GuitarModel.LESPAUL),
    FxPreset("Metal Rhythm", AmpModel.METAL, listOf(PedalType.NOISE_GATE to 0.4f, PedalType.BOOST to 0.4f, PedalType.METAL_DIST to 0.6f), guitar = GuitarModel.SUPERSTRAT),
    FxPreset("Djent 7", AmpModel.RECTO, listOf(PedalType.NOISE_GATE to 0.6f, PedalType.COMPRESSOR to 0.4f), guitar = GuitarModel.SEVEN),
    FxPreset("Blues Lead", AmpModel.TWEED, listOf(PedalType.OVERDRIVE to 0.35f, PedalType.DELAY to 0.25f, PedalType.REVERB to 0.3f), guitar = GuitarModel.STRAT),
    FxPreset("Jazz Club", AmpModel.JAZZ, listOf(PedalType.COMPRESSOR to 0.3f, PedalType.REVERB to 0.3f), guitar = GuitarModel.JAZZBOX),
    FxPreset("Acoustic Folk", AmpModel.ACOUSTIC_DI, listOf(PedalType.COMPRESSOR to 0.3f, PedalType.REVERB to 0.35f), guitar = GuitarModel.ACOUSTIC),
    FxPreset("Nylon Spanish", AmpModel.ACOUSTIC_DI, listOf(PedalType.REVERB to 0.4f), guitar = GuitarModel.NYLON),
    FxPreset("Shoegaze Wall", AmpModel.FUZZ_STACK, listOf(PedalType.FUZZ to 0.6f, PedalType.FLANGER to 0.5f, PedalType.REVERB to 0.8f), guitar = GuitarModel.TWELVE),
    FxPreset("Wah Solo", AmpModel.BRIT, listOf(PedalType.WAH to 0.7f, PedalType.OVERDRIVE to 0.5f, PedalType.DELAY to 0.3f), guitar = GuitarModel.LESPAUL),
    FxPreset("80s Chorus Clean", AmpModel.CHIME, listOf(PedalType.CHORUS to 0.6f, PedalType.DELAY to 0.35f), guitar = GuitarModel.STRAT),
    FxPreset("Psych Phaser", AmpModel.TWEED, listOf(PedalType.PHASER to 0.6f, PedalType.FUZZ to 0.4f), guitar = GuitarModel.HOLLOW),
    FxPreset("Slide Resonator", AmpModel.CLEAN, listOf(PedalType.VIBRATO to 0.3f, PedalType.REVERB to 0.3f), guitar = GuitarModel.RESO),
    FxPreset("Bass Grit", AmpModel.BASS, listOf(PedalType.COMPRESSOR to 0.5f, PedalType.OVERDRIVE to 0.3f), guitar = GuitarModel.BASS),
    FxPreset("Fretless Smooth", AmpModel.BASS, listOf(PedalType.CHORUS to 0.3f, PedalType.COMPRESSOR to 0.4f), guitar = GuitarModel.FRETLESS),
    FxPreset("Octave Fuzz Bass", AmpModel.BASS, listOf(PedalType.OCTAVER to 0.5f, PedalType.FUZZ to 0.4f), guitar = GuitarModel.JAZZ_BASS),
    FxPreset("Synth Lead Scream", AmpModel.SYNTH_DRIVE, listOf(PedalType.DISTORTION to 0.4f, PedalType.DELAY to 0.4f), waveform = SynthWaveform.SUPERSAW),
    FxPreset("Lo-Fi Keys", AmpModel.NONE, listOf(PedalType.LOFI to 0.6f, PedalType.BITCRUSH to 0.25f, PedalType.REVERB to 0.3f), waveform = SynthWaveform.EPIANO),
    FxPreset("Dreamy Pad", AmpModel.NONE, listOf(PedalType.CHORUS to 0.5f, PedalType.REVERB to 0.7f), waveform = SynthWaveform.STRINGS),
    FxPreset("Reese Destroyer", AmpModel.SYNTH_DRIVE, listOf(PedalType.DISTORTION to 0.5f, PedalType.PHASER to 0.4f), waveform = SynthWaveform.REESE),
    FxPreset("Robot Ring", AmpModel.NONE, listOf(PedalType.RING_MOD to 0.5f, PedalType.BITCRUSH to 0.4f)),
    FxPreset("Vocal Polish", AmpModel.NONE, listOf(PedalType.COMPRESSOR to 0.5f, PedalType.DELAY to 0.15f, PedalType.REVERB to 0.3f)),
)
