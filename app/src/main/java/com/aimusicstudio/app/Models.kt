package com.aimusicstudio.app

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Port of SynthNote.swift */
data class SynthNote(val id: String = UUID.randomUUID().toString(), val pitch: Int, val startTime: Double, val duration: Double, val velocity: Float = 0.8f) {
    val endTime get() = startTime + duration
}

/** Port of AudioClip (inferred from MixerEngine.swift) */
data class AudioClip(
    val id: String = UUID.randomUUID().toString(),
    val fileName: String, val startTime: Double, val duration: Double,
    val sourceStartTime: Double = 0.0, val sourceDuration: Double = duration,
)

/** 3-band EQ like MixerEngine (low shelf / mid peak / high shelf), gains in dB. */
data class TrackEQSettings(val lowGain: Float = 0f, val midGain: Float = 0f, val highGain: Float = 0f)

/** Port of TrackCompressorSettings. */
data class TrackCompressorSettings(
    val isEnabled: Boolean = false, val threshold: Float = -18f, val ratio: Float = 4f,
    val attackMs: Float = 10f, val releaseMs: Float = 120f, val makeupGain: Float = 0f,
)

enum class SynthWaveform(val label: String) { SINE("Sine"), TRIANGLE("Tri"), SAW("Saw"), SQUARE("Square"),
    SUPERSAW("Supersaw Lead"), PULSE("Pulse 25%"), SUB_BASS("Sub Bass"), REESE("Reese Bass"), EPIANO("Electric Piano"), ORGAN("Drawbar Organ"),
    STRINGS("String Pad"), BRASS("Synth Brass"), PLUCK("Pluck"), BELL("FM Bell"), FLUTE("Flute"), CHOIR("Choir Pad") }

/** Port of DrumPad from InstrumentSoundGenerator.swift */
enum class DrumPad(val label: String) {
    KICK("Bass Drum"), SNARE("Snare"), LOW_TOM("Low Tom"), MID_TOM("Mid Tom"), HIGH_TOM("Hi Tom"),
    RIMSHOT("Rimshot"), CLAP("Clap"), COWBELL("Cowbell"), CLOSED_HAT("Closed Hat"), OPEN_HAT("Open Hat"), CYMBAL("Cymbal"),
    RIDE("Ride"), CRASH("Crash")
}

/** 16-step DrumPattern. */
data class DrumPattern(val steps: Map<DrumPad, List<Boolean>> = DrumPad.values().associateWith { List(16) { false } }, val isEnabled: Boolean = true) {
    fun toggled(pad: DrumPad, step: Int): DrumPattern {
        val row = steps[pad]!!.toMutableList(); row[step] = !row[step]
        return copy(steps = steps + (pad to row))
    }
}

enum class TrackKind { AUDIO, SYNTH, GUITAR, LYRICS }

data class Track(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val kind: TrackKind = TrackKind.AUDIO,
    val clips: List<AudioClip> = emptyList(),
    val notes: List<SynthNote> = emptyList(),
    val waveform: SynthWaveform = SynthWaveform.SAW,
    val volume: Float = 0.8f, val pan: Float = 0f,
    val isMuted: Boolean = false, val isSoloed: Boolean = false,
    val eq: TrackEQSettings = TrackEQSettings(),
    val compressor: TrackCompressorSettings = TrackCompressorSettings(),
    val pedals: List<PedalSlot> = emptyList(),
    val amp: AmpModel = AmpModel.NONE,
    val guitar: GuitarModel = GuitarModel.STRAT,
    val reverbSend: Float = 0f, val delaySend: Float = 0f,
    val volAuto: List<AutoPoint> = emptyList(), val panAuto: List<AutoPoint> = emptyList(),
    val ampGain: Float = 0.5f, val ampBass: Float = 0.5f, val ampMid: Float = 0.5f, val ampTreble: Float = 0.5f, val ampPresence: Float = 0.5f, val ampMaster: Float = 0.7f,
    val kit: DrumKit = DrumKit.ROCK,
    val lyrics: String = "", val vocalStyle: VocalStyle = VocalStyle.POP, val voiceGender: VoiceGender = VoiceGender.FEMALE, val songInfo: String = "", val genre: String = "Pop",
)
/** Delivery styles for the device's natural (neural where installed) TTS voice. Pitch/rate are gentle so the voice stays human. */
enum class VocalStyle(val label: String, val pitch: Float, val rate: Float) { POP("Pop – bright", 1.08f, 0.95f), SOUL("Soul / R&B – warm", 0.95f, 0.82f),
    ROCK("Rock – punchy", 1.0f, 1.0f), BALLAD("Ballad – slow, soft", 1.0f, 0.72f), RAP("Hip-hop – rhythmic", 1.0f, 1.25f), COUNTRY("Country – storyteller", 0.97f, 0.88f) }
