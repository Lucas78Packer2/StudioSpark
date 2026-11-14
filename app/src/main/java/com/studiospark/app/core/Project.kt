package com.studiospark.app.core

import com.studiospark.app.dsp.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

const val ENGINE_SR = 48000

fun newId(): String = UUID.randomUUID().toString().substring(0, 8)

/**
 * A non-destructive window onto pooled audio. [printed] clips already carry the track's amp/pedal tone (captured post-rig),
 * so playback bypasses the rig for them and the effects are never applied twice.
 */
class Clip(val id: String = newId(), var audioId: String, var start: Long, var offset: Long = 0, var length: Long, var gainDb: Float = 0f, var rate: Float = 1f, var name: String = "Clip", var takeId: String? = null,
           var printed: Boolean = false,
           /** Optional fades in timeline frames (0 = only the built-in 2 ms click guard). Non-destructive: applied at mix time. */
           var fadeIn: Long = 0, var fadeOut: Long = 0) {
    val end get() = start + (length / rate).toLong()
    val duration get() = end - start
    fun copy(id: String = this.id) = Clip(id, audioId, start, offset, length, gainDb, rate, name, takeId, printed, fadeIn, fadeOut)
}

/** Per-track play/pause rules (pure, so they are testable). The track never gets its own playhead: it follows the transport. */
object TrackPlay {
    enum class Action { UNPAUSE, UNPAUSE_AND_START, START, PAUSE }
    /** What tapping a track's play/pause button does. Never arms or records. */
    fun tap(paused: Boolean, rolling: Boolean): Action = when { paused && rolling -> Action.UNPAUSE; paused -> Action.UNPAUSE_AND_START; !rolling -> Action.START; else -> Action.PAUSE }
    /** Whether a track's clips/notes sound at this moment. */
    fun sounds(paused: Boolean, rolling: Boolean) = rolling && !paused
}

/** Non-destructive clip trimming: only the window (start/offset/length) and fades change; pooled audio is never rewritten. */
object ClipEdit {
    /** Shortest clip a trim may leave (10 ms). */
    const val MIN_LEN = ENGINE_SR / 100L
    /** Moves the left edge to timeline [newStart]: start, offset and length change together so the audio stays in place. */
    fun trimLeft(c: Clip, newStart: Long) {
        var d = newStart - c.start                                     // timeline frames (+ = shorten)
        val minD = -(c.offset / c.rate).toLong()                       // cannot reveal audio before the source start
        val maxD = ((c.length - MIN_LEN) / c.rate).toLong()            // keep at least MIN_LEN
        d = d.coerceIn(minD, maxD).coerceAtLeast(-c.start)             // and never before 0 on the timeline
        val src = (d * c.rate).toLong()
        c.start += d; c.offset = (c.offset + src).coerceAtLeast(0); c.length -= src; clampFades(c)
    }
    /** Moves the right edge to timeline [newEnd], limited by the pooled audio length [srcLen]. */
    fun trimRight(c: Clip, newEnd: Long, srcLen: Long) {
        val want = ((newEnd - c.start) * c.rate).toLong()
        c.length = want.coerceIn(MIN_LEN, (srcLen - c.offset).coerceAtLeast(MIN_LEN)); clampFades(c)
    }
    fun clampFades(c: Clip) { val d = c.duration; c.fadeIn = c.fadeIn.coerceIn(0, d); c.fadeOut = c.fadeOut.coerceIn(0, d - c.fadeIn) }
    /** Source-frame window of non-silent audio inside the clip, or null if all silent. [thresholdDb] dBFS, [padFrames] kept around the sound. */
    fun soundBounds(data: ShortArray, c: Clip, thresholdDb: Float = -45f, padFrames: Long = ENGINE_SR / 100L): LongRange? {
        val thr = (32768.0 * Math.pow(10.0, thresholdDb / 20.0)).toInt()
        val from = c.offset.coerceIn(0, data.size.toLong()).toInt(); val to = (c.offset + c.length).coerceIn(0, data.size.toLong()).toInt()
        var first = -1; var last = -1
        for (i in from until to) if (kotlin.math.abs(data[i].toInt()) >= thr) { first = i; break }
        if (first < 0) return null
        for (i in to - 1 downTo from) if (kotlin.math.abs(data[i].toInt()) >= thr) { last = i; break }
        return (first - padFrames).coerceAtLeast(from.toLong())..(last + padFrames).coerceAtMost(to.toLong() - 1)
    }
    /** Trims leading and trailing silence (non-destructive). Returns false if the clip is all silence or already tight. */
    fun silenceTrim(c: Clip, data: ShortArray, thresholdDb: Float = -45f): Boolean {
        val b = soundBounds(data, c, thresholdDb) ?: return false
        val oldS = c.start; val oldL = c.length
        val newStartTl = c.start + ((b.first - c.offset) / c.rate).toLong()
        val newEndTl = c.start + ((b.last + 1 - c.offset) / c.rate).toLong()
        trimRight(c, newEndTl, data.size.toLong()); trimLeft(c, newStartTl)
        return c.start != oldS || c.length != oldL
    }
}

