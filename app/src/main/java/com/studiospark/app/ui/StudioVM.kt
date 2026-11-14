package com.studiospark.app.ui

import android.app.Application
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.studiospark.app.audio.*
import com.studiospark.app.core.*
import com.studiospark.app.dsp.*
import kotlin.math.pow
import com.studiospark.app.stems.StemSeparator
import kotlinx.coroutines.*
import java.io.File
import java.util.Calendar

enum class Screen(val label: String) { STUDIO("Studio"), DRUMS("Drum Kit"), GUITAR("Guitar"), BASS("Bass"), STEMS("Stems"), RECORD("Record"), JAM("Jam Room") }
enum class UploadStep(val label: String) { SOURCE("Source"), RIGHTS("Rights"), INSTRUMENT("Instrument"), TONE("Tone"), PLACE("Place") }

/** State of the upload / import / place-audio flow. The AmpliTube-style rig lives in its TONE step. */
class UploadFlow(val origin: String) {
    var step by mutableStateOf(UploadStep.SOURCE)
    var sourceName by mutableStateOf("")
    var audio by mutableStateOf<ShortArray?>(null)
    var existingAudioId: String? = null
    var takeId: String? = null
    var midi by mutableStateOf<MidiFile.Imported?>(null)
    var confirmed by mutableStateOf(false)
    var family by mutableStateOf(Family.GUITAR)
    var instrumentId by mutableStateOf("gtr_0")
    var targetTrackId by mutableStateOf<String?>(null)
    var rig by mutableStateOf(RigPresets.defaultFor(Family.GUITAR))
    var rigRev by mutableIntStateOf(0)
    var auditioning by mutableStateOf(false)
    var printTone by mutableStateOf(false)
    var placeAtPlayhead by mutableStateOf(true)
    var busy by mutableStateOf<String?>(null)
    var error by mutableStateOf<String?>(null)
}

class StemsState {
    var sourceName by mutableStateOf<String?>(null)
    var mix by mutableStateOf<ShortArray?>(null)
    var stereo44: FloatArray? = null
    var stems by mutableStateOf<Map<String, ShortArray>>(emptyMap())
    var progress by mutableFloatStateOf(0f)
    var status by mutableStateOf("")
    var running by mutableStateOf(false)
    var tab by mutableStateOf("all")
    var selected by mutableStateOf(setOf("vocals", "drums", "bass", "other"))
    var cancel = false
    var error by mutableStateOf<String?>(null)
}