enum class VoiceGender(val label: String) { FEMALE("Female"), MALE("Male"), NEUTRAL("Any / neutral") }

/** Port of StudioSession.swift */
data class StudioSession(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Untitled Session",
    val tracks: List<Track> = emptyList(),
    val tempoBPM: Int = 120,
    val drumPattern: DrumPattern = DrumPattern(),
    val loopStart: Double = 0.0, val loopEnd: Double = 0.0, val isLoopEnabled: Boolean = false,
    val masterVolume: Float = 1f,
    val kit: DrumKit = DrumKit.ROCK,
    val timeSigNum: Int = 4, val timeSigDen: Int = 4,
    val reverbReturn: Float = 0.8f, val delayReturn: Float = 0.8f, val delayTimeBeats: Float = 0.75f,
    val lastEdited: Long = System.currentTimeMillis(),
) {
    val stepSeconds get() = 60.0 / tempoBPM / 4.0
    val barSeconds get() = stepSeconds * timeSigNum * (16.0 / timeSigDen)
    /** Timeline length: end of last clip/note, at least 2 bars. */
    val length: Double get() {
        val ends = tracks.flatMap { t -> t.clips.map { it.startTime + it.duration } + t.notes.map { it.endTime } }
        return maxOf(ends.maxOrNull() ?: 0.0, barSeconds * 2)
    }
}