data class MidiNote(val pitch: Int, val start: Long, val length: Long, val velocity: Int)
data class AutoPoint(val at: Long, val db: Float)

/** A recorded pass. Takes are private until the user Places them; Discard removes them. Placed takes stay in the track's playlist (non-destructive). */
class Take(val id: String = newId(), val audioId: String, var trackId: String?, val start: Long, val length: Long, val name: String, val source: String, val createdAt: Long = System.currentTimeMillis(), var placed: Boolean = false, var savedToLibrary: Boolean = false, val notes: MutableList<MidiNote> = mutableListOf(),
           /** Captured after the track's rig ("Print processed tone"). */ var printed: Boolean = false)

class Track(
    val id: String = newId(), var name: String, var family: Family, var instrumentId: String, var color: Long,
    var volumeDb: Float = 0f, var pan: Float = 0f, var mute: Boolean = false, var solo: Boolean = false,
    var recArm: Boolean = false, var inputMonitor: Boolean = false, var inputSource: String = "phone_mic",
    val clips: MutableList<Clip> = mutableListOf(), val notes: MutableList<MidiNote> = mutableListOf(), val automation: MutableList<AutoPoint> = mutableListOf(),
    var rig: Rig = RigPresets.defaultFor(family), val playlist: MutableList<String> = mutableListOf(),
    var kitId: String? = null, var tuning: String? = null, var lyrics: MutableList<String> = mutableListOf(), var voiceStyle: String = "Pop · warm",
    var automationOn: Boolean = false,
    /** Guitar/Bass source: false = the on-screen/MIDI instrument, true = the mic/interface input through this track's rig. */
    var useMic: Boolean = false,
    /** Mic recordings on this track are captured post-rig (amp, pedals, FX printed) and play back with the rig bypassed. Default on (GitHub issue 1). */
    var printTone: Boolean = true,
    /** Per-track pause: the track follows the global transport but stays silent (clips and MIDI) while paused. */
    var paused: Boolean = false,
)

