package com.aimusicstudio.app

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Mirrors ContentView/MixerView state: sessions, library, transport, recorder. */
class StudioViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx = app
    val stemsDir: File = TrackStorage.stemsDir(app)
    val player = AudioPlayer(); val recorder = TakeRecorder()

    var sessions by mutableStateOf(listOf<StudioSession>()); private set
    var session by mutableStateOf(StudioSession(name = "My First Session")); private set
    var library by mutableStateOf(listOf<SampleLibraryItem>()); private set
    var playhead by mutableStateOf(0.0); private set
    var level by mutableFloatStateOf(0f); private set
    var isPlaying by mutableStateOf(false); private set
    var isRecording by mutableStateOf(false); private set
    var status by mutableStateOf(""); 
    var selectedTrackId by mutableStateOf<String?>(null)

    init {
        TrackStorage.seedBundledStems(app)
        val (saved, active) = StudioSessionStorage.load(app)
        if (saved.isNotEmpty()) { sessions = saved; session = saved.firstOrNull { it.id == active } ?: saved.first() }
        else { session = demoSession(); sessions = listOf(session) }
        refreshLibrary()
        viewModelScope.launch(Dispatchers.IO) { seedBuiltInLibrary(); withContext(Dispatchers.Main) { refreshLibrary() } }
        viewModelScope.launch { while (isActive) { level = maxOf(player.level, recorder.level); playhead = player.playhead; isPlaying = player.isPlaying; delay(50) } }
    }

    private fun demoSession(): StudioSession {
        var dp = DrumPattern()
        listOf(0, 4, 8, 12).forEach { dp = dp.toggled(DrumPad.KICK, it) }
        listOf(4, 12).forEach { dp = dp.toggled(DrumPad.SNARE, it) }
        (0 until 16 step 2).forEach { dp = dp.toggled(DrumPad.CLOSED_HAT, it) }
        return StudioSession(name = "My First Session", drumPattern = dp, tracks = listOf(
            Track(name = "Bass Line", clips = listOf(AudioClip(fileName = "stem-01-bass-line.wav", startTime = 0.0, duration = 2.0))),
            Track(name = "Lead Synth", kind = TrackKind.SYNTH, waveform = SynthWaveform.SQUARE,
                notes = listOf(60, 63, 67, 70, 67, 63).mapIndexed { i, p -> SynthNote(pitch = p, startTime = i * 0.5, duration = 0.4) }),
        ))
    }

    /** Built-in content library ("8-track" sound bank): drum kits, synth/keys one-shots, guitar & bass notes, rendered once to WAV. */
    private fun seedBuiltInLibrary() {
        val marker = File(stemsDir, ".lib-v122"); if (marker.exists()) return
        fun w(name: String, x: FloatArray) = runCatching { val f = File(stemsDir, "lib-" + name.replace(' ', '-') + ".wav"); if (!f.exists()) MixRenderer.writeWavInterleaved(f, x, 1) }
        DrumKit.values().forEach { k -> DrumPad.values().forEach { p -> w("${k.label} ${p.label}", Fx.kitDrum(k, p)) } }
        SynthWaveform.values().forEach { wf -> listOf(36, 48, 60).forEach { p -> w("${wf.label} ${Tuner.names[p % 12]}${p / 12 - 1}", InstrumentSoundGenerator.note(wf, p, 1.2)) } }
        listOf(GuitarModel.STRAT, GuitarModel.LESPAUL, GuitarModel.TELE, GuitarModel.SUPERSTRAT, GuitarModel.ACOUSTIC).forEach { g ->
            listOf(40, 45, 52).forEach { p -> val x = Fx.guitarNote(g, p, 1.5); w("${g.label} Guitar ${Tuner.names[p % 12]}${p / 12 - 1}", x) } }
        listOf(GuitarModel.BASS, GuitarModel.JAZZ_BASS, GuitarModel.MUSICMAN).forEach { g -> listOf(28, 33, 40).forEach { p -> w("${g.label} ${Tuner.names[p % 12]}${p / 12 - 1}", Fx.guitarNote(g, p, 1.5)) } }
        runCatching { marker.writeText("ok") }
    }
    fun refreshLibrary() { viewModelScope.launch { library = withContext(Dispatchers.IO) { SampleLibrary.scan(stemsDir) } } }

    // ---- Session (StudioSessionStorage) ----
    private val undoStack = ArrayDeque<StudioSession>(); private val redoStack = ArrayDeque<StudioSession>(); private var lastPush = 0L
    var canUndo by mutableStateOf(false); private set
    var canRedo by mutableStateOf(false); private set
    fun update(f: (StudioSession) -> StudioSession) {
        val now = System.currentTimeMillis()
        if (now - lastPush > 250) { undoStack.addLast(session); if (undoStack.size > 100) undoStack.removeFirst(); redoStack.clear() }
        lastPush = now; session = f(session).copy(lastEdited = now); canUndo = undoStack.isNotEmpty(); canRedo = false; rerenderIfPlaying()
    }
    fun undo() { undoStack.removeLastOrNull()?.let { redoStack.addLast(session); session = it; status = "Undo" }; lastPush = 0; canUndo = undoStack.isNotEmpty(); canRedo = redoStack.isNotEmpty() }
    fun redo() { redoStack.removeLastOrNull()?.let { undoStack.addLast(session); session = it; status = "Redo" }; lastPush = 0; canUndo = undoStack.isNotEmpty(); canRedo = redoStack.isNotEmpty() }
    fun updateTrack(id: String, f: (Track) -> Track) = update { s -> s.copy(tracks = s.tracks.map { if (it.id == id) f(it) else it }) }
    fun save() {
        sessions = if (sessions.any { it.id == session.id }) sessions.map { if (it.id == session.id) session else it } else sessions + session
        StudioSessionStorage.save(ctx, sessions, session.id); status = "Saved session ${session.name}"
    }
    fun newSession() { save(); session = StudioSession(name = "Session ${sessions.size + 1}"); save() }
    fun open(id: String) { save(); sessions.firstOrNull { it.id == id }?.let { session = it; status = "Opened ${it.name}" } }
    fun deleteSession(id: String) { if (sessions.size <= 1) return; sessions = sessions.filter { it.id != id }; if (session.id == id) session = sessions.first(); StudioSessionStorage.save(ctx, sessions, session.id) }

    // ---- Tracks ----
    fun addTrack(kind: TrackKind) = update { it.copy(tracks = it.tracks + Track(name = (when (kind) { TrackKind.SYNTH -> "Synth "; TrackKind.GUITAR -> "Guitar "; else -> "Audio " }) + (it.tracks.size + 1), kind = kind)) }
    fun removeTrack(id: String) = update { it.copy(tracks = it.tracks.filter { t -> t.id != id }) }
    fun addSampleToTrack(item: SampleLibraryItem, trackId: String?) {
        val target = trackId ?: selectedTrackId
        val clipStart = { t: Track -> t.clips.maxOfOrNull { it.startTime + it.duration } ?: 0.0 }
        if (target == null || session.tracks.none { it.id == target && it.kind == TrackKind.AUDIO }) {
            update { it.copy(tracks = it.tracks + Track(name = item.name, clips = listOf(AudioClip(fileName = item.id, startTime = 0.0, duration = item.duration)))) }
        } else updateTrack(target) { t -> t.copy(clips = t.clips + AudioClip(fileName = item.id, startTime = clipStart(t), duration = item.duration)) }
        status = "Added ${item.name} to track"
    }
    fun moveClip(trackId: String, clipId: String, delta: Double) = updateTrack(trackId) { t -> t.copy(clips = t.clips.map { if (it.id == clipId) it.copy(startTime = (it.startTime + delta).coerceAtLeast(0.0)) else it }) }
    fun trimClip(trackId: String, clipId: String, delta: Double) = updateTrack(trackId) { t -> t.copy(clips = t.clips.map {
        if (it.id == clipId) it.copy(duration = (it.duration + delta).coerceIn(0.1, it.sourceDuration - it.sourceStartTime)) else it }) }
    fun deleteClip(trackId: String, clipId: String) = updateTrack(trackId) { t -> t.copy(clips = t.clips.filter { it.id != clipId }) }
    fun toggleNote(trackId: String, pitch: Int, step: Int, offset: Int = 0) = updateTrack(trackId) { t ->
        val st = (step + offset) * session.stepSeconds; val ex = t.notes.firstOrNull { it.pitch == pitch && kotlin.math.abs(it.startTime - st) < 1e-6 }
        if (ex != null) t.copy(notes = t.notes - ex) else t.copy(notes = t.notes + SynthNote(pitch = pitch, startTime = st, duration = session.stepSeconds * 0.95))
    }

    // ---- Triggering (Sounds / Instruments tabs) ----
    fun preview(item: SampleLibraryItem) { viewModelScope.launch(Dispatchers.Default) { player.oneShot(AudioDecoder.decode(item.file), session.masterVolume) } }
    fun trigger(i: Instrument, pitch: Int = 60) { viewModelScope.launch(Dispatchers.Default) {
        val x = i.pad?.let { Fx.kitDrum(session.kit, it) } ?: InstrumentSoundGenerator.note(i.waveform!!, pitch, 0.35, acid = i.acid)
        player.oneShot(x, session.masterVolume) } }
    /** "Add to track" after previewing an instrument: drums go into the step pattern, synths add a note at the playhead. */
    fun addInstrumentToSession(i: Instrument, pitch: Int = 60) {
        if (i.pad != null) { update { it.copy(drumPattern = it.drumPattern.toggled(i.pad, 0)) }; status = "${i.name} added on step 1 of the drum pattern"; return }
        val existing = session.tracks.firstOrNull { it.kind == TrackKind.SYNTH && it.waveform == i.waveform }
        val note = SynthNote(pitch = pitch, startTime = playhead.coerceAtMost(session.length), duration = session.stepSeconds * 2)
        if (existing != null) updateTrack(existing.id) { it.copy(notes = it.notes + note) }
        else update { it.copy(tracks = it.tracks + Track(name = i.name, kind = TrackKind.SYNTH, waveform = i.waveform!!, notes = listOf(note))) }
        status = "${i.name} note added"
    }

    // ---- Transport ----
    /** Loop region if set; otherwise the whole song (rounded to bars) loops so drum loops and recorded parts keep playing. */
    private fun loopFor(s: StudioSession) = if (s.isLoopEnabled && s.loopEnd > s.loopStart) s.loopStart..s.loopEnd
        else 0.0..(kotlin.math.ceil(s.length / s.barSeconds) * s.barSeconds)
    private var renderJob: kotlinx.coroutines.Job? = null
    fun play() { val s = session; isPlaying = true; viewModelScope.launch(Dispatchers.Default) {
        val (l, r) = MixRenderer.render(s, stemsDir)
        val loop = loopFor(s)
        val from = if (player.playhead >= loop.endInclusive) loop.start else player.playhead
        player.play(l, r, from, loop) {} } }
    /** Re-render in the background whenever the session changes during playback (pads, keys, takes, mutes, kit). */
    private fun rerenderIfPlaying() { if (!player.isPlaying) return; val s = session
        renderJob?.cancel(); renderJob = viewModelScope.launch(Dispatchers.Default) { delay(120)
            val (l, r) = MixRenderer.render(s, stemsDir); if (isActive && player.isPlaying) player.swap(l, r, loopFor(s)) } }
    fun pause() { finishKeyboardTake(); player.stop(); isPlaying = false }
    fun stop() { finishKeyboardTake(); player.stop(); isPlaying = false; player.playhead = 0.0 }
    fun seek(t: Double) { val was = player.isPlaying; player.stop(); player.playhead = t; if (was) play() }

    // ---- Bounce / export (MixBounceRenderer + MixExportStorage) ----
    fun bounce() { val s = session; status = "Bouncing mix…"; viewModelScope.launch(Dispatchers.Default) {
        val (l, r) = MixRenderer.render(s, stemsDir)
        val f = File(TrackStorage.exportsDir(ctx), s.name.replace(Regex("[^A-Za-z0-9 _-]"), "") + "-mix-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".wav")
        MixRenderer.writeWav(f, l, r); status = "Exported mix to ${f.absolutePath}" } }

    // ---- Recording (InstrumentTakeRecorder) ----
    fun startRecording() {
        val name = "take-" + SimpleDateFormat("HHmmss", Locale.US).format(Date()) + ".wav"
        isRecording = true; status = "Recording…"
        val startAt = if (player.isPlaying) player.playhead else playhead
        val target = (armedTrackId ?: selectedTrackId)?.let { id -> session.tracks.firstOrNull { it.id == id && (it.kind == TrackKind.AUDIO || it.kind == TrackKind.LYRICS) } }
        recorder.start(File(stemsDir, name)) { f ->
            viewModelScope.launch { isRecording = false
                val d = withContext(Dispatchers.IO) { AudioDecoder.durationOf(f) }
                if (d <= 0) { status = "Recording was empty – check microphone permission"; return@launch }
                val clip = AudioClip(fileName = f.name, startTime = startAt, duration = d)
                pendingTake = PendingTake(target?.id, target?.name ?: "a new vocal track", clip = clip)
                refreshLibrary(); status = "Take recorded – place it on the track?" } }
    }
    fun stopRecording() { viewModelScope.launch(Dispatchers.IO) { recorder.stop() } }

    // ---- Studio gear: kits, guitars, amps, pedals ----
    fun triggerPad(pad: DrumPad, kit: DrumKit? = null) { val k = kit ?: session.kit; viewModelScope.launch(Dispatchers.Default) { player.oneShot(Fx.kitDrum(k, pad), session.masterVolume) }; captureLive(pad = pad) }
    fun triggerTrackNote(t: Track, pitch: Int) { viewModelScope.launch(Dispatchers.Default) {
        val x = when (t.kind) { TrackKind.GUITAR -> Fx.guitarNote(t.guitar, pitch, 0.6); else -> InstrumentSoundGenerator.note(t.waveform, pitch, 0.35) }
        t.pedals.forEach { Fx.pedal(x, it, session.tempoBPM) }; Fx.amp(x, t); player.oneShot(x, session.masterVolume * t.volume) } ; captureLive(pitch = pitch, track = t) }
    /** Select one kit for a drum/track: stores on the track, mirrors to session.kit (step sequencer), auditions the kick. */
    fun selectKit(trackId: String?, k: DrumKit) { update { s -> s.copy(kit = k, tracks = s.tracks.map { if (it.id == trackId) it.copy(kit = k) else it }) }; triggerPad(DrumPad.KICK, k); status = "Kit: ${k.label}" }
    /** Song concept → lyrics track songInfo/genre/theme. */
    fun inventConcept(id: String?, prompt: String) {
        val c = SongAI.inventConcept(session, prompt)
        val tid = id ?: addLyricsTrack(c.theme)
        updateTrack(tid) { it.copy(genre = c.genre, songInfo = c.summary(), lyrics = SongAI.writeLyrics(c.theme, c.genre), vocalStyle = c.vocal) }
        status = "AI concept: \"${c.title}\" – ${c.genre}"
    }
    fun writeAndSing(id: String, theme: String) { val t = session.tracks.firstOrNull { it.id == id } ?: return
        updateTrack(id) { it.copy(lyrics = SongAI.writeLyrics(theme.ifBlank { t.songInfo.lineSequence().firstOrNull { l -> l.startsWith("Theme:") }?.removePrefix("Theme:")?.trim() ?: "" }, t.genre)) }; lastPush = 0; singLyrics(id) }
    fun addPedal(id: String, type: PedalType) = updateTrack(id) { it.copy(pedals = it.pedals + PedalSlot(type)) }
    fun updatePedal(id: String, pid: String, f: (PedalSlot) -> PedalSlot) = updateTrack(id) { t -> t.copy(pedals = t.pedals.map { if (it.id == pid) f(it) else it }) }
    fun removePedal(id: String, pid: String) = updateTrack(id) { t -> t.copy(pedals = t.pedals.filter { it.id != pid }) }
    fun movePedal(id: String, pid: String, d: Int) = updateTrack(id) { t -> val l = t.pedals.toMutableList(); val i = l.indexOfFirst { it.id == pid }; val j = i + d
        if (i >= 0 && j in l.indices) { val x = l[i]; l[i] = l[j]; l[j] = x }; t.copy(pedals = l) }
    fun addGuitarTrack() = update { it.copy(tracks = it.tracks + Track(name = "Guitar ${it.tracks.size + 1}", kind = TrackKind.GUITAR, amp = AmpModel.BRIT, pedals = listOf(PedalSlot(PedalType.OVERDRIVE, amount = 0.3f)))) }

    /** Assign an onboard instrument to a track: synth/guitar voice change, or render a drum hit to a clip at the playhead. */
    fun assignInstrument(trackId: String, i: Instrument) {
        val t = session.tracks.firstOrNull { it.id == trackId } ?: return
        if (i.pad != null) { val f = File(stemsDir, "kit-v${Fx.KIT_VERSION}-${session.kit.name.lowercase()}-${i.pad.name.lowercase()}.wav")
            if (!f.exists()) MixRenderer.writeWavInterleaved(f, Fx.kitDrum(session.kit, i.pad), 1)
            val d = AudioDecoder.durationOf(f)
            if (t.kind == TrackKind.AUDIO) updateTrack(trackId) { it.copy(clips = it.clips + AudioClip(fileName = f.name, startTime = playhead, duration = d)) }
            else update { it.copy(tracks = it.tracks + Track(name = i.name, clips = listOf(AudioClip(fileName = f.name, startTime = playhead, duration = d)))) }
            refreshLibrary(); status = "${i.name} (${session.kit.label} kit) placed at ${fmt(playhead)}"; return }
        updateTrack(trackId) { it.copy(kind = if (it.kind == TrackKind.GUITAR) TrackKind.GUITAR else TrackKind.SYNTH, waveform = i.waveform!!, name = if (it.clips.isEmpty() && it.notes.isEmpty()) i.name else it.name) }
        status = "${t.name} now plays ${i.name}"
    }

    // ---- Live capture (record-armed track receives pads / keys / MIDI while recording or playing) ----
    var armedTrackId by mutableStateOf<String?>(null)
    var liveCapture by mutableStateOf(false)
    private fun captureLive(pitch: Int? = null, pad: DrumPad? = null, track: Track? = null) {
        if (!liveCapture || !isPlaying) return
        val step = ((playhead / session.stepSeconds).roundToIntSafe()) % 16
        if (pad != null) { update { it.copy(drumPattern = it.drumPattern.let { p -> if (p.steps[pad]!![step]) p else p.toggled(pad, step) }) }; return }
        val id = armedTrackId ?: track?.id ?: return
        if (kbRecording) { takeNotes += SynthNote(pitch = pitch!!, startTime = (playhead / session.stepSeconds).roundToIntSafe() * session.stepSeconds, duration = session.stepSeconds * 2); return }
        val q = (playhead / session.stepSeconds).roundToIntSafe() * session.stepSeconds
        updateTrack(id) { it.copy(notes = it.notes + SynthNote(pitch = pitch!!, startTime = q, duration = session.stepSeconds * 2)) }
    }
    // ---- Per-track on-screen keyboard ----
    var keyboardTrackId by mutableStateOf<String?>(null)
    /** Play [pitch] on track [trackId] now; returns press time (song seconds) if it should be recorded. */
    fun keyDown(trackId: String, pitch: Int): Double? {
        val t = session.tracks.firstOrNull { it.id == trackId } ?: return null
        viewModelScope.launch(Dispatchers.Default) {
            val x = when (t.kind) { TrackKind.GUITAR -> Fx.guitarNote(t.guitar, pitch, 0.6); else -> InstrumentSoundGenerator.note(t.waveform, pitch, if (t.waveform == SynthWaveform.STRINGS || t.waveform == SynthWaveform.CHOIR) 0.9 else 0.45, acid = t.name.contains("303") || t.name.contains("Acid", true)) }
            t.pedals.forEach { Fx.pedal(x, it, session.tempoBPM) }; Fx.amp(x, t); player.oneShot(x, session.masterVolume * t.volume) }
        lastKeyPitch = pitch
        // Free-play: sound only. Notes are captured only while keyboard-recording, or when this track is armed during playback.
        return if (isPlaying && (kbRecording && armedTrackId == trackId || (liveCapture && armedTrackId == trackId))) playhead else null
    }
    /** One-tap record on the keyboard panel: arm this track, enable capture, start transport. Tap again to stop. */
    private val takeNotes = mutableListOf<SynthNote>()
    var isKeyboardRecording by mutableStateOf(false); private set
    private var kbRecording: Boolean get() = isKeyboardRecording; set(v) { isKeyboardRecording = v }
    private var takeStart = 0.0
    /** Finalize an in-progress keyboard take (from Record toggle, transport Stop, or Pause). */
    private fun finishKeyboardTake() { if (!kbRecording) return
        val trackId = armedTrackId; kbRecording = false; liveCapture = false
        val t = session.tracks.firstOrNull { it.id == trackId }
        if (takeNotes.isNotEmpty() && t != null) { pendingTake = PendingTake(t.id, t.name, notes = takeNotes.toList()); status = "Take recorded – place it on ${t.name}?" }
        else status = "Stopped recording – no notes played"
        takeNotes.clear(); player.playhead = takeStart }
    fun toggleTrackRecord(trackId: String) {
        if (kbRecording) { val same = armedTrackId == trackId; pause(); if (same) return }
        takeNotes.clear(); pendingTake = null; kbRecording = true; takeStart = player.playhead
        armedTrackId = trackId; liveCapture = true; if (!isPlaying) play(); status = "● Recording – play the keys"
    }
    // ---- Pending takes: ask before placing ----
    data class PendingTake(val trackId: String?, val trackName: String, val clip: AudioClip? = null, val notes: List<SynthNote> = emptyList())
    var pendingTake by mutableStateOf<PendingTake?>(null)
    fun placePendingTake() { val p = pendingTake ?: return; pendingTake = null; player.stopPreview()
        if (p.clip != null) { if (p.trackId != null && session.tracks.any { it.id == p.trackId }) updateTrack(p.trackId) { it.copy(isMuted = false, clips = it.clips + p.clip) }
            else update { it.copy(tracks = it.tracks + Track(name = "Vocal Take ${it.tracks.size + 1}", clips = listOf(p.clip))) } }
        if (p.notes.isNotEmpty() && p.trackId != null) updateTrack(p.trackId) { it.copy(isMuted = false, notes = it.notes + p.notes) }
        rerenderIfPlaying(); status = "Your take is now part of your piece on ${p.trackName} – press Play to hear it" }
    fun discardPendingTake() { player.stopPreview(); pendingTake = null; status = "Take discarded" }
    fun auditionPendingTake() { val p = pendingTake ?: return; status = "Listening privately – not on the track yet"; viewModelScope.launch(Dispatchers.Default) {
        if (p.clip != null) player.audition(AudioDecoder.decode(File(stemsDir, p.clip.fileName)), session.masterVolume)
        else { val t = session.tracks.firstOrNull { it.id == p.trackId } ?: return@launch
            val tmp = session.copy(tracks = listOf(t.copy(clips = emptyList(), notes = p.notes, isMuted = false, isSoloed = false)), drumPattern = session.drumPattern.copy(isEnabled = false))
            val (l, _) = MixRenderer.render(tmp, stemsDir); val a = ((p.notes.minOf { it.startTime }) * SR).toInt().coerceIn(0, l.size)
            player.audition(l.copyOfRange(a, l.size), 1f) } } }

    // ---- Clip editing (bar-grid snap) ----
    private fun snapBar(x: Double) = kotlin.math.round(x / session.barSeconds) * session.barSeconds
    /** Stretch/shrink by whole bars; duration always lands on a bar multiple (min 1 bar). Longer than the source loops it. */
    fun resizeClipBars(trackId: String, clipId: String, bars: Int) = updateTrack(trackId) { t -> t.copy(clips = t.clips.map {
        if (it.id != clipId) it else { val b = session.barSeconds; val cur = kotlin.math.max(1.0, kotlin.math.round(it.duration / b)); it.copy(startTime = snapBar(it.startTime), duration = kotlin.math.max(1.0, cur + bars) * b) } }) }
    fun duplicateClip(trackId: String, clipId: String) = updateTrack(trackId) { t -> val c = t.clips.firstOrNull { it.id == clipId } ?: return@updateTrack t
        val end = kotlin.math.ceil((c.startTime + c.duration) / session.barSeconds - 1e-6) * session.barSeconds
        t.copy(clips = t.clips + c.copy(id = java.util.UUID.randomUUID().toString(), startTime = end)) }
    /** Polish (default behavior): snap clip start + length to the bar grid; on instrument tracks quantize notes to 16th steps and even out velocities. */
    fun polishTrack(trackId: String, clipId: String? = null) { val st = session.stepSeconds; val b = session.barSeconds
        updateTrack(trackId) { t -> t.copy(
            clips = t.clips.map { if (clipId != null && it.id != clipId) it else it.copy(startTime = snapBar(it.startTime), duration = kotlin.math.max(1.0, kotlin.math.round(it.duration / b)) * b) },
            notes = if (clipId != null) t.notes else t.notes.map { n -> n.copy(startTime = kotlin.math.round(n.startTime / st) * st, duration = kotlin.math.max(1.0, kotlin.math.round(n.duration / st)) * st * 0.95, velocity = (n.velocity * 0.5f + 0.4f).coerceIn(0.3f, 1f)) }.distinctBy { it.pitch to it.startTime }) }
        status = "Polished: snapped to bar grid" + if (clipId == null) " and quantized notes" else "" }
    fun keyUp(trackId: String, pitch: Int, start: Double?) {
        start ?: return
        val step = session.stepSeconds; val q = kotlin.math.round(start / step) * step
        val dur = (kotlin.math.round((playhead - start).coerceAtLeast(step) / step) * step).coerceAtLeast(step)
        if (kbRecording && armedTrackId == trackId) { takeNotes += SynthNote(pitch = pitch, startTime = q, duration = dur); status = "Captured ${takeNotes.size} notes"; return }
        updateTrack(trackId) { it.copy(notes = it.notes + SynthNote(pitch = pitch, startTime = q, duration = dur)) }
        status = "Recorded ${Tuner.names[pitch % 12]}${pitch / 12 - 1} into ${session.tracks.firstOrNull { it.id == trackId }?.name}"
    }
    private fun Double.roundToIntSafe() = kotlin.math.round(this).toInt().coerceAtLeast(0)
    fun midiNote(pitch: Int, vel: Int) {
        val t = session.tracks.firstOrNull { it.id == (armedTrackId ?: selectedTrackId) && it.kind != TrackKind.AUDIO }
        if (t != null) triggerTrackNote(t, pitch) else trigger(InstrumentLibrary.first { it.waveform != null }, pitch)
        status = "MIDI in: note $pitch vel $vel"
    }

    // ---- AI suggestions (any instrument track) ----
    var lastKeyPitch by mutableStateOf<Int?>(null)
    fun aiGenerate(trackId: String, bars: Int = 4, role: SongAI.Role? = null, replace: Boolean = false) {
        val t = session.tracks.firstOrNull { it.id == trackId } ?: return
        val start = playhead; val notes = SongAI.generate(session, t, start, bars, roleOverride = role)
        val end = notes.maxOfOrNull { it.endTime } ?: start
        updateTrack(trackId) { tr -> tr.copy(notes = (if (replace) tr.notes.filter { it.endTime <= notes.first().startTime || it.startTime >= end } else tr.notes) + notes) }
        status = "AI wrote a ${bars}-bar ${(role ?: SongAI.role(t)).label.lowercase()} for ${t.name} in ${SongAI.detectKey(session).name} (undo to remove)"
    }
    fun aiChordToTrack(trackId: String, degree: Int) {
        val k = SongAI.detectKey(session); val st = kotlin.math.floor(playhead / session.barSeconds) * session.barSeconds
        val notes = SongAI.chordTones(k, degree).map { pc -> SynthNote(pitch = 48 + pc + if (pc < k.root) 12 else 0, startTime = st, duration = session.barSeconds * 0.98, velocity = 0.6f) }
        updateTrack(trackId) { it.copy(notes = it.notes + notes) }; session.tracks.firstOrNull { it.id == trackId }?.let { t -> notes.forEach { n -> keyDown(t.id, n.pitch) } }
        status = "Added ${SongAI.chordName(k, degree)} at bar ${(st / session.barSeconds).toInt() + 1}"
    }
    fun applyPreset(trackId: String, p: FxPreset) {
        updateTrack(trackId) { t -> t.copy(amp = p.amp, pedals = p.pedals.map { PedalSlot(it.first, amount = it.second) },
            kind = when { t.kind == TrackKind.AUDIO || t.kind == TrackKind.LYRICS -> t.kind; p.guitar != null -> TrackKind.GUITAR; p.waveform != null -> TrackKind.SYNTH; else -> t.kind },
            guitar = if (t.kind != TrackKind.AUDIO && t.kind != TrackKind.LYRICS) p.guitar ?: t.guitar else t.guitar, waveform = p.waveform ?: t.waveform) }
        session.tracks.firstOrNull { it.id == trackId }?.let { t -> if (t.kind != TrackKind.AUDIO && t.kind != TrackKind.LYRICS) keyDown(t.id, if (SongAI.role(t) == SongAI.Role.BASS) 40 else 52) }
        status = "Preset ${p.name} loaded on ${session.tracks.firstOrNull { it.id == trackId }?.name}"
    }

    // ---- Lyrics track + AI vocals (natural device TTS voice) ----
    fun addLyricsTrack(theme: String = ""): String {
        val (genre, info, form) = SongAI.analyze(session)
        val t = Track(name = "Lyrics & Vocals", kind = TrackKind.LYRICS, lyrics = SongAI.writeLyrics(theme, genre), genre = genre, songInfo = "$genre · $info\nForm: $form",
            vocalStyle = when { genre.contains("Hip") -> VocalStyle.RAP; genre.contains("Rock") || genre.contains("Metal") -> VocalStyle.ROCK; genre.contains("R&B") || genre.contains("Jazz") -> VocalStyle.SOUL; genre.contains("Country") -> VocalStyle.COUNTRY; else -> VocalStyle.POP },
            pedals = listOf(PedalSlot(PedalType.COMPRESSOR, amount = 0.4f), PedalSlot(PedalType.REVERB, amount = 0.25f)), volume = 0.9f)
        update { it.copy(tracks = it.tracks + t) }; selectedTrackId = t.id; lyricsTrackId = t.id; status = "AI wrote lyrics – $genre"; return t.id
    }
    var lyricsTrackId by mutableStateOf<String?>(null)
    fun reanalyze(id: String) { val (g, i, f) = SongAI.analyze(session); updateTrack(id) { it.copy(genre = g, songInfo = "$g · $i\nForm: $f") } }
    fun rewriteLyrics(id: String, theme: String) { val t = session.tracks.firstOrNull { it.id == id } ?: return; updateTrack(id) { it.copy(lyrics = SongAI.writeLyrics(theme, t.genre)) } }
    var voiceInfo by mutableStateOf("")
    private val tts: android.speech.tts.TextToSpeech by lazy { android.speech.tts.TextToSpeech(ctx) { ok -> ttsReady = ok == android.speech.tts.TextToSpeech.SUCCESS; voiceInfo = if (ttsReady) "Voice engine ready" else "No text-to-speech engine on this phone – install Speech Services by Google" } }
    @Volatile private var ttsReady = false
    private val femaleIds = listOf("sfg", "tpf", "tpc", "iob", "iog", "female", "fis", "cxx")
    private val maleIds = listOf("iol", "iom", "tpd", "male", "rjs", "gbd")
    private fun pickVoice(g: VoiceGender): android.speech.tts.Voice? {
        val vs = runCatching { tts.voices }.getOrNull()?.filter { it.locale.language == "en" && !it.features.contains("notInstalled") } ?: return null
        fun isF(v: android.speech.tts.Voice) = femaleIds.any { v.name.contains(it, true) } && !v.name.contains("-male", true)
        fun isM(v: android.speech.tts.Voice) = maleIds.any { v.name.contains(it, true) } && !isF(v)
        val pool = when (g) { VoiceGender.FEMALE -> vs.filter(::isF); VoiceGender.MALE -> vs.filter(::isM); else -> vs }.ifEmpty { vs }
        return pool.sortedWith(compareBy<android.speech.tts.Voice>({ it.isNetworkConnectionRequired }, { -it.quality }, { it.locale.country != "US" })).firstOrNull()
    }
    /** Renders each lyric line with the device's natural (neural on Pixel) voice and lays the takes on the lyrics track, two bars per line from the playhead. */
    fun singLyrics(id: String) {
        val t = session.tracks.firstOrNull { it.id == id } ?: return; tts
        viewModelScope.launch(Dispatchers.IO) {
            var w = 0; while (!ttsReady && w < 40) { kotlinx.coroutines.delay(100); w++ }
            if (!ttsReady) { status = voiceInfo; return@launch }
            val v = pickVoice(t.voiceGender); if (v != null) tts.voice = v
            // Pitch = style colour (plus a gender nudge only when no gendered voice exists); rate = style feel scaled gently by tempo (sqrt keeps words intelligible).
            val gNudge = if (v == null) when (t.voiceGender) { VoiceGender.MALE -> 0.88f; VoiceGender.FEMALE -> 1.1f; else -> 1f } else 1f
            tts.setPitch((t.vocalStyle.pitch * gNudge).coerceIn(0.6f, 1.6f)); tts.setSpeechRate((t.vocalStyle.rate * kotlin.math.sqrt(session.tempoBPM / 110f)).coerceIn(0.6f, 1.4f))
            voiceInfo = "Voice: ${v?.name ?: "default"}${if (v != null && v.quality >= android.speech.tts.Voice.QUALITY_HIGH) " (high quality)" else ""}"
            val lines = t.lyrics.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val bar = session.barSeconds; var time = kotlin.math.floor(playhead / bar) * bar; val clips = mutableListOf<AudioClip>(); var k = 0
            for (ln in lines) {
                if (ln.startsWith("[")) { if (k > 0) time += bar; continue }
                progress = "Singing line ${k + 1}/${lines.count { !it.startsWith("[") }}…" to (k.toFloat() / lines.size)
                val f = File(stemsDir, "vox-${t.id.take(6)}-${System.currentTimeMillis()}-$k.wav")
                val latch = java.util.concurrent.CountDownLatch(1); val uid = "l$k"
                tts.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                    override fun onStart(u: String?) {}; override fun onDone(u: String?) { latch.countDown() }
                    @Deprecated("") override fun onError(u: String?) { latch.countDown() } })
                tts.synthesizeToFile(ln, android.os.Bundle(), f, uid); latch.await(20, java.util.concurrent.TimeUnit.SECONDS)
                runCatching { if (f.length() > 44) { val polished = Fx.vocalPolish(AudioDecoder.decode(f).copyOf(), t.vocalStyle); MixRenderer.writeWavInterleaved(f, polished, 1) } }
                val d = AudioDecoder.durationOf(f)
                if (d > 0) { clips += AudioClip(fileName = f.name, startTime = time, duration = d); time += kotlin.math.ceil((d + 0.15) / bar).coerceAtLeast(1.0) * bar }
                k++
            }
            withContext(Dispatchers.Main) {
                lastPush = 0; updateTrack(id) { it.copy(clips = clips) }; progress = null
                status = if (clips.isEmpty()) "Vocal render failed – check Settings › Text-to-speech" else "AI vocals: ${clips.size} lines sung on ${t.name} ($voiceInfo)"; refreshLibrary() }
        }
    }

    // ---- Automation ----
    fun addAutoPoint(id: String, pan: Boolean, time: Double, v: Float) = updateTrack(id) { if (pan) it.copy(panAuto = (it.panAuto + AutoPoint(time, v)).sortedBy { p -> p.time }) else it.copy(volAuto = (it.volAuto + AutoPoint(time, v)).sortedBy { p -> p.time }) }
    fun clearAuto(id: String, pan: Boolean) = updateTrack(id) { if (pan) it.copy(panAuto = emptyList()) else it.copy(volAuto = emptyList()) }

    // ---- Import, stem split, slicing ----
    var progress by mutableStateOf<Pair<String, Float>?>(null); private set
    fun importAudio(uri: android.net.Uri, split: Boolean) { viewModelScope.launch(Dispatchers.IO) {
        try {
            val cr = ctx.contentResolver
            var name = "import"; cr.query(uri, null, null, null, null)?.use { c -> val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME); if (i >= 0 && c.moveToFirst()) name = c.getString(i) }
            val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_"); val f = File(stemsDir, java.util.UUID.randomUUID().toString().take(8) + "_" + safe)
            progress = "Importing $name…" to 0.01f
            cr.openInputStream(uri)?.use { i -> f.outputStream().use { i.copyTo(it) } }
            if (f.extension.lowercase() !in setOf("wav", "mp3", "m4a", "aac", "flac", "ogg", "aif", "aiff", "caf")) { val g = File(f.path + ".m4a"); f.renameTo(g) }
            val file = stemsDir.listFiles()?.firstOrNull { it.name.startsWith(f.name) } ?: f
            val d = AudioDecoder.durationOf(file)
            if (d <= 0) { status = "Couldn't decode $name"; progress = null; return@launch }
            withContext(Dispatchers.Main) {
                if (split) splitStems(file) else { update { it.copy(tracks = it.tracks + Track(name = SampleLibrary.displayName(file.name), clips = listOf(AudioClip(fileName = file.name, startTime = 0.0, duration = d)))) }
                    progress = null; status = "Imported $name onto a new track" }
                refreshLibrary() }
        } catch (e: Exception) { status = "Import failed: ${e.message}"; progress = null }
    } }
    fun splitStems(file: File) { viewModelScope.launch(Dispatchers.Default) {
        try {
            val stems = StemSeparator.separate(file, stemsDir) { m, p -> progress = m to p }
            val d = AudioDecoder.durationOf(file)
            withContext(Dispatchers.Main) {
                update { s -> s.copy(tracks = s.tracks + stems.map { (disp, fn) -> Track(name = disp, clips = listOf(AudioClip(fileName = fn, startTime = 0.0, duration = d))) }) }
                refreshLibrary(); status = "Split ${SampleLibrary.displayName(file.name)} into ${stems.size} stem tracks"; progress = null }
        } catch (e: Exception) { status = "Stem split failed: ${e.message}"; progress = null }
    } }
    /** Slice a sample into equal parts placed as consecutive clips on a new track; slices are also exposed as pads. */
    fun sliceToTrack(item: SampleLibraryItem, n: Int) {
        val len = item.duration / n
        update { it.copy(tracks = it.tracks + Track(name = "${item.name} slices", clips = (0 until n).map { k -> AudioClip(fileName = item.id, startTime = k * len, duration = len, sourceStartTime = k * len, sourceDuration = item.duration) })) }
        status = "Sliced ${item.name} into $n clips"
    }
    fun previewSlice(item: SampleLibraryItem, n: Int, k: Int) { viewModelScope.launch(Dispatchers.Default) {
        val x = AudioDecoder.decode(item.file); val a = x.size * k / n; val b = x.size * (k + 1) / n; player.oneShot(x.copyOfRange(a, b), session.masterVolume) } }

    // ---- MIDI files ----
    fun exportMidi(trackId: String): File? { val t = session.tracks.firstOrNull { it.id == trackId } ?: return null
        val f = File(TrackStorage.exportsDir(ctx), t.name.replace(Regex("[^A-Za-z0-9]"), "_") + ".mid"); MidiFile.write(f, t.notes, session.tempoBPM); status = "Exported MIDI to ${f.absolutePath}"; return f }
    fun importMidi(uri: android.net.Uri) { viewModelScope.launch(Dispatchers.IO) {
        val notes = runCatching { MidiFile.read(ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }, session.tempoBPM) }.getOrElse { emptyList() }
        withContext(Dispatchers.Main) { if (notes.isEmpty()) status = "No notes found in MIDI file" else {
            update { it.copy(tracks = it.tracks + Track(name = "MIDI ${it.tracks.size + 1}", kind = TrackKind.SYNTH, notes = notes)) }; status = "Imported ${notes.size} MIDI notes" } } } }

    override fun onCleared() { player.stop(); recorder.stop(); save() }
}