/** JSON encoding, the Android equivalent of Codable + StudioSessionStorage. Missing keys fall back to defaults like the Swift decoder. */
object SessionJson {
    fun encode(s: StudioSession): JSONObject = JSONObject().apply {
        put("id", s.id); put("name", s.name); put("tempoBPM", s.tempoBPM)
        put("loopStart", s.loopStart); put("loopEnd", s.loopEnd); put("isLoopEnabled", s.isLoopEnabled)
        put("masterVolume", s.masterVolume.toDouble()); put("lastEdited", s.lastEdited)
        put("kit", s.kit.name); put("tsN", s.timeSigNum); put("tsD", s.timeSigDen); put("revRet", s.reverbReturn.toDouble()); put("dlyRet", s.delayReturn.toDouble()); put("dlyBeats", s.delayTimeBeats.toDouble())
        put("drumPattern", JSONObject().apply {
            put("isEnabled", s.drumPattern.isEnabled)
            s.drumPattern.steps.forEach { (p, r) -> put(p.name, JSONArray(r)) }
        })
        put("tracks", JSONArray(s.tracks.map { t ->
            JSONObject().apply {
                put("id", t.id); put("name", t.name); put("kind", t.kind.name); put("waveform", t.waveform.name)
                put("volume", t.volume.toDouble()); put("pan", t.pan.toDouble()); put("isMuted", t.isMuted); put("isSoloed", t.isSoloed)
                put("eq", JSONArray(listOf(t.eq.lowGain, t.eq.midGain, t.eq.highGain).map { it.toDouble() }))
                val c = t.compressor
                put("comp", JSONObject().apply { put("on", c.isEnabled); put("th", c.threshold.toDouble()); put("ra", c.ratio.toDouble()); put("at", c.attackMs.toDouble()); put("re", c.releaseMs.toDouble()); put("mk", c.makeupGain.toDouble()) })
                put("clips", JSONArray(t.clips.map { JSONObject().apply { put("id", it.id); put("fileName", it.fileName); put("startTime", it.startTime); put("duration", it.duration); put("sourceStartTime", it.sourceStartTime); put("sourceDuration", it.sourceDuration) } }))
                put("pedals", JSONArray(t.pedals.map { JSONObject().apply { put("t", it.type.name); put("on", it.on); put("a", it.amount.toDouble()); put("tn", it.tone.toDouble()) } }))
                put("lyrics", t.lyrics); put("vox", t.vocalStyle.name); put("vg", t.voiceGender.name); put("songInfo", t.songInfo); put("genre", t.genre); put("amp", t.amp.name); put("guitar", t.guitar.name); put("rvs", t.reverbSend.toDouble()); put("dls", t.delaySend.toDouble())
                put("ampK", JSONArray(listOf(t.ampGain, t.ampBass, t.ampMid, t.ampTreble, t.ampPresence, t.ampMaster).map { it.toDouble() })); put("tkit", t.kit.name)
                put("volAuto", JSONArray(t.volAuto.map { JSONArray(listOf(it.time, it.value.toDouble())) })); put("panAuto", JSONArray(t.panAuto.map { JSONArray(listOf(it.time, it.value.toDouble())) }))
                put("notes", JSONArray(t.notes.map { JSONObject().apply { put("id", it.id); put("pitch", it.pitch); put("startTime", it.startTime); put("duration", it.duration); put("velocity", it.velocity.toDouble()) } }))
            }
        }))
    }