class Project(
    var name: String = "Untitled Session", var tempo: Float = 120f, var sigNum: Int = 4, var sigDen: Int = 4,
    val tracks: MutableList<Track> = mutableListOf(), val takes: MutableList<Take> = mutableListOf(),
    var loopStart: Long = 0, var loopEnd: Long = 0, var punchIn: Long = 0, var punchOut: Long = 0,
    var trackCount: Int = 8, var customKits: MutableList<DrumKit> = mutableListOf(), var customInstruments: MutableList<InstrumentDef> = mutableListOf(), var stringBuilds: MutableList<StringBuild> = mutableListOf(),
    var room: MutableList<RoomItem> = mutableListOf(), var cords: MutableList<Cord> = mutableListOf(), var camX: Float = 0f, var camY: Float = 0f, var camZoom: Float = 1f,
) {
    val samplesPerBeat get() = (ENGINE_SR * 60.0 / tempo).toLong()
    val samplesPerBar get() = samplesPerBeat * sigNum * 4 / sigDen
    fun barsBeats(pos: Long): String { val beat = pos / samplesPerBeat.toDouble(); val bar = (beat / (sigNum * 4.0 / sigDen)).toInt() + 1; val inBar = beat % (sigNum * 4.0 / sigDen); val b = inBar.toInt() + 1; val tick = ((inBar - inBar.toInt()) * 960).toInt(); return String.format("%3d| %d| %03d", bar, b, tick) }
    fun snap(pos: Long, grid: Long = samplesPerBar): Long = ((pos + grid / 2) / grid) * grid
    fun track(id: String?) = tracks.firstOrNull { it.id == id }
    /** Every instrument the user can pick anywhere: stock, saved kits/instruments, and saved guitar/bass builds (live from their build spec). */
    fun allInstruments() = InstrumentLibrary.stock + customInstruments + stringBuilds.filter { it.saved }.map { StringCatalog.toInstrument(it) }
    fun instrument(id: String) = allInstruments().firstOrNull { it.id == id } ?: stringBuilds.firstOrNull { StringCatalog.instrumentId(it) == id }?.let { StringCatalog.toInstrument(it) } ?: InstrumentLibrary.stock.first()
    fun build(id: String?) = stringBuilds.firstOrNull { it.id == id }
    val length: Long get() = maxOf(samplesPerBar * 16, tracks.flatMap { it.clips }.maxOfOrNull { it.end } ?: 0L, tracks.flatMap { it.notes }.maxOfOrNull { it.start + it.length } ?: 0L)
}

/** A basic studio: no tracks, no gear, nothing preloaded. Instruments and gear are added by the user. */
object Sessions { fun blank(): Project = Project(name = "New Session") }

class RoomItem(val id: String, val kind: String, val refId: String, var x: Float, var y: Float, var scale: Float = 1f, var values: FloatArray? = null, var enabled: Boolean = true)
data class Cord(val from: String, val to: String)

object TrackColors { val all = listOf(0xFF4F7CAC, 0xFFB5523B, 0xFF5E9A6B, 0xFFC09A3E, 0xFF8467A8, 0xFF3E8E92, 0xFFAD5C82, 0xFF7A8A99) }

