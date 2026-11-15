package com.studiospark.app.engine.shared

import com.studiospark.app.core.*
import com.studiospark.app.dsp.Family
import java.util.EnumMap

/**
 * One tab's isolated session on the shared engine: its own tracks (strips), faders, pans and gear chains.
 * The STUDIO session is a view onto the live Studio project; IMPORT and SONGWRITER own a private blank Project
 * and hold no reference to the Studio one.
 */
class EngineSession internal constructor(val kind: SessionKind, private val projectRef: () -> Project, private val gear: GearLibrary) {
    val project: Project get() = projectRef()
    /** The session's strips. */
    val tracks: List<Track> get() = project.tracks
    fun track(id: String) = project.track(id)

    fun addTrack(name: String, family: Family, instrumentId: String, notes: List<MidiNote> = emptyList(), clips: List<Clip> = emptyList(),
                 lyrics: List<String> = emptyList(), color: Long = TrackColors.all[project.tracks.size % TrackColors.all.size]): Track =
        Track(name = name, family = family, instrumentId = instrumentId, color = color).also {
            it.notes.addAll(notes); it.clips.addAll(clips); it.lyrics.addAll(lyrics); project.tracks.add(it) }
    fun removeTrack(id: String) { project.tracks.removeAll { it.id == id }; gear.retain(project.tracks) }
    fun setFader(id: String, db: Float) { track(id)?.volumeDb = db.coerceIn(-60f, 12f) }
    fun setPan(id: String, pan: Float) { track(id)?.pan = pan.coerceIn(-1f, 1f) }
    fun setMute(id: String, mute: Boolean) { track(id)?.mute = mute }
    fun setCaptureMode(id: String, mode: CaptureMode) { track(id)?.let { SharedEngine.setCaptureMode(it, mode) } }
    /** The only way gear reaches a track: a user add. Loads the universal library on first use. */
    fun addGear(trackId: String, gearId: String) = gear.addGear(requireNotNull(track(trackId)), gearId)
    fun setInstrument(trackId: String, instrumentId: String) = gear.setInstrument(requireNotNull(track(trackId)), instrumentId)
    /** Empties this session's strips (non-Studio sessions only; Studio edits go through StudioVM's undoable edit). */
    fun clear() { check(kind != SessionKind.STUDIO) { "Use StudioVM.newSession for the Studio project" }; project.tracks.clear(); gear.retain(emptyList()) }
}

/**
 * Hands out one isolated [EngineSession] per [SessionKind] over a single lazily created [SharedEngine] and a single
 * universal [GearLibrary]. Constructing the host creates no session, engine, rig, sample or catalog (blank launch).
 */
class SessionHost(private val studioProject: () -> Project, backendFactory: () -> AudioBackend, val gear: GearLibrary = GearLibrary()) {
    val engine = SharedEngine(backendFactory)
    private val sessions = EnumMap<SessionKind, EngineSession>(SessionKind::class.java)

    @Synchronized fun createdKinds(): Set<SessionKind> = sessions.keys.toSet()
    /** The session if it exists; never creates one. */
    @Synchronized fun peek(kind: SessionKind): EngineSession? = sessions[kind]
    /** The session, created blank the first time its tab asks for it. */
    @Synchronized fun session(kind: SessionKind): EngineSession = sessions.getOrPut(kind) {
        if (kind == SessionKind.STUDIO) EngineSession(kind, studioProject, gear)
        else { val own = Sessions.blank().also { it.name = kind.label }; EngineSession(kind, { own }, gear) }
    }
    /** Discards an Import/Songwriter session; it comes back blank on next use. The Studio project is untouched. */
    @Synchronized fun close(kind: SessionKind) { require(kind != SessionKind.STUDIO); sessions.remove(kind)
        gear.retain(sessions.values.flatMap { it.tracks }) }

    /**
     * Manual handoff: deep-copies [trackIds] (all if null) from the [trigger]'s session into [into] (the Studio project;
     * callers pass it inside StudioVM.edit so the handoff is one undo step). Audio, MIDI, gear chain and knob settings,
     * volume, pan, names and lyrics are copied with nothing shared, so later edits on either side never leak across.
     */
    @Synchronized fun handoffToStudio(trigger: HandoffTrigger, into: Project = studioProject(), trackIds: Collection<String>? = null): List<Track> {
        val src = sessions[trigger.from] ?: return emptyList()
        require(src.project !== into) { "Handoff target must be the Studio project, not the source session" }
        val copies = src.tracks.filter { trackIds == null || it.id in trackIds }.map { TrackCopy.deep(it) }
        into.tracks.addAll(copies)
        return copies
    }
}

/** Lossless track copy. Pooled audio is immutable and app-wide, so clips keep their audioId; everything mutable is cloned. */
object TrackCopy {
    fun deep(t: Track): Track = Track(
        name = t.name, family = t.family, instrumentId = t.instrumentId, color = t.color,
        volumeDb = t.volumeDb, pan = t.pan, mute = t.mute, solo = t.solo,
        recArm = false, inputMonitor = false, inputSource = t.inputSource,
        clips = t.clips.map { it.copy(newId()) }.toMutableList(), notes = t.notes.toMutableList(), automation = t.automation.toMutableList(),
        rig = t.rig.copy(), playlist = t.playlist.toMutableList(), kitId = t.kitId, tuning = t.tuning, lyrics = t.lyrics.toMutableList(),
        voiceStyle = t.voiceStyle, automationOn = t.automationOn, useMic = t.useMic, printTone = t.printTone, paused = t.paused, jamRole = t.jamRole,
    )
}