    fun decode(o: JSONObject): StudioSession {
        val dp = o.optJSONObject("drumPattern")
        val steps = DrumPad.values().associateWith { p ->
            val a = dp?.optJSONArray(p.name); List(16) { a?.optBoolean(it) ?: false }
        }
        val tracks = o.optJSONArray("tracks")?.let { arr -> (0 until arr.length()).map { i ->
            val t = arr.getJSONObject(i); val eq = t.optJSONArray("eq"); val c = t.optJSONObject("comp") ?: JSONObject()
            Track(
                id = t.optString("id", UUID.randomUUID().toString()), name = t.optString("name", "Track"),
                kind = runCatching { TrackKind.valueOf(t.optString("kind")) }.getOrDefault(TrackKind.AUDIO),
                waveform = runCatching { SynthWaveform.valueOf(t.optString("waveform")) }.getOrDefault(SynthWaveform.SAW),
                volume = t.optDouble("volume", 0.8).toFloat(), pan = t.optDouble("pan", 0.0).toFloat(),
                isMuted = t.optBoolean("isMuted"), isSoloed = t.optBoolean("isSoloed"),
                eq = TrackEQSettings((eq?.optDouble(0) ?: 0.0).toFloat(), (eq?.optDouble(1) ?: 0.0).toFloat(), (eq?.optDouble(2) ?: 0.0).toFloat()),
                compressor = TrackCompressorSettings(c.optBoolean("on"), c.optDouble("th", -18.0).toFloat(), c.optDouble("ra", 4.0).toFloat(), c.optDouble("at", 10.0).toFloat(), c.optDouble("re", 120.0).toFloat(), c.optDouble("mk", 0.0).toFloat()),
                clips = t.optJSONArray("clips")?.let { ca -> (0 until ca.length()).map { j -> val x = ca.getJSONObject(j)
                    AudioClip(x.optString("id"), x.getString("fileName"), x.optDouble("startTime"), x.optDouble("duration"), x.optDouble("sourceStartTime", 0.0), x.optDouble("sourceDuration", x.optDouble("duration"))) } } ?: emptyList(),
                pedals = t.optJSONArray("pedals")?.let { pa -> (0 until pa.length()).mapNotNull { j -> val x = pa.getJSONObject(j)
                    runCatching { PedalSlot(PedalType.valueOf(x.getString("t")), x.optBoolean("on", true), x.optDouble("a", 0.5).toFloat(), x.optDouble("tn", 0.5).toFloat()) }.getOrNull() } } ?: emptyList(),
                lyrics = t.optString("lyrics", ""), songInfo = t.optString("songInfo", ""), genre = t.optString("genre", "Pop"),
                vocalStyle = runCatching { VocalStyle.valueOf(t.optString("vox")) }.getOrDefault(VocalStyle.POP), voiceGender = runCatching { VoiceGender.valueOf(t.optString("vg")) }.getOrDefault(VoiceGender.FEMALE),
                amp = runCatching { AmpModel.valueOf(t.optString("amp")) }.getOrDefault(AmpModel.NONE),
                guitar = runCatching { GuitarModel.valueOf(t.optString("guitar")) }.getOrDefault(GuitarModel.STRAT),
                ampGain = (t.optJSONArray("ampK")?.optDouble(0, 0.5) ?: 0.5).toFloat(), ampBass = (t.optJSONArray("ampK")?.optDouble(1, 0.5) ?: 0.5).toFloat(),
                ampMid = (t.optJSONArray("ampK")?.optDouble(2, 0.5) ?: 0.5).toFloat(), ampTreble = (t.optJSONArray("ampK")?.optDouble(3, 0.5) ?: 0.5).toFloat(),
                ampPresence = (t.optJSONArray("ampK")?.optDouble(4, 0.5) ?: 0.5).toFloat(), ampMaster = (t.optJSONArray("ampK")?.optDouble(5, 0.7) ?: 0.7).toFloat(),
                kit = runCatching { DrumKit.valueOf(t.optString("tkit")) }.getOrDefault(runCatching { DrumKit.valueOf(o.optString("kit")) }.getOrDefault(DrumKit.ROCK)),
                reverbSend = t.optDouble("rvs", 0.0).toFloat(), delaySend = t.optDouble("dls", 0.0).toFloat(),
                volAuto = autoList(t.optJSONArray("volAuto")), panAuto = autoList(t.optJSONArray("panAuto")),
                notes = t.optJSONArray("notes")?.let { na -> (0 until na.length()).map { j -> val x = na.getJSONObject(j)
                    SynthNote(x.optString("id"), x.getInt("pitch"), x.optDouble("startTime"), x.optDouble("duration"), x.optDouble("velocity", 0.8).toFloat()) } } ?: emptyList(),
            )
        } } ?: emptyList()
        return StudioSession(
            id = o.optString("id", UUID.randomUUID().toString()), name = o.optString("name", "Untitled Session"),
            tracks = tracks, tempoBPM = o.optInt("tempoBPM", 120),
            drumPattern = DrumPattern(steps, dp?.optBoolean("isEnabled", true) ?: true),
            loopStart = o.optDouble("loopStart", 0.0), loopEnd = o.optDouble("loopEnd", 0.0), isLoopEnabled = o.optBoolean("isLoopEnabled"),
            masterVolume = o.optDouble("masterVolume", 1.0).toFloat(), lastEdited = o.optLong("lastEdited", System.currentTimeMillis()),
            kit = runCatching { DrumKit.valueOf(o.optString("kit")) }.getOrDefault(DrumKit.ROCK),
            timeSigNum = o.optInt("tsN", 4), timeSigDen = o.optInt("tsD", 4),
            reverbReturn = o.optDouble("revRet", 0.8).toFloat(), delayReturn = o.optDouble("dlyRet", 0.8).toFloat(), delayTimeBeats = o.optDouble("dlyBeats", 0.75).toFloat(),
        )
    }
    private fun autoList(a: JSONArray?): List<AutoPoint> = if (a == null) emptyList() else (0 until a.length()).map { val p = a.getJSONArray(it); AutoPoint(p.getDouble(0), p.getDouble(1).toFloat()) }
    private fun unused() {
    }
}