// ------------------------------------------------------------ JSON persistence
object ProjectJson {
    fun rig(r: Rig) = JSONObject().put("in", r.inputGainDb.toDouble()).put("out", r.outputGainDb.toDouble()).put("power", r.power).put("preset", r.presetName)
        .put("slots", JSONArray().apply { r.slots.forEach { s -> put(JSONObject().put("gear", s.gearId).put("on", s.enabled).put("v", JSONArray().apply { s.values.forEach { put(it.toDouble()) } })) } })
    fun rig(o: JSONObject): Rig { val r = Rig(inputGainDb = o.optDouble("in", 0.0).toFloat(), outputGainDb = o.optDouble("out", 0.0).toFloat(), power = o.optBoolean("power", true), presetName = o.optString("preset", "Init"))
        val a = o.optJSONArray("slots") ?: JSONArray()
        for (i in 0 until a.length()) { val s = a.getJSONObject(i); val id = s.getString("gear"); val def = GearCatalog.find(id) ?: continue; val v = s.getJSONArray("v")
            val vals = def.defaults(); for (k in 0 until minOf(vals.size, v.length())) vals[k] = v.getDouble(k).toFloat(); r.slots.add(RigSlot(RigSlot.next(), id, vals, s.optBoolean("on", true))) }
        return r }
    fun kit(k: DrumKit) = JSONObject().put("id", k.id).put("name", k.name).put("stick", k.stick.name).put("base", k.baseId ?: "").put("pieces", JSONArray().apply { k.pieces.forEach { p ->
        put(JSONObject().put("id", p.id).put("name", p.name).put("s", p.sample).put("x", p.x.toDouble()).put("y", p.y.toDouble()).put("z", p.size.toDouble()).put("t", p.tune.toDouble()).put("g", p.gainDb.toDouble()).put("c", p.chokeGroup).put("m", p.midi).put("p", p.pan.toDouble()).put("cat", p.catalogId).put("f", p.finish).put("role", p.role).put("link", p.link)) } })
        .put("shell", k.shellFinish).put("cym", k.cymbalFinish).put("starter", k.starterId)
    fun kit(o: JSONObject): DrumKit { val a = o.getJSONArray("pieces"); return DrumKit(o.getString("id"), o.getString("name"), (0 until a.length()).map { val p = a.getJSONObject(it)
        KitPiece(p.getString("id"), p.getString("name"), p.getString("s"), p.getDouble("x").toFloat(), p.getDouble("y").toFloat(), p.getDouble("z").toFloat(), p.optDouble("t", 0.0).toFloat(), p.optDouble("g", 0.0).toFloat(), p.optInt("c"), p.optInt("m", 36), p.optDouble("p", 0.0).toFloat(),
            p.optString("cat"), p.optString("f"), p.optString("role"), p.optString("link")) },
        runCatching { Stick.valueOf(o.optString("stick")) }.getOrDefault(Stick.S5A), o.optString("base").ifBlank { null }, true,
        o.optString("shell").ifBlank { "natural_maple" }, o.optString("cym").ifBlank { "traditional" }, o.optString("starter")) }
    private fun inst(d: InstrumentDef) = JSONObject().put("id", d.id).put("name", d.name).put("family", d.family.name).put("surface", d.surface.name).put("source", d.source.name).put("voice", d.voice).put("art", d.art).put("cat", d.category).put("tuning", d.tuning ?: "").put("strings", d.strings ?: "").put("base", d.baseId ?: "").put("bright", d.brightness.toDouble())
    private fun inst(o: JSONObject) = InstrumentDef(o.getString("id"), o.getString("name"), Family.valueOf(o.getString("family")), Surface.valueOf(o.getString("surface")), SoundSource.valueOf(o.getString("source")), o.getString("voice"), o.getString("art"), o.optString("cat", "Custom"), true, o.optString("tuning").ifBlank { null }, o.optString("strings").ifBlank { null }, o.optString("base").ifBlank { null }, o.optDouble("bright", 0.5).toFloat())