class StudioVM(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("studiospark", 0)
    private val dir = File(app.filesDir, "session").apply { mkdirs() }
    val pool = AudioPool(File(dir, "audio"))
    val bank = SampleBank(app.assets)
    val mixer = Mixer(bank, pool)
    val engine = AudioEngine(app, mixer)
    val midiHub = MidiHub(app) { on, p, v -> if (on) noteOn(p, v) else noteOff(p) }

    var project by mutableStateOf(Project()); private set
    var rev by mutableIntStateOf(0); private set
    private val undoStack = UndoStack()
    var canUndo by mutableStateOf(false); private set
    var canRedo by mutableStateOf(false); private set

    var screen by mutableStateOf(Screen.STUDIO)
    var selectedTrackId by mutableStateOf<String?>(null)
    var selectedClipId by mutableStateOf<String?>(null)
    var chromeHidden by mutableStateOf(prefs.getBoolean("hidden", false))
    var gold by mutableStateOf(prefs.getBoolean("gold", false))
    var mixWindow by mutableStateOf(false)
    var compactMixer by mutableStateOf(prefs.getBoolean("compactMixer", false)); private set
    fun chooseCompactMixer(on: Boolean) { compactMixer = on; prefs.edit().putBoolean("compactMixer", on).apply() }
    var surfaceOverride by mutableStateOf<Surface?>(null)
    var message by mutableStateOf<String?>(null)
    var settingsOpen by mutableStateOf(false)
    var galleryOpen by mutableStateOf(false)
    var workbench by mutableStateOf<String?>(null) // "guitar" | "drums"
    var upload by mutableStateOf<UploadFlow?>(null)
    val stems = StemsState()
    var zoom by mutableFloatStateOf(0.5f) // timeline px-per-beat scale; 0.5 shows ~4 bars on a phone in portrait

    // birthday banner — user-set only, never assumed
    var birthdayMonth by mutableIntStateOf(prefs.getInt("bdM", 0)); private set
    var birthdayDay by mutableIntStateOf(prefs.getInt("bdD", 0)); private set
    var bannerDismissedYear by mutableIntStateOf(prefs.getInt("bdX", 0)); private set

    // live mirrors
    var transport by mutableStateOf(TransportState.STOPPED); private set
    var position by mutableLongStateOf(0L); private set
    var meters by mutableStateOf<Map<String, Float>>(emptyMap()); private set
    var masterL by mutableFloatStateOf(0f); private set
    var masterR by mutableFloatStateOf(0f); private set
    var inputLevel by mutableFloatStateOf(0f); private set
    var recElapsed by mutableLongStateOf(0L); private set
    var tunerOn by mutableStateOf(false)
    var tuner by mutableStateOf<Tuner.Reading?>(null); private set
    var cpu by mutableFloatStateOf(0f); private set
    var previewingTakeId by mutableStateOf<String?>(null); private set
    var roomRecording by mutableStateOf(false); private set
    var metronomeFree by mutableStateOf(false)
    var latencyMs by mutableFloatStateOf(prefs.getFloat("lat", -1f))

    var micGate by mutableStateOf<(() -> Unit)?>(null)       // in-app confirmation each mic tap
    var requestMicPermission: ((Boolean) -> Unit) -> Unit = { it(false) }
    var inputDevices by mutableStateOf<List<InputDevice>>(emptyList()); private set
    private var inputsScanned = false
    /** Scans audio inputs the first time the recording screen or an input picker is opened. */
    fun ensureInputs() { if (!inputsScanned) refreshInputs() }
    var midiInput by mutableStateOf(prefs.getBoolean("midiIn", false)); private set
    /** MIDI controllers are only listened for when the user turns MIDI input on. */
    fun setMidiInputEnabled(on: Boolean) { midiInput = on; prefs.edit().putBoolean("midiIn", on).apply()
        if (on) viewModelScope.launch(Dispatchers.Default) { runCatching { midiHub.listen() } } else midiHub.stopListening() }
    var inputDeviceId by mutableStateOf<Int?>(null)


    // ------------------------------------------------------------------ editing core
    private var lastCoalesce = ""; private var lastCoalesceAt = 0L
    fun edit(coalesce: String? = null, undoable: Boolean = true, block: Project.() -> Unit) {
        if (undoable) { val now = System.currentTimeMillis()
            if (coalesce == null || coalesce != lastCoalesce || now - lastCoalesceAt > 900) undoStack.push(ProjectJson.write(project))
            lastCoalesce = coalesce ?: ""; lastCoalesceAt = now }
        project.block(); changed()
    }
    fun changed() { KitRegistry.project = project; rev++; canUndo = undoStack.canUndo; canRedo = undoStack.canRedo; publish(); scheduleSave() }
    fun undo() { undoStack.undo(ProjectJson.write(project))?.let { project = ProjectJson.read(it); changed() } }
    fun redo() { undoStack.redo(ProjectJson.write(project))?.let { project = ProjectJson.read(it); changed() } }

    /** Per-track amp/pedal chains, built only when a track needs them (never at launch). */
    val rigs = LazyRigs(ENGINE_SR.toFloat())
    /** Marks tracks as needing their rig and samples; republishes if anything changed. */
    fun wantTracks(ids: Collection<String>) { var any = false; ids.forEach { if (rigs.want(it)) any = true }; if (any) publish() }
    private fun contentTracks() = project.tracks.filter { it.clips.isNotEmpty() || it.notes.isNotEmpty() || it.recArm || it.inputMonitor }.map { it.id }
    fun publish() {
        val p = project
        if (transport != TransportState.STOPPED) contentTracks().forEach { rigs.want(it) }
        val snaps = p.tracks.map { t ->
            val inst = p.instrument(t.instrumentId)
            val voice = if (t.family == Family.DRUMS) "kit:${t.kitId ?: inst.voice.removePrefix("kit:")}" else inst.voice
            // samples load on demand: only for tracks that are being used
            if (rigs.isWanted(t.id)) groupFor(voice)?.let { bank.ensure(it) }
            val kit = if (voice.startsWith("kit:")) DrumKits.byId(voice.removePrefix("kit:"), p.customKits) else null
            val (proc, procR) = rigs.resolve(t.id, t.rig, kit != null)
            val notes = t.notes.sortedBy { it.start }.toTypedArray()
            val syl = t.lyrics.filter { !it.startsWith("[") }.joinToString(" ").lowercase().split(Regex("[^a-z]+")).filter { it.isNotBlank() }.flatMap { w -> Regex("[aeiouy]+").findAll(w).map { m -> m.value.first().let { c -> if (c == 'y') 'i' else c } }.toList().ifEmpty { listOf('a') } }
            TrackSnap(t.id, t.family, voice, kit, t.clips.map { it.copy() }.toTypedArray(), notes, CharArray(notes.size) { syl.getOrElse(it % maxOf(1, syl.size)) { 'a' } },
                Shapers.dbToLin(t.volumeDb), t.pan, t.mute, t.solo, t.inputMonitor, t.recArm, inst.source == SoundSource.MIC || t.family == Family.AUDIO || (t.family == Family.VOCAL && inst.voice == "mic"),
                proc, t.automation.sortedBy { it.at }.toTypedArray(), t.automationOn, inst.brightness, when { t.voiceStyle.contains("Bright") -> 1; t.voiceStyle.contains("Breathy") -> 2; else -> 0 }, procR)
        }
        rigs.retain(p.tracks.map { it.id }.toSet())
        mixer.snap = MixSnap(p, snaps)
        engine.wantInput = p.tracks.any { (it.recArm || it.inputMonitor) && snaps.first { s -> s.id == it.id }.micInput } || tunerOn
        if (engine.wantInput && ready) engine.start()
    }
    fun groupFor(voice: String): String? = when { voice.startsWith("kit:") -> "drums"; voice == "mic" || voice == "voice" -> null; voice.startsWith("tex") -> "textures"
        voice in setOf("sub_bass", "saw_lead", "warm_pad", "square_arp", "pluck", "brass_synth", "wt_glass", "wt_vowel", "fm_ep", "fm_bell", "fm_bass", "organ") -> null; else -> voice }

    private var saveJob: Job? = null
    private fun scheduleSave() { saveJob?.cancel(); saveJob = viewModelScope.launch { delay(800); val s = ProjectJson.write(project); withContext(Dispatchers.IO) { File(dir, "project.json.tmp").apply { writeText(s); renameTo(File(dir, "project.json")) } } } }

    private suspend fun ticker() {
        var lastPos = -1L; var recStart = 0L
        while (true) {
            val s = mixer.session; val st = s.state
            val active = st != TransportState.STOPPED || mixer.previewAudio != null || engine.inputActive || roomRecording || mixer.room().voices.isNotEmpty() || mixer.channels.values.any { it.voices.isNotEmpty() }
            if (transport != st) { transport = st; if (st == TransportState.RECORDING) recStart = System.currentTimeMillis() }
            if (s.position != lastPos) { position = s.position; lastPos = s.position }
            if (active || meters.values.any { it > 0.001f }) {
                meters = HashMap(mixer.trackPeaks); masterL = mixer.mixPeakL; masterR = mixer.mixPeakR; inputLevel = engine.inputPeak; cpu = engine.cpuLoad
                if (!active) { mixer.trackPeaks.keys.forEach { mixer.trackPeaks[it] = mixer.trackPeaks[it]!! * 0.5f }; mixer.mixPeakL *= 0.5f; mixer.mixPeakR *= 0.5f }
            }
            recElapsed = if (st == TransportState.RECORDING) System.currentTimeMillis() - recStart else if (roomRecording) System.currentTimeMillis() - roomRecStart else recElapsed
            if (previewingTakeId != null && mixer.previewAudio == null) { previewingTakeId = null; mixer.session.listen(null) }
            if (tunerOn && engine.inputActive) { val buf = FloatArray(2048); val w = engine.tunerWrite; for (i in 0 until 2048) buf[i] = engine.tunerBuf[(w - 2048 + i + 4096) % 4096]
                tuner = withContext(Dispatchers.Default) { Tuner.detect(buf, 2048, ENGINE_SR.toFloat()) } ?: tuner }
            delay(if (active || tunerOn) 33 else 200)
        }
    }

    // ------------------------------------------------------------------ transport
    fun play() { if (transport == TransportState.STOPPED) { wantTracks(contentTracks()); engine.play() } }
    fun stop() { if (roomRecording) stopRoomRecording(); engine.stopTransport(); stopPreview() }
    fun togglePlay() = if (transport == TransportState.STOPPED) play() else stop()
    fun record() {
        if (transport == TransportState.RECORDING || transport == TransportState.COUNT_IN) { stop(); return }
        wantTracks(contentTracks())
        val micArmed = project.tracks.any { it.recArm && mixer.snap?.tracks?.firstOrNull { s -> s.id == it.id }?.micInput == true }
        if (micArmed) withMic { engine.record() } else engine.record()
    }
    fun rtz() { engine.locate(0); position = 0 }
    fun rewind() { val bar = project.samplesPerBar; val np = ((position - 1) / bar).coerceAtLeast(0) * bar; engine.locate(if (np == position) (np - bar).coerceAtLeast(0) else np) }
    fun forward() { val bar = project.samplesPerBar; engine.locate((position / bar + 1) * bar) }
    fun locate(pos: Long) { engine.locate(pos); position = pos }
    fun setMode(m: RecordMode) { engine.post { mixer.session.mode = m }; mixer.session.mode = m; rev++ }
    fun setLoop(on: Boolean) { mixer.session.loopPlayback = on; if (on && project.loopEnd <= project.loopStart) edit { loopStart = 0; loopEnd = samplesPerBar * 4 }; rev++ }
    fun setCountIn(bars: Int) { mixer.session.countInBars = bars; rev++ }
    fun setMetronome(on: Boolean) { mixer.session.metronome = on; rev++ }
    fun setFreeMetronome(on: Boolean) { metronomeFree = on; mixer.metronomeFree = on }
    fun setTempo(bpm: Float) = edit("tempo") { tempo = bpm.coerceIn(30f, 300f) }
    fun setSig(n: Int, d: Int) = edit { sigNum = n; sigDen = d }

    // ------------------------------------------------------------------ mic gate (asks every time)
    fun withMic(action: () -> Unit) { micGate = { requestMicPermission { granted -> if (granted) action() else message = "Microphone permission is off. Enable it in Android Settings › Apps › StudioSpark." } } }

    fun refreshInputs() { inputsScanned = true; inputDevices = engine.inputDevices(); if (inputDeviceId == null) inputDeviceId = inputDevices.firstOrNull { it.external }?.id ?: inputDevices.firstOrNull()?.id; engine.preferredInput = inputDeviceId }
    fun pickInput(id: Int) { inputDeviceId = id; engine.preferredInput = id }

    // ------------------------------------------------------------------ tracks
    fun selectedTrack() = project.track(selectedTrackId)
    fun addTrack(inst: InstrumentDef, name: String = inst.name) { lateinit var t: Track
        edit { t = Track(name = name, family = inst.family, instrumentId = inst.id, color = TrackColors.all[tracks.size % TrackColors.all.size], kitId = if (inst.family == Family.DRUMS) inst.voice.removePrefix("kit:") else null, tuning = inst.tuning); buildRig(inst)?.let { t.rig = it }; rigs.want(t.id); tracks.add(t) }
        selectedTrackId = t.id }
    fun deleteTrack(id: String) { edit { tracks.removeAll { it.id == id } }; if (selectedTrackId == id) selectedTrackId = project.tracks.firstOrNull()?.id }
    fun setTrack(id: String, coalesce: String? = null, f: Track.() -> Unit) = edit(coalesce?.let { "$it$id" }) { track(id)?.f() }
    fun toggleArm(t: Track) { rigs.want(t.id); val wantMic = !t.recArm && mixer.snap?.tracks?.firstOrNull { it.id == t.id }?.micInput == true
        if (wantMic) withMic { setTrack(t.id) { recArm = true } } else setTrack(t.id) { recArm = !recArm } }
    fun toggleMonitor(t: Track) { rigs.want(t.id); if (!t.inputMonitor) withMic { setTrack(t.id) { inputMonitor = true } } else setTrack(t.id) { inputMonitor = false } }
    fun applyTrackCount(n: Int) = edit { trackCount = n; var i = tracks.size; while (tracks.size < n) { i++; tracks.add(Track(name = "Audio $i", family = Family.AUDIO, instrumentId = "vox_mic", color = TrackColors.all[tracks.size % TrackColors.all.size])) }
        val extras = tracks.drop(n).filter { it.clips.isEmpty() && it.notes.isEmpty() && it.name.startsWith("Audio ") }; tracks.removeAll(extras.toSet()) }
    fun setInstrument(t: Track, inst: InstrumentDef) = setTrack(t.id) { rigs.want(t.id); instrumentId = inst.id; family = inst.family; if (inst.family == Family.DRUMS) kitId = inst.voice.removePrefix("kit:"); tuning = inst.tuning ?: tuning; val br = buildRig(inst); if (br != null) rig = br else if (rig.slots.isEmpty()) rig = RigPresets.defaultFor(inst.family) }

    // clips
    fun moveClip(t: Track, c: Clip, newStart: Long, snap: Boolean) = setTrack(t.id, "move${c.id}") { c.start = (if (snap) project.snap(newStart, project.samplesPerBeat) else newStart).coerceAtLeast(0) }
    fun duplicateClip(t: Track, c: Clip) = setTrack(t.id) { clips.add(c.copy(newId()).also { it.start = project.snap(c.end, project.samplesPerBar) }) }
    fun deleteClip(t: Track, c: Clip) { setTrack(t.id) { clips.remove(c) }; selectedClipId = null }
    fun trimClip(t: Track, c: Clip, deltaBars: Int) = setTrack(t.id) { val src = pool.get(c.audioId)?.size?.toLong() ?: c.length; val nl = (c.length + deltaBars * project.samplesPerBar * c.rate).toLong().coerceIn(project.samplesPerBeat / 2, src - c.offset); c.length = nl }
    fun stretchClip(t: Track, c: Clip, factor: Float) = setTrack(t.id) { c.rate = (c.rate / factor).coerceIn(0.25f, 4f) }
    fun splitClip(t: Track, c: Clip, at: Long) = setTrack(t.id) { if (at <= c.start || at >= c.end) return@setTrack; val cut = ((at - c.start) * c.rate).toLong(); clips.add(c.copy(newId()).also { it.start = at; it.offset = c.offset + cut; it.length = c.length - cut }); c.length = cut }
    /** Polish (first pass): normalise the clip to −1 dBFS peak and apply 5 ms edge fades in a new audio copy (original kept). */
    fun polishClip(t: Track, c: Clip) { val d = pool.get(c.audioId) ?: return; viewModelScope.launch(Dispatchers.Default) {
        var pk = 1; for (s in d) pk = maxOf(pk, kotlin.math.abs(s.toInt())); val g = 0.89f * 32767f / pk; val fade = ENGINE_SR / 200
        val out = ShortArray(d.size) { i -> val e = minOf(1f, minOf(i, d.size - 1 - i).toFloat() / fade); (d[i] * g * e).toInt().coerceIn(-32768, 32767).toShort() }
        val id = newId(); pool.put(id, out); withContext(Dispatchers.Main) { setTrack(t.id) { c.audioId = id; c.name = c.name.removeSuffix(" (polished)") + " (polished)" }; message = "Polished: normalised to −1 dBFS with edge fades. Original audio kept for undo." } } }

    // ------------------------------------------------------------------ live playing
    private val heldTrack = HashMap<Int, String?>()
    fun liveTarget(): String? = if (screen == Screen.JAM) null else selectedTrackId
    fun noteOn(pitch: Int, vel: Int, target: String? = liveTarget()) { if (target != null && rigs.want(target)) { if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) publish() else viewModelScope.launch(Dispatchers.Main) { publish() } }; heldTrack[pitch] = target; engine.noteOn(target, pitch, vel); midiHub.send(true, pitch, vel) }
    fun noteOff(pitch: Int) { val t = heldTrack.remove(pitch) ?: liveTarget(); engine.noteOff(t, pitch); midiHub.send(false, pitch, 0) }

    // ------------------------------------------------------------------ takes
    private fun onPasses(passes: List<CapturedPass>) {
        val made = mutableListOf<Take>()
        for (pass in passes) { if (pass.audio.size < ENGINE_SR / 20 && pass.notes.isEmpty()) continue
            val id = newId(); pool.put(id, pass.audio)
            made += mixer.session.finishPass(project, listOf(Triple(pass.trackId, id, pass.audio.size.toLong())), pass.source, pass.start).onEach { it.notes.addAll(pass.notes) } }
        if (made.isNotEmpty()) { changed(); message = "${made.size} take${if (made.size > 1) "s" else ""} recorded — Listen, then Place or Discard" }
    }
    fun listen(take: Take) { val a = pool.get(take.audioId) ?: return; if (previewingTakeId == take.id) { stopPreview(); return }
        mixer.session.listen(take.id); previewingTakeId = take.id
        val rig = project.track(take.trackId)?.rig?.let { RigProcessor(ENGINE_SR.toFloat(), it) }
        engine.preview(a, rig) }
    fun stopPreview() { engine.preview(null); previewingTakeId = null; mixer.session.listen(null); upload?.auditioning = false }
    fun place(take: Take, toTrack: String? = take.trackId, asMidi: Boolean = false) { stopPreview(); var tr: Track? = null
        val fresh = toTrack == null
        edit { tr = mixer.session.place(this, take.id, toTrack, if (take.source == "Mic") Family.VOCAL else Family.AUDIO, if (take.trackId == null) take.name else null, asMidi)
            // Jam-room takes are recorded through the room's amp and pedals already; a fresh track gets an empty rig so the tone is not processed twice.
            if (fresh && take.source == "Jam Room") tr?.rig = Rig(presetName = "Printed in jam room") }
        selectedTrackId = tr?.id; tr?.let { wantTracks(listOf(it.id)) }; message = "Placed on ${tr?.name}" }
    fun discard(take: Take) { stopPreview(); var aid: String? = null; edit { aid = mixer.session.discard(this, take.id) }; aid?.let { pool.delete(it) } }
    fun saveToLibrary(take: Take) = edit { take.savedToLibrary = true }

    // ------------------------------------------------------------------ jam room
    private var roomRecStart = 0L
    fun startRoomRecording() { engine.startRoomRecording(); roomRecording = true; roomRecStart = System.currentTimeMillis() }
    fun stopRoomRecording() { engine.stopRoomRecording(); roomRecording = false }
    /** True once the Jam Room has been opened or used this session. */
    var roomReady = false; private set
    /** Builds the Jam Room only when the user opens it or a feature needs it. Starts empty: the user adds instruments, pedals and amps. */
    fun ensureRoom() { if (!roomReady) rebuildRoom() }
    var roomInstrument by mutableStateOf("inst1")
    fun rebuildRoom() { roomReady = true
        val p = project; val g = SignalGraph(p.cords.toMutableList()); val inst = p.room.firstOrNull { it.id == roomInstrument && (it.kind == "instrument" || it.kind == "kit") } ?: p.room.firstOrNull { it.kind == "instrument" || it.kind == "kit" }
        val rig = Rig(presetName = "Jam Room")
        if (inst != null) { roomInstrument = inst.id
            for (id in g.chainFrom(inst.id).drop(1)) { val it = p.room.firstOrNull { r -> r.id == id } ?: continue; val def = GearCatalog.find(it.refId) ?: continue
                if (it.values == null || it.values!!.size != def.params.size) it.values = def.defaults(); rig.slots.add(RigSlot(RigSlot.next(), def.id, it.values!!, it.enabled)) }
            val d = p.instrument(inst.refId).takeIf { inst.kind == "instrument" }
            val voice = if (inst.kind == "kit") "kit:${inst.refId}" else d?.voice ?: "guitar"
            groupFor(voice)?.let { bank.ensure(it) }
            mixer.roomVoice = voice; mixer.roomBrightness = d?.brightness ?: 0.6f; mixer.roomKit = if (voice.startsWith("kit:")) DrumKits.byId(voice.removePrefix("kit:"), p.customKits) else null }
        mixer.roomRig = RigProcessor(ENGINE_SR.toFloat(), rig)
    }
    fun roomConnect(from: String, to: String): Boolean { val g = SignalGraph(project.cords.toMutableList()); val ok = g.connect(from, to)
        if (ok) { edit { cords.clear(); cords.addAll(g.cords) }; rebuildRoom() } else message = "That cable would create a feedback loop — blocked."; return ok }
    fun roomAdd(kind: String, ref: String) { val id = "${kind}_${newId()}"
        edit { val inst = room.firstOrNull { it.id == roomInstrument }
            room.add(RoomItem(id, kind, ref, 0.3f + (room.size % 5) * 0.1f, if (kind == "amp" || kind == "cab") 0.22f else if (kind == "pedal") 0.5f else 0.72f))
            if (kind == "pedal" && inst != null) { val g = SignalGraph(cords.toMutableList()); val chain = g.chainFrom(inst.id); val ampIdx = chain.indexOfFirst { c -> room.firstOrNull { it.id == c }?.kind == "amp" }
                val before = if (ampIdx > 0) chain[ampIdx - 1] else chain.last(); val after = if (ampIdx > 0) chain[ampIdx] else null
                g.disconnect(before, after ?: ""); g.connect(before, id); if (after != null) g.connect(id, after); cords.clear(); cords.addAll(g.cords) }
            if (kind == "amp" || kind == "cab") { val g = SignalGraph(cords.toMutableList()); val chain = inst?.let { g.chainFrom(it.id) } ?: emptyList(); if (chain.isNotEmpty() && kind == "amp" && chain.none { c -> room.firstOrNull { it.id == c }?.kind == "amp" }) { g.connect(chain.last(), id); cords.clear(); cords.addAll(g.cords) }
                else if (chain.isNotEmpty() && kind == "cab") { g.connect(chain.last(), id); cords.clear(); cords.addAll(g.cords) } }
            if (kind == "instrument" || kind == "kit") { roomInstrument = id; val oldInst = inst; if (oldInst != null) { val first = cords.firstOrNull { it.from == oldInst.id }?.to; if (first != null) { cords.removeAll { it.from == oldInst.id }; cords.add(Cord(id, first)) } } }
        }; rebuildRoom() }
    fun roomRemove(id: String) { edit { val g = SignalGraph(cords.toMutableList()); g.remove(id); cords.clear(); cords.addAll(g.cords); room.removeAll { it.id == id } }; rebuildRoom() }
    fun roomMove(id: String, x: Float, y: Float) { project.room.firstOrNull { it.id == id }?.let { it.x = x.coerceIn(0.02f, 0.98f); it.y = y.coerceIn(0.02f, 0.98f) }; rev++ }
    fun roomSave() { edit { }; message = "Room saved — gear positions, cables and camera kept" }
    fun setCamera(x: Float, y: Float, z: Float) { project.camX = x; project.camY = y; project.camZoom = z; rev++ }

    // ------------------------------------------------------------------ upload flow (rig lives here)
    fun openUpload(origin: String, forTrack: Track? = selectedTrack()) {
        val u = UploadFlow(origin)
        forTrack?.let { t -> rigs.want(t.id); u.targetTrackId = t.id; u.family = t.family; u.instrumentId = t.instrumentId
            // Tone edits on an existing track are live on that track (undoable as one step); other origins edit a working copy until Place.
            u.rig = if (origin == "rig") { edit { }; t.rig } else t.rig.copy()
            val clip = t.clips.firstOrNull { it.id == selectedClipId } ?: t.clips.firstOrNull()
            if (origin == "rig" && clip != null) { u.audio = pool.get(clip.audioId); u.existingAudioId = clip.audioId; u.sourceName = clip.name; u.confirmed = true; u.step = UploadStep.TONE }
            else if (origin == "rig") { u.sourceName = "${t.name} (live input / instrument)"; u.confirmed = true; u.step = UploadStep.TONE } }
        upload = u
    }
    fun uploadFromTake(u: UploadFlow, take: Take) { u.audio = pool.get(take.audioId); u.existingAudioId = take.audioId; u.takeId = take.id; u.sourceName = take.name; u.confirmed = true; u.step = UploadStep.INSTRUMENT }
    fun uploadFromStem(u: UploadFlow, name: String) { stems.stems[name]?.let { u.audio = it; u.sourceName = "Stem · ${name.replaceFirstChar { c -> c.uppercase() }}"; u.family = when (name) { "drums" -> Family.DRUMS; "bass" -> Family.BASS; "vocals" -> Family.VOCAL; else -> Family.GUITAR }; u.rig = RigPresets.defaultFor(u.family); u.step = UploadStep.RIGHTS } }
    fun uploadFromUri(u: UploadFlow, uri: Uri) {
        val ctx = getApplication<Application>(); val name = ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        when (val d = ImportPolicy.classify(uri.toString(), name, ctx.contentResolver.getType(uri))) {
            is ImportPolicy.Decision.Reject -> { u.error = d.reason; return }
            is ImportPolicy.Decision.Accept -> { u.error = null; u.sourceName = name ?: "Imported file"; u.busy = "Decoding…"
                viewModelScope.launch { runCatching { withContext(Dispatchers.IO) {
                    if (d.kind == "midi") { u.midi = MidiFile.read(ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }) }
                    else { val dec = Decoder.decode(ctx, uri) { u.busy = "Decoding… ${(it * 100).toInt()}%" }; u.audio = Decoder.toEngineMono(dec, ENGINE_SR) } } }
                    .onFailure { u.error = "Could not decode this file: ${it.message}" }.onSuccess { u.step = UploadStep.RIGHTS }; u.busy = null } }
        }
    }
    fun uploadChooseInstrument(u: UploadFlow, inst: InstrumentDef) { u.instrumentId = inst.id; u.family = inst.family; if (u.targetTrackId == null || project.track(u.targetTrackId)?.family != inst.family) u.rig = RigPresets.defaultFor(inst.family); u.rigRev++ }
    fun uploadRigChanged(u: UploadFlow) { u.rigRev++; if (u.auditioning) audition(u, true) else if (project.track(u.targetTrackId)?.rig === u.rig) publish() }
    fun audition(u: UploadFlow, on: Boolean) { u.auditioning = on && u.audio != null
        if (u.auditioning) engine.preview(u.audio, RigProcessor(ENGINE_SR.toFloat(), u.rig), loop = true)
        else { engine.preview(null) }
        // live instrument audition: route the track's live voices through the edited rig
        if (project.track(u.targetTrackId)?.rig === u.rig) publish()
    }
    fun finishUpload(u: UploadFlow) {
        stopPreview()
        val audio = u.audio; val midi = u.midi
        viewModelScope.launch {
            u.busy = if (u.printTone) "Printing tone…" else "Placing…"
            val finalAudio = if (audio != null && u.printTone) withContext(Dispatchers.Default) { printRig(audio, u.rig) } else audio
            var audioId = u.existingAudioId.takeIf { !u.printTone && it != null && finalAudio === audio }
            if (finalAudio != null && audioId == null) { audioId = newId(); pool.put(audioId, finalAudio) }
            val inst = project.instrument(u.instrumentId); var target: Track? = null
            edit {
                target = track(u.targetTrackId) ?: Track(name = u.sourceName.substringBeforeLast('.').take(24).ifBlank { inst.name }, family = u.family, instrumentId = inst.id, color = TrackColors.all[tracks.size % TrackColors.all.size],
                    kitId = if (u.family == Family.DRUMS) inst.voice.removePrefix("kit:") else null, tuning = inst.tuning).also { tracks.add(it) }
                val t = target!!
                t.rig = if (u.printTone) Rig(presetName = "Printed") else u.rig.copy()
                val at = if (u.placeAtPlayhead) snap(position, samplesPerBeat) else 0L
                if (audioId != null && finalAudio != null) t.clips.add(Clip(audioId = audioId!!, start = at, length = finalAudio.size.toLong(), name = u.sourceName.take(40)))
                if (midi != null) { if (u.origin != "rig") tempo = midi.tempo; midi.tracks.firstOrNull()?.second?.forEach { n -> t.notes.add(n.copy(start = n.start + at)) } }
                u.takeId?.let { tid -> takes.firstOrNull { it.id == tid }?.let { tk -> tk.placed = true; tk.trackId = t.id; if (tk.id !in t.playlist) t.playlist.add(tk.id) } }
            }
            selectedTrackId = target?.id; u.busy = null; upload = null; screen = Screen.STUDIO; message = "Placed on ${target?.name} with ${if (u.printTone) "printed tone" else "live rig \"${u.rig.presetName}\""}"
        }
    }
    fun closeUpload() { upload?.let { u -> u.targetTrackId?.let { id -> project.track(id)?.let { publish() } } }; stopPreview(); upload = null }
    private fun printRig(a: ShortArray, rig: Rig): ShortArray { val proc = RigProcessor(ENGINE_SR.toFloat(), rig.copy()); val out = ShortArray(a.size); val buf = FloatArray(512); var i = 0
        while (i < a.size) { val n = minOf(512, a.size - i); for (k in 0 until n) buf[k] = a[i + k] / 32768f; proc.process(buf, n); for (k in 0 until n) out[i + k] = (buf[k].coerceIn(-1f, 1f) * 32767f).toInt().toShort(); i += n }; return out }

    // ------------------------------------------------------------------ stems
    fun stemsImport(uri: Uri) {
        val ctx = getApplication<Application>(); val name = ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        when (val d = ImportPolicy.classify(uri.toString(), name, ctx.contentResolver.getType(uri))) {
            is ImportPolicy.Decision.Reject -> stems.error = d.reason
            is ImportPolicy.Decision.Accept -> { if (d.kind != "audio") { stems.error = "Stems need an audio file."; return }
                stems.error = null; stems.status = "Decoding…"; stems.running = true; stems.stems = emptyMap()
                viewModelScope.launch { runCatching { withContext(Dispatchers.IO) { val dec = Decoder.decode(ctx, uri, maxSeconds = 360); stems.mix = Decoder.toEngineMono(dec, ENGINE_SR); stems.stereo44 = Decoder.toStereo44k(dec) } }
                    .onFailure { stems.error = "Could not decode: ${it.message}" }.onSuccess { stems.sourceName = name; stems.status = "Ready to separate" }; stems.running = false } }
        }
    }
    fun stemsSeparate() { val x = stems.stereo44 ?: return; stems.running = true; stems.cancel = false; stems.error = null
        viewModelScope.launch { runCatching { withContext(Dispatchers.Default) { val sep = StemSeparator(getApplication()); try { val out = sep.separate(x, { stems.cancel }) { s, f -> stems.status = s; stems.progress = f }
            StemSeparator.names.mapIndexed { i, n -> n to Wav.toShorts(Wav.resample(FloatArray(out[i].size) { k -> out[i][k] / 32768f }, 44100, ENGINE_SR)) }.toMap() } finally { sep.close() } } }
            .onSuccess { stems.stems = it; stems.status = "Separated into vocals, drums, bass and other (estimates; some bleed is normal)" }.onFailure { stems.error = if (it is InterruptedException) "Separation cancelled" else "Separation failed: ${it.message}" }
            stems.running = false } }
    fun stemsPreview(tab: String) { stems.tab = tab; val a = if (tab == "all") stems.mix else stems.stems[tab]; engine.preview(a) }
    fun stemsAddSelected() { val sel = stems.selected.filter { stems.stems.containsKey(it) }; if (sel.isEmpty()) return; engine.preview(null)
        edit { for (n in sel) { val a = stems.stems[n]!!; val id = newId(); pool.put(id, a); val fam = when (n) { "drums" -> Family.DRUMS; "bass" -> Family.BASS; "vocals" -> Family.VOCAL; else -> Family.AUDIO }
            tracks.add(Track(name = "Stem · ${n.replaceFirstChar { it.uppercase() }}", family = fam, instrumentId = "vox_mic", color = TrackColors.all[tracks.size % TrackColors.all.size], rig = RigPresets.defaultFor(fam).also { r -> r.slots.forEach { s -> s.enabled = s.gear.kind == GearKind.RACK } }).also { it.clips.add(Clip(audioId = id, start = 0, length = a.size.toLong(), name = "${stems.sourceName ?: "Stem"} · $n")) }) } }
        screen = Screen.STUDIO; message = "${sel.size} time-aligned stems added as Studio tracks" }

    // ------------------------------------------------------------------ AI / songwriting
    fun aiMelody(t: Track) = setTrack(t.id) { val k = Assistant.detectKey(notes.map { it.pitch }.ifEmpty { listOf(60, 64, 67) }); val start = project.snap(position, project.samplesPerBar); notes.addAll(Assistant.melody(k, 4, project.samplesPerBeat, (System.currentTimeMillis() % 1000).toInt()).map { it.copy(start = it.start + start) }) }
    fun aiLyrics(t: Track, prompt: String, genre: String) = setTrack(t.id) { lyrics.clear(); lyrics.addAll(Assistant.lyrics(prompt, genre)) }
    fun aiVocalTrack(prompt: String, genre: String) { val inst = InstrumentLibrary.stock.first { it.id == "vox_ai" }; addTrack(inst, "AI Vocal"); selectedTrack()?.let { t ->
        setTrack(t.id) { lyrics.addAll(Assistant.lyrics(prompt, genre)); voiceStyle = "$genre · warm"; val k = Assistant.Key(9, true); val lines = lyrics.filter { !it.startsWith("[") }; var at = 0L; val spb = project.samplesPerBeat
            for (line in lines.take(8)) { val syl = Assistant.syllables(line).coerceIn(2, 10); val mel = Assistant.melody(k, 1, spb, line.hashCode()).take(syl); var tt = at; for (n in mel) { notes.add(n.copy(start = tt, length = spb / 2 - 200, pitch = n.pitch.coerceIn(57, 72))); tt += spb / 2 }; at += project.samplesPerBar * 2 } } } }

    // ------------------------------------------------------------------ export
    fun exportMix() { viewModelScope.launch { message = "Bouncing mix…"; val bytes = withContext(Dispatchers.Default) { renderMix() }
        val ok = withContext(Dispatchers.IO) { saveDownload("${project.name.replace(Regex("[^A-Za-z0-9 _-]"), "")}-mix.wav", "audio/wav", bytes) }; message = if (ok) "Mix bounced to Downloads" else "Could not save the bounce" } }
    fun exportMidi() { viewModelScope.launch(Dispatchers.IO) { val ok = saveDownload("${project.name}-midi.mid", "audio/midi", MidiFile.write(project, project.tracks.filter { it.notes.isNotEmpty() })); withContext(Dispatchers.Main) { message = if (ok) "MIDI exported to Downloads" else "Export failed" } } }
    private fun saveDownload(name: String, mime: String, data: ByteArray): Boolean = runCatching { val cr = getApplication<Application>().contentResolver
        val uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply { put(MediaStore.Downloads.DISPLAY_NAME, name); put(MediaStore.Downloads.MIME_TYPE, mime) })!!; cr.openOutputStream(uri)!!.use { it.write(data) }; true }.getOrDefault(false)
    private fun renderMix(): ByteArray {
        val p = ProjectJson.read(ProjectJson.write(project)); val off = Mixer(bank, pool)
        val snaps = mixer.snap!!.tracks.map { s -> val t = p.track(s.id)!!; TrackSnap(s.id, s.family, s.voice, s.kit, s.clips, s.notes, s.vowels, s.gain, s.pan, s.mute, s.solo, false, false, false, RigProcessor(ENGINE_SR.toFloat(), t.rig), s.automation, s.autoOn, s.brightness, s.voiceStyle, if (s.kit != null) RigProcessor(ENGINE_SR.toFloat(), t.rig) else null) }
        off.snap = MixSnap(p, snaps); off.session.metronome = false; off.session.play()
        val len = p.length + ENGINE_SR * 2; val out = ShortArray(len.toInt() * 2); val buf = FloatArray(1024); val silent = FloatArray(512); var pos = 0
        while (pos < len) { off.render(buf, silent, 512); for (i in 0 until 1024) { val k = pos * 2 + i; if (k < out.size) out[k] = (buf[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort() }; pos += 512 }
        val b = java.nio.ByteBuffer.allocate(44 + out.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()); b.putInt(36 + out.size * 2); b.put("WAVEfmt ".toByteArray()); b.putInt(16); b.putShort(1); b.putShort(2); b.putInt(ENGINE_SR); b.putInt(ENGINE_SR * 4); b.putShort(4); b.putShort(16); b.put("data".toByteArray()); b.putInt(out.size * 2); out.forEach { b.putShort(it) }
        return b.array()
    }

    // ------------------------------------------------------------------ user rig presets
    fun userPresets(): List<RigPreset> = runCatching { val a = org.json.JSONArray(prefs.getString("presets", "[]")); (0 until a.length()).map { i -> val o = a.getJSONObject(i); RigPreset(o.getString("name"), null) { ProjectJson.rig(o.getJSONObject("rig")).also { it.presetName = o.getString("name") } } } }.getOrDefault(emptyList())
    fun saveUserPreset(rig: Rig) { val a = runCatching { org.json.JSONArray(prefs.getString("presets", "[]")) }.getOrDefault(org.json.JSONArray()); val name = "My Rig ${a.length() + 1}"; rig.presetName = name
        a.put(org.json.JSONObject().put("name", name).put("rig", ProjectJson.rig(rig))); prefs.edit().putString("presets", a.toString()).apply(); message = "Saved preset \"$name\"" }

    // ------------------------------------------------------------------ settings
    fun setGoldTheme(on: Boolean) { gold = on; prefs.edit().putBoolean("gold", on).apply() }
    fun setHidden(on: Boolean) { chromeHidden = on; prefs.edit().putBoolean("hidden", on).apply() }
    fun setBirthday(m: Int, d: Int) { birthdayMonth = m; birthdayDay = d; prefs.edit().putInt("bdM", m).putInt("bdD", d).apply() }
    fun dismissBanner() { bannerDismissedYear = Calendar.getInstance().get(Calendar.YEAR); prefs.edit().putInt("bdX", bannerDismissedYear).apply() }
    fun bannerVisible(): Boolean { if (birthdayMonth == 0 || birthdayDay == 0) return false; val c = Calendar.getInstance()
        return c.get(Calendar.MONTH) + 1 == birthdayMonth && c.get(Calendar.DAY_OF_MONTH) == birthdayDay && bannerDismissedYear != c.get(Calendar.YEAR) && transport != TransportState.RECORDING }
    fun setLatency(ms: Float) { latencyMs = ms; prefs.edit().putFloat("lat", ms).apply(); mixer.latencyCompFrames = (ms * ENGINE_SR / 1000f).toInt() }
    fun estimatedLatencyMs() = engine.outputLatencyMs + engine.inputLatencyMs
    fun saveCustomKit(k: DrumKit) { edit { customKits.removeAll { it.id == k.id }; customKits.add(k); customInstruments.removeAll { it.id == "kit_${k.id}" }; customInstruments.add(InstrumentDef("kit_${k.id}", k.name, Family.DRUMS, Surface.DRUM_KIT, SoundSource.DRUM_RENDER, "kit:${k.id}", "kit:${k.id}", "My drum kits", custom = true)) }
        saveKitToUserLibrary(k); message = "Kit \"${k.name}\" saved — pick it on any drum track, in the Jam Room, Record, upload/place and the Instruments gallery" }
    fun saveCustomInstrument(d: InstrumentDef) { edit { customInstruments.removeAll { it.id == d.id }; customInstruments.add(d) }; message = "\"${d.name}\" saved to your instrument list" }
    fun newSession() { stop(); undoStack.push(ProjectJson.write(project)); rigs.clear(); project = Sessions.blank()
        mergeUserLibrary(project); selectedTrackId = null; if (roomReady) rebuildRoom(); changed() }


    override fun onCleared() { engine.shutdown(); midiHub.close(); super.onCleared() }

    // ------------------------------------------------------------------ Drum Kit tab
    fun drumTrack(): Track? = selectedTrack()?.takeIf { it.family == Family.DRUMS && project.instrument(it.instrumentId).surface != Surface.PADS } ?: project.tracks.firstOrNull { it.family == Family.DRUMS && project.instrument(it.instrumentId).surface != Surface.PADS }
    fun ensureDrumTrack(): Track = drumTrack() ?: run { addTrack((InstrumentLibrary.stock.firstOrNull { it.id == "kit_studio_maple" } ?: InstrumentLibrary.stock.first { it.surface == Surface.DRUM_KIT }), "Drums"); selectedTrack()!! }
    fun kitOf(t: Track?): DrumKit = t?.let { DrumKits.byId(it.kitId ?: project.instrument(it.instrumentId).voice.removePrefix("kit:"), project.customKits) } ?: DrumKits.studio
    /** Applies an edited kit live to the drum track (undoable, coalesced per gesture). Factory kits fork into an editable custom copy first. */
    fun applyKit(k: DrumKit, coalesce: String? = null): DrumKit {
        val t = ensureDrumTrack()
        val kit = if (k.custom) k else k.copy(id = "ck_${newId()}", name = if (k.id == "blank") "My kit" else "My ${k.name}", custom = true, baseId = k.id)
        edit(coalesce?.let { "kit$it" }) { customKits.removeAll { it.id == kit.id }; customKits.add(kit); track(t.id)?.kitId = kit.id }
        return kit
    }
    fun startKitFrom(starterId: String) { val s = DrumCatalog.starter(starterId) ?: return; applyKit(s.copy(custom = false)); message = if (starterId == "blank") "Blank kit — add drums, cymbals, pedals and hardware from the catalog" else "Started from ${s.name}" }
    fun saveKitNamed(k: DrumKit, name: String) { val kit = applyKit(k.copy(name = name.ifBlank { k.name })); saveCustomKit(kit) }
    fun kitHit(p: KitPiece, vel: Int) { val t = ensureDrumTrack(); if (p.sounding && vel > 0) { noteOn(p.midi, vel, t.id); viewModelScope.launch { delay(60); noteOff(p.midi) } } }
    /** Plays a sample with the catalog voicing (retune + level) on the private preview bus, without touching the kit or timeline. */
    fun auditionSample(sample: String, tune: Float, gainDb: Float) { if (sample.isEmpty()) return
        viewModelScope.launch(Dispatchers.Default) { bank.ensureBlocking("drums"); val s = bank.drum(sample, 110, 0) ?: return@launch; val rate = 2.0.pow(tune / 12.0); val g = Shapers.dbToLin(gainDb)
            val n = (s.data.size / rate).toInt(); val out = ShortArray(n) { i -> val x = i * rate; val a = x.toInt(); val fr = (x - a).toFloat(); val v = s.data[a] * (1 - fr) + s.data.getOrElse(a + 1) { 0 } * fr; (v * g).toInt().coerceIn(-32768, 32767).toShort() }
            engine.preview(out) } }
    fun recordOnDrums() { val t = ensureDrumTrack(); if (!t.recArm) setTrack(t.id) { recArm = true }; record() }

    // ------------------------------------------------------------------ Guitar & Bass tabs
    private fun fam(bass: Boolean) = if (bass) Family.BASS else Family.GUITAR
    fun stringTrack(bass: Boolean): Track? { val f = fam(bass)
        return selectedTrack()?.takeIf { it.family == f } ?: project.tracks.firstOrNull { it.family == f && project.build(project.instrument(it.instrumentId).baseId) != null }
            ?: project.tracks.firstOrNull { it.family == f && project.instrument(it.instrumentId).surface == Surface.STRINGS } }
    fun ensureStringTrack(bass: Boolean): Track = stringTrack(bass) ?: run { addTrack(InstrumentLibrary.stock.first { it.family == fam(bass) && it.surface == Surface.STRINGS }, if (bass) "Bass" else "Guitar"); selectedTrack()!! }
    /** The build shown on the tab: the target track's build, else the latest draft, else the first starter. */
    fun buildOf(bass: Boolean): StringBuild { val t = stringTrack(bass); val inst = t?.let { project.instrument(it.instrumentId) }
        return project.build(inst?.baseId)?.takeIf { it.bass == bass } ?: project.stringBuilds.lastOrNull { it.bass == bass && !it.saved } ?: StringCatalog.starters(bass).first() }
    /** Applies an edited build live to its track (undoable, coalesced per gesture). Starters fork into a project draft first. Returns the live build. */
    fun applyBuild(b: StringBuild, coalesce: String? = null): StringBuild {
        val t = ensureStringTrack(b.bass)
        val nb = if (project.build(b.id) != null) b else b.copy(id = "sb_${newId()}", name = if (b.starterId == "blank") b.name else "My ${b.name}", saved = false)
        val inst = StringCatalog.toInstrument(nb)
        edit(coalesce?.let { "sb$it" }) { stringBuilds.removeAll { it.id == nb.id }; stringBuilds.add(nb)
            track(t.id)?.let { tr -> tr.instrumentId = inst.id; tr.family = inst.family; tr.tuning = inst.tuning; if (tr.rig.slots.isEmpty()) tr.rig = RigPresets.defaultFor(inst.family) } }
        groupFor(inst.voice)?.let { bank.ensure(it) }
        return nb
    }
    fun startBuildFrom(id: String, bass: Boolean) { val s = StringCatalog.starter(id) ?: return; val nb = applyBuild(s)
        if (s.rigPreset.isNotEmpty()) setBuildRig(nb, s.rigPreset)
        message = if (s.starterId == "blank") "From scratch — choose a body, pickups, hardware and finish" else "Started from ${s.name}" }
    /** Plugs the instrument's track into a rig preset (stomps → amp → cab → rack). The live rig processor follows immediately. */
    fun setBuildRig(b: StringBuild, presetName: String) { val t = ensureStringTrack(b.bass); val pr = (RigPresets.factory + userPresets()).firstOrNull { it.name == presetName } ?: return
        edit { track(t.id)?.rig = pr.build(); stringBuilds.replaceAll { if (it.id == b.id) it.copy(rigPreset = presetName) else it } }; message = "${b.name} → $presetName" }
    fun saveBuildNamed(b: StringBuild, name: String) { val nb = applyBuild(b.copy(name = name.ifBlank { b.name }, saved = true))
        saveToUserLibrary(nb); message = "\"${nb.name}\" saved — pick it on any track, in the Jam Room, Record, upload/place and the Instruments gallery" }
    fun stringNote(pitch: Int, vel: Int, bass: Boolean) { val t = ensureStringTrack(bass); noteOn(pitch, vel, t.id); viewModelScope.launch { delay(if (bass) 1400 else 1800); noteOff(pitch) } }
    fun recordOn(t: Track) { if (!t.recArm) setTrack(t.id) { recArm = true }; record() }
    /** Sends a saved instrument into the Jam Room, plugged into whatever amp chain is already cabled. */
    fun sendToRoom(d: InstrumentDef) { if (d.family == Family.DRUMS) roomAdd("kit", d.voice.removePrefix("kit:")) else roomAdd("instrument", d.id)
        if (d.family == Family.GUITAR || d.family == Family.BASS) {
            if (project.room.none { it.kind == "amp" }) { roomAdd("amp", if (d.family == Family.BASS) "fathom_bass" else "citrus_valve"); roomAdd("cab", if (d.family == Family.BASS) "cab_810" else "cab_412") }
            if (project.cords.none { it.from == roomInstrument }) { val targets = project.cords.map { it.to }.toSet()
                project.room.firstOrNull { (it.kind == "pedal" || it.kind == "amp") && it.id !in targets }?.let { roomConnect(roomInstrument, it.id) } } }
        screen = Screen.JAM; rebuildRoom(); message = "${d.name} is plugged in to the Jam Room rig" }

    /** A custom guitar/bass carries its amp+pedal rig wherever it is assigned. */
    fun buildRig(inst: InstrumentDef): Rig? = project.build(inst.baseId)?.rigPreset?.takeIf { it.isNotEmpty() }?.let { n -> (RigPresets.factory + userPresets()).firstOrNull { it.name == n }?.build?.invoke() }
    /** Opens a saved kit/guitar/bass on its builder tab, on a track that uses it. */
    fun editInstrument(d: InstrumentDef) { val t = project.tracks.firstOrNull { it.instrumentId == d.id } ?: run { addTrack(d); selectedTrack()!! }
        selectedTrackId = t.id; galleryOpen = false; screen = when (d.family) { Family.DRUMS -> Screen.DRUMS; Family.BASS -> Screen.BASS; else -> Screen.GUITAR } }

    // personal library: saved builds and kits survive new sessions
    private fun saveToUserLibrary(b: StringBuild) { val a = runCatching { org.json.JSONArray(prefs.getString("libBuilds", "[]")) }.getOrElse { org.json.JSONArray() }
        val out = org.json.JSONArray(); for (i in 0 until a.length()) { val o = a.getJSONObject(i); if (o.optString("id") != b.id) out.put(o) }; out.put(StringCatalog.toJson(b)); prefs.edit().putString("libBuilds", out.toString()).apply() }
    fun saveKitToUserLibrary(k: DrumKit) { val a = runCatching { org.json.JSONArray(prefs.getString("libKits", "[]")) }.getOrElse { org.json.JSONArray() }
        val out = org.json.JSONArray(); for (i in 0 until a.length()) { val o = a.getJSONObject(i); if (o.optString("id") != k.id) out.put(o) }; out.put(ProjectJson.kit(k)); prefs.edit().putString("libKits", out.toString()).apply() }
    private fun mergeUserLibrary(p: Project) { runCatching { val a = org.json.JSONArray(prefs.getString("libBuilds", "[]")); for (i in 0 until a.length()) { val b = StringCatalog.fromJson(a.getJSONObject(i)); if (p.build(b.id) == null) p.stringBuilds.add(b) } }
        runCatching { val a = org.json.JSONArray(prefs.getString("libKits", "[]")); for (i in 0 until a.length()) { val k = ProjectJson.kit(a.getJSONObject(i)); if (p.customKits.none { it.id == k.id }) { p.customKits.add(k)
            if (p.customInstruments.none { it.id == "kit_${k.id}" }) p.customInstruments.add(InstrumentDef("kit_${k.id}", k.name, Family.DRUMS, Surface.DRUM_KIT, SoundSource.DRUM_RENDER, "kit:${k.id}", "kit:${k.id}", "My drum kits", custom = true)) } } } }

    // init last: every property above must be initialised before the engine and room are built
    /** False until the session is loaded and the engine is running; the UI shows a light loading state until then. */
    var ready by mutableStateOf(false); private set
    private val pendingReady = mutableListOf<() -> Unit>()
    /** Runs [action] now if the session is ready, otherwise right after startup finishes (on the main thread). */
    fun whenReady(action: () -> Unit) { if (ready) action() else pendingReady += action }

    init {
        engine.onPasses = { passes -> onPasses(passes) }
        engine.onRecordRefused = { message = it }
        mixer.latencyCompFrames = ((if (latencyMs >= 0) latencyMs else 0f) * ENGINE_SR / 1000f).toInt()
        // Launch opens a basic studio only. No samples, rigs, room, input scan, MIDI or audio engine are started here:
        // each loads when the user first needs it. Only the lightweight session data is read, off the main thread.
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.Default) {
                val f = File(dir, "project.json")
                val p = if (f.exists()) runCatching { ProjectJson.read(f.readText()) }.getOrElse { Sessions.blank() } else Sessions.blank()
                mergeUserLibrary(p); p   // saved kits, guitars and basses stay listed everywhere (data only, no assets)
            }
            project = loaded; KitRegistry.project = loaded
            selectedTrackId = loaded.tracks.firstOrNull()?.id
            withContext(Dispatchers.Default) { publish() }   // track list only: no rig is built, no samples load
            ready = true
            val queued = pendingReady.toList(); pendingReady.clear(); queued.forEach { runCatching { it() } }
            if (midiInput) launch(Dispatchers.Default) { runCatching { midiHub.listen() } }   // user turned MIDI on earlier
            ticker()
        }
    }

}