/** Automation breakpoint (seconds, value). Volume 0..1, pan -1..1. */
data class AutoPoint(val time: Double, val value: Float)

enum class PedalType(val label: String, val color: Long) {
    OVERDRIVE("Tube Screamer-style Overdrive", 0xFFE0B020), DISTORTION("RAT-style Distortion", 0xFFD04020), CHORUS("Boss-style CE-2 Chorus", 0xFF3080E0),
    DELAY("Carbon Copy-style Delay", 0xFF20A060), REVERB("BigSky-style Reverb", 0xFF8050D0), WAH("Cry Baby-style Wah", 0xFFE07020), COMPRESSOR("Dyna Comp-style Compressor", 0xFF4090A0), TREMOLO("Fender-style Tremolo", 0xFFC03080),
    FUZZ("Big Muff-style Fuzz", 0xFF9A6A20), METAL_DIST("Boss-style Metal Zone", 0xFF707070), BOOST("Clean Boost", 0xFFE0E0E0), PHASER("Phase 90-style Phaser", 0xFFE04090), FLANGER("Electric Mistress-style Flanger", 0xFF40A0C0),
    VIBRATO("Vibrato", 0xFF7070E0), OCTAVER("POG-style Octaver", 0xFF60C060), BITCRUSH("Bitcrusher", 0xFF80A020), AUTOWAH("Mu-Tron-style Envelope Filter", 0xFFF09030), RING_MOD("Ring Mod", 0xFFB050B0),
    NOISE_GATE("Decimator-style Noise Gate", 0xFF8080A0), LOFI("Lo-Fi Tape", 0xFFA08060)
}
data class PedalSlot(val type: PedalType, val on: Boolean = true, val amount: Float = 0.5f, val tone: Float = 0.5f, val id: String = UUID.randomUUID().toString())