    fun write(p: Project): String = JSONObject().apply {
        put("format", "studiospark-pro/1"); put("name", p.name); put("tempo", p.tempo.toDouble()); put("sigNum", p.sigNum); put("sigDen", p.sigDen)
        put("loopStart", p.loopStart); put("loopEnd", p.loopEnd); put("punchIn", p.punchIn); put("punchOut", p.punchOut); put("trackCount", p.trackCount)
        put("cam", JSONArray().put(p.camX.toDouble()).put(p.camY.toDouble()).put(p.camZoom.toDouble()))
        put("tracks", JSONArray().apply { p.tracks.forEach { t -> put(JSONObject().apply {
            put("id", t.id); put("name", t.name); put("family", t.family.name); put("inst", t.instrumentId); put("color", t.color); put("vol", t.volumeDb.toDouble()); put("pan", t.pan.toDouble())
            put("mute", t.mute); put("solo", t.solo); put("arm", t.recArm); put("mon", t.inputMonitor); put("input", t.inputSource); put("kit", t.kitId ?: ""); put("tuning", t.tuning ?: "")
            put("voice", t.voiceStyle); put("autoOn", t.automationOn); put("useMic", t.useMic); put("print", t.printTone); put("paused", t.paused); put("lyrics", JSONArray(t.lyrics)); put("playlist", JSONArray(t.playlist)); put("rig", rig(t.rig))
            put("clips", JSONArray().apply { t.clips.forEach { c -> put(JSONObject().put("id", c.id).put("a", c.audioId).put("s", c.start).put("o", c.offset).put("l", c.length).put("g", c.gainDb.toDouble()).put("r", c.rate.toDouble()).put("n", c.name).put("take", c.takeId ?: "").put("pr", c.printed).put("fi", c.fadeIn).put("fo", c.fadeOut)) } })
            put("notes", JSONArray().apply { t.notes.forEach { n -> put(JSONArray().put(n.pitch).put(n.start).put(n.length).put(n.velocity)) } })
            put("auto", JSONArray().apply { t.automation.forEach { a -> put(JSONArray().put(a.at).put(a.db.toDouble())) } })
        }) } })
        put("takes", JSONArray().apply { p.takes.forEach { k -> put(JSONObject().put("id", k.id).put("a", k.audioId).put("track", k.trackId ?: "").put("s", k.start).put("l", k.length).put("n", k.name).put("src", k.source).put("at", k.createdAt).put("placed", k.placed).put("lib", k.savedToLibrary).put("pr", k.printed).put("notes", JSONArray().apply { k.notes.forEach { n -> put(JSONArray().put(n.pitch).put(n.start).put(n.length).put(n.velocity)) } })) } })
        put("kits", JSONArray().apply { p.customKits.forEach { put(kit(it)) } })
        put("insts", JSONArray().apply { p.customInstruments.forEach { put(inst(it)) } })
        put("builds", JSONArray().apply { p.stringBuilds.forEach { put(StringCatalog.toJson(it)) } })
        put("room", JSONArray().apply { p.room.forEach { r -> put(JSONObject().put("id", r.id).put("k", r.kind).put("ref", r.refId).put("x", r.x.toDouble()).put("y", r.y.toDouble()).put("sc", r.scale.toDouble()).put("on", r.enabled).put("v", JSONArray().apply { r.values?.forEach { put(it.toDouble()) } })) } })
        put("cords", JSONArray().apply { p.cords.forEach { put(JSONArray().put(it.from).put(it.to)) } })
    }.toString()