enum class AmpModel(val label: String, val gain: Float, val bass: Float, val treble: Float) {
    NONE("No amp", 0f, 0f, 0f), CLEAN("Clean Combo", 1.5f, 1f, 2f), BRIT("British Crunch", 6f, 2f, 3f),
    HIGAIN("High-Gain Stack", 18f, 4f, 4f), BASS("Bass Amp", 2f, 6f, -4f), TWEED("Tweed '59", 4f, 3f, 1f),
    CHIME("Chime AC30", 3f, 0f, 5f), JAZZ("Jazz Clean", 0.8f, 3f, -3f), METAL("Metal 5150", 28f, 5f, 5f), RECTO("Modern Recto", 22f, 6f, 3f), PLEXI("Plexi '68", 9f, 2f, 4f),
    FUZZ_STACK("Fuzz Stack", 35f, 3f, 1f), ACOUSTIC_DI("Acoustic DI", 0.3f, 1f, 3f), SYNTH_DRIVE("Synth Drive", 5f, 2f, 2f),
    TWIN("Fender-style Twin Reverb", 1.2f, 1f, 3f), DELUXE("Fender-style Deluxe", 2.5f, 2f, 2f), JCM800("Marshall-style JCM800 + 4x12", 12f, 3f, 4f), JTM45("Marshall-style JTM45", 7f, 3f, 3f),
    DUAL_RECT("Mesa-style Dual Rectifier + 4x12", 24f, 6f, 3f), MARK_IIC("Mesa-style Mark IIC+", 16f, 3f, 5f), ORANGE_ROCKERVERB("Orange-style Rockerverb", 14f, 4f, 3f),
    PEAVEY_6505("Peavey-style 6505 Head", 26f, 5f, 4f), EVH("EVH-style 5150 III", 22f, 4f, 5f), DIEZEL("Diezel-style VH4", 25f, 5f, 4f), BOGNER("Bogner-style Uberschall", 20f, 5f, 3f),
    ROLAND_JC("Roland-style JC-120 Jazz Chorus", 0.6f, 1f, 3f), SOLDANO("Soldano-style SLO-100", 18f, 3f, 4f), FRIEDMAN("Friedman-style BE-100", 13f, 3f, 4f), DUMBLE("Dumble-style ODS", 6f, 2f, 3f),
    AMPEG_SVT("Ampeg-style SVT + 8x10", 3f, 7f, -3f), GALLIEN("Gallien-Krueger-style 800RB", 2.5f, 5f, 1f), DARKGLASS("Darkglass-style B7K Bass", 8f, 6f, 2f), LESLIE("Leslie-style Rotary Cab", 1.5f, 2f, 1f)
}
enum class GuitarModel(val label: String, val brightness: Float, val body: Float) {
    STRAT("Fender-style Strat", 0.6f, 0.3f), LESPAUL("Gibson-style Les Paul", 0.35f, 0.6f), TELE("Fender-style Tele", 0.75f, 0.2f), HOLLOW("Gibson-style ES-335", 0.25f, 0.8f), ACOUSTIC("Martin-style Dreadnought", 0.5f, 0.9f), BASS("Fender-style P-Bass", 0.2f, 0.7f),
    SUPERSTRAT("Ibanez-style Superstrat", 0.55f, 0.4f), SEVEN("Schecter-style 7-String", 0.4f, 0.5f), JAZZBOX("Gibson-style L-5 Archtop", 0.15f, 0.9f), NYLON("Yamaha-style Nylon", 0.3f, 1.0f),
    TWELVE("Rickenbacker-style 12-String", 0.85f, 0.7f), RESO("Dobro-style Resonator", 0.9f, 0.6f), JAZZ_BASS("Fender-style Jazz Bass", 0.35f, 0.5f), FRETLESS("Fretless Bass", 0.1f, 0.6f),
    SG("Gibson-style SG", 0.45f, 0.45f), EXPLORER("Gibson-style Explorer", 0.5f, 0.5f), FLYING_V("Gibson-style Flying V", 0.5f, 0.45f), PRS("PRS-style Custom 24", 0.5f, 0.5f),
    JAZZMASTER("Fender-style Jazzmaster", 0.65f, 0.35f), GRETSCH("Gretsch-style Hollowbody", 0.4f, 0.75f), JACKSON("Jackson-style Soloist", 0.6f, 0.35f), ESP("ESP-style Eclipse", 0.45f, 0.55f),
    TAYLOR("Taylor-style Grand Auditorium", 0.6f, 0.85f), MUSICMAN("Music Man-style StingRay Bass", 0.4f, 0.6f), RICK_BASS("Rickenbacker-style 4003 Bass", 0.55f, 0.55f),
    THUNDERBIRD("Gibson-style Thunderbird Bass", 0.3f, 0.75f), IBANEZ_SR("Ibanez-style SR 5-String Bass", 0.45f, 0.5f), HOFNER("Hofner-style Violin Bass", 0.25f, 0.8f)
}
enum class DrumKit(val label: String) { ROCK("Rock"), TR808("808"), ACOUSTIC("Acoustic Jazz"), ELECTRONIC("Electronic"), LOFI("Lo-Fi"), TRAP("Trap"), METAL("Metal"), HOUSE("House"), JAZZ_BRUSH("Jazz Brushes"), DNB("Drum & Bass"), VINTAGE909("909"), PROG_ARENA("Arena Prog Kit (big toms, Peart-inspired)"), MASK_METAL("Mask Metal Kit (triggered double-kick, Jordison-inspired)"), DJENT("Djent Tight Kit"),
    PEARL_STAGE("Pearl-inspired Stage Kit"), DW_COLLECTOR("DW-inspired Collector Kit"), SABIAN_AA("Sabian-inspired AA Cymbal Kit") }
enum class MixerLayout(val label: String) { CONSOLE("Full Console"), COMPACT("Compact"), BUS("Bus / Sends") }