    fun read(s: String): Project { val o = JSONObject(s)
        val p = Project(o.optString("name", "Untitled Session"), o.optDouble("tempo", 120.0).toFloat(), o.optInt("sigNum", 4), o.optInt("sigDen", 4))
        p.loopStart = o.optLong("loopStart"); p.loopEnd = o.optLong("loopEnd"); p.punchIn = o.optLong("punchIn"); p.punchOut = o.optLong("punchOut"); p.trackCount = o.optInt("trackCount", 8)
        o.optJSONArray("cam")?.let { p.camX = it.getDouble(0).toFloat(); p.camY = it.getDouble(1).toFloat(); p.camZoom = it.getDouble(2).toFloat() }
        val ta = o.optJSONArray("tracks") ?: JSONArray()
        for (i in 0 until ta.length()) { val t = ta.getJSONObject(i)
            val tr = Track(t.getString("id"), t.getString("name"), Family.valueOf(t.getString("family")), t.getString("inst"), t.getLong("color"), t.optDouble("vol", 0.0).toFloat(), t.optDouble("pan", 0.0).toFloat(), t.optBoolean("mute"), t.optBoolean("solo"), t.optBoolean("arm"), t.optBoolean("mon"), t.optString("input", "phone_mic"))
            tr.rig = t.optJSONObject("rig")?.let { rig(it) } ?: RigPresets.defaultFor(tr.family); tr.kitId = t.optString("kit").ifBlank { null }; tr.tuning = t.optString("tuning").ifBlank { null }; tr.voiceStyle = t.optString("voice", tr.voiceStyle); tr.automationOn = t.optBoolean("autoOn"); tr.useMic = t.optBoolean("useMic"); tr.printTone = t.optBoolean("print", true); tr.paused = t.optBoolean("paused")
            t.optJSONArray("lyrics")?.let { a -> for (k in 0 until a.length()) tr.lyrics.add(a.getString(k)) }
            t.optJSONArray("playlist")?.let { a -> for (k in 0 until a.length()) tr.playlist.add(a.getString(k)) }
            t.optJSONArray("clips")?.let { a -> for (k in 0 until a.length()) { val c = a.getJSONObject(k); tr.clips.add(Clip(c.getString("id"), c.getString("a"), c.getLong("s"), c.optLong("o"), c.getLong("l"), c.optDouble("g", 0.0).toFloat(), c.optDouble("r", 1.0).toFloat(), c.optString("n", "Clip"), c.optString("take").ifBlank { null }, c.optBoolean("pr"), c.optLong("fi"), c.optLong("fo"))) } }
            t.optJSONArray("notes")?.let { a -> for (k in 0 until a.length()) { val n = a.getJSONArray(k); tr.notes.add(MidiNote(n.getInt(0), n.getLong(1), n.getLong(2), n.getInt(3))) } }
            t.optJSONArray("auto")?.let { a -> for (k in 0 until a.length()) { val n = a.getJSONArray(k); tr.automation.add(AutoPoint(n.getLong(0), n.getDouble(1).toFloat())) } }
            p.tracks.add(tr) }
        o.optJSONArray("takes")?.let { a -> for (k in 0 until a.length()) { val t = a.getJSONObject(k); p.takes.add(Take(t.getString("id"), t.getString("a"), t.optString("track").ifBlank { null }, t.getLong("s"), t.getLong("l"), t.getString("n"), t.optString("src"), t.optLong("at"), t.optBoolean("placed"), t.optBoolean("lib"), printed = t.optBoolean("pr")).also { tk -> t.optJSONArray("notes")?.let { na -> for (q in 0 until na.length()) { val n = na.getJSONArray(q); tk.notes.add(MidiNote(n.getInt(0), n.getLong(1), n.getLong(2), n.getInt(3))) } } }) } }
        o.optJSONArray("kits")?.let { a -> for (k in 0 until a.length()) p.customKits.add(kit(a.getJSONObject(k))) }
        o.optJSONArray("insts")?.let { a -> for (k in 0 until a.length()) p.customInstruments.add(inst(a.getJSONObject(k))) }
        o.optJSONArray("builds")?.let { a -> for (k in 0 until a.length()) p.stringBuilds.add(StringCatalog.fromJson(a.getJSONObject(k))) }
        o.optJSONArray("room")?.let { a -> for (k in 0 until a.length()) { val r = a.getJSONObject(k); p.room.add(RoomItem(r.getString("id"), r.getString("k"), r.getString("ref"), r.getDouble("x").toFloat(), r.getDouble("y").toFloat(), r.optDouble("sc", 1.0).toFloat(), r.optJSONArray("v")?.let { va -> if (va.length() == 0) null else FloatArray(va.length()) { va.getDouble(it).toFloat() } }, r.optBoolean("on", true))) } }
        o.optJSONArray("cords")?.let { a -> for (k in 0 until a.length()) { val c = a.getJSONArray(k); p.cords.add(Cord(c.getString(0), c.getString(1))) } }
        return p }
}

/** Snapshot undo across session, mixer, piano roll and automation edits (the whole project document is the unit). */
class UndoStack(private val limit: Int = 100) {
    private val undo = ArrayDeque<String>(); private val redo = ArrayDeque<String>()
    fun push(snapshot: String) { if (undo.lastOrNull() == snapshot) return; undo.addLast(snapshot); if (undo.size > limit) undo.removeFirst(); redo.clear() }
    fun undo(current: String): String? { val s = undo.removeLastOrNull() ?: return null; redo.addLast(current); return s }
    fun redo(current: String): String? { val s = redo.removeLastOrNull() ?: return null; undo.addLast(current); return s }
    val canUndo get() = undo.isNotEmpty(); val canRedo get() = redo.isNotEmpty()
}
