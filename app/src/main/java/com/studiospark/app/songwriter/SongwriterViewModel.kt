package com.studiospark.app.songwriter

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.studiospark.app.core.ENGINE_SR
import com.studiospark.app.core.MidiNote
import com.studiospark.app.core.Project
import com.studiospark.app.core.Track
import com.studiospark.app.dsp.Family
import com.studiospark.app.engine.shared.HandoffTrigger
import com.studiospark.app.engine.shared.SessionHost
import com.studiospark.app.engine.shared.SessionKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A string/fret position on the fretboard. string 0 = low E … 5 = high e; fret 0 = open. */
data class FretPos(val string: Int, val fret: Int) { val midi get() = Notes.STANDARD[string] + fret }

/** One lyric line per bar of the progression, placed on the timeline at its bar start. */
data class LyricLine(val bar: Int, val chord: Chord, val text: String, val startFrame: Long, val playing: Boolean)

/** Immutable snapshot of the Songwriter tab. The default value is the blank-launch state: no key, nothing loaded. */
data class SongwriterUiState(
    val keyRoot: Int? = null,
    val mode: Mode = Mode.MAJOR,
    val progression: List<Chord> = emptyList(),
    /** Ranked next chords (Progressions.suggest: hand-written functional-harmony table + session weighting, not a song corpus). */
    val suggestions: List<Progressions.Suggestion> = emptyList(),
    /** The chord whose fingering is shown on the fretboard (the top suggestion unless the user picked one). */
    val focusChord: Chord? = null,
    val voicings: List<Fingering> = emptyList(),
    val voicingIndex: Int = 0,
    val capo: ChordDictionary.Capo? = null,
    val inScale: Set<Int> = emptySet(),
    val outOfScale: Set<Int> = emptySet(),
    val melody: List<MidiNote> = emptyList(),
    val melodyChords: List<Chord> = emptyList(),
    val taps: List<FretPos> = emptyList(),
    val style: Accompaniment.Style = Accompaniment.Style.POP,
    val tempo: Float = 100f,
    /** Generated drums/bass/guitar for the current progression; null until the user generates (or nothing to generate). */
    val parts: Accompaniment.Parts? = null,
    val lyrics: List<LyricLine> = emptyList(),
    val playingBar: Int = -1,
    val listening: Boolean = false,
    val status: String? = null,
) {
    val key: Key? get() = keyRoot?.let { Key(it, mode) }
    val fingering: Fingering? get() = voicings.getOrNull(voicingIndex)
    val isBlank get() = keyRoot == null && progression.isEmpty() && melody.isEmpty() && lyrics.all { it.text.isEmpty() } && parts == null
}

/** Sends the Songwriter session to Studio. Implemented by StudioVM (undoable edit through the shared SessionHost). */
fun interface SongwriterHandoff { fun sendToStudio(session: SongwriterSession) }

/**
 * Songwriter tab state holder (2.2.0). Wraps the existing engine — Theory (Key/Progressions), ChordDictionary,
 * Harmonizer, Accompaniment — and the tab's own [SongwriterSession]; it reimplements none of them.
 *
 * Isolation: holds no reference to the Studio project or StudioVM. The only way out is [sendToStudio], a user button,
 * which calls [handoff]. Imported tones are never touched: this class adds no amp, pedal or rig to anything.
 * Blank launch: created only when the Songwriter tab is first composed; the initial state is empty with no key locked.
 */
class SongwriterViewModel(
    val session: SongwriterSession = SongwriterSession(),
    private val handoff: SongwriterHandoff? = null,
    private val sampleRate: Int = ENGINE_SR,
) : ViewModel() {
    private val _state = MutableStateFlow(SongwriterUiState())
    val state: StateFlow<SongwriterUiState> = _state.asStateFlow()
    private var focus: Chord? = null
    private var voicingIndex = 0
    private var taps: List<FretPos> = emptyList()
    private var parts: Accompaniment.Parts? = null
    private var partsFor: List<Chord>? = null

    init { if (!session.isBlankSession()) refresh() }

    // ---------------------------------------------------------------- key lock (12-note picker, one locked at a time)
    /** One tap sets and locks the key; tapping another root replaces it. Existing chords snap into the new key. */
    fun lockRoot(root: Int) { require(root in 0..11) { "root must be a pitch class 0..11" }; session.lockKey(root); focus = null; voicingIndex = 0; refresh() }
    fun setMode(mode: Mode) { session.mode = mode; session.keyRoot?.let { session.lockKey(it) }; focus = null; refresh() }
    fun toggleMode() = setMode(if (session.mode == Mode.MAJOR) Mode.MINOR else Mode.MAJOR)

    // ---------------------------------------------------------------- progression + suggestions + fingerings
    fun addChord(c: Chord) { session.add(c); focus = null; voicingIndex = 0; refresh() }
    fun pickSuggestion(s: Progressions.Suggestion) = addChord(s.chord)
    fun undoChord() { session.removeLast(); focus = null; refresh() }
    /** Shows [c]'s fingering on the fretboard without adding it. */
    fun focusChord(c: Chord?) { focus = c; voicingIndex = 0; refresh() }
    /** Cycles through the dictionary's voicings for the focused chord (open shape, then barre shapes). */
    fun nextVoicing() { val n = _state.value.voicings.size; if (n > 1) { voicingIndex = (voicingIndex + 1) % n; refresh() } }
    fun fingeringFor(c: Chord): Fingering? = ChordDictionary.best(c)

    // ---------------------------------------------------------------- melody → chords
    fun tapNote(midi: Int) { session.tapNote(midi, sampleRate); refresh() }
    fun tapFret(string: Int, fret: Int) { require(string in 0..5 && fret in 0..24); val p = FretPos(string, fret); taps = taps + p; session.tapNote(p.midi, sampleRate); refresh() }
    /** Mic-detected notes (from Pitch.track, already in engine frames) replace the melody and are harmonized. */
    fun micNotes(notes: List<MidiNote>, status: String? = null) { session.melody = notes; session.harmonize(sampleRate); session.status = status; taps = emptyList(); refresh() }
    fun clearMelody() { session.clearMelody(); taps = emptyList(); refresh() }
    fun useMelodyChords() { session.useMelodyChords(); focus = null; refresh() }

    // ---------------------------------------------------------------- AI drums / bass / guitar (rule-based, see Accompaniment)
    fun setStyle(s: Accompaniment.Style) { session.style = s; if (parts != null) generate() else refresh() }
    fun setTempo(bpm: Float) { session.tempo = bpm.coerceIn(40f, 240f); if (parts != null) generate() else refresh() }
    /** Generates drums, bass and guitar from the current progression. Null when there is no progression. */
    fun generate(): Accompaniment.Parts? {
        parts = if (session.progression.isEmpty()) null else session.parts(sampleRate); partsFor = session.progression.toList(); refresh(); return parts
    }

    // ---------------------------------------------------------------- lyrics, synced to the timeline
    fun setLyric(bar: Int, text: String) { session.setLyric(bar, text); refresh() }
    fun setPlayingBar(bar: Int) { session.playingBar = bar; refresh() }
    /** Timeline position (engine frames) → bar index of the progression, or -1 when outside it. */
    fun barAt(frame: Long): Int { val bar = session.beatFrames(sampleRate) * 4; val b = if (frame < 0 || bar <= 0) -1 else (frame / bar).toInt(); return if (b in session.progression.indices) b else -1 }
    fun seek(frame: Long) = setPlayingBar(barAt(frame))
    fun setListening(on: Boolean) { session.listening = on; refresh() }

    // ---------------------------------------------------------------- handoff (user button only)
    /** "Send to Studio": the only path from this session into Studio. Returns false when there is nothing to send. */
    fun sendToStudio(): Boolean { if (session.progression.isEmpty()) return false; val h = handoff ?: return false; h.sendToStudio(session); return true }

    /** Re-reads the session (call after an outside change such as mic capture finishing). */
    fun refresh() {
        val s = session; val k = s.key
        if (partsFor != null && partsFor != s.progression.toList()) { parts = null; partsFor = null }
        val f = focus ?: if (k != null) Progressions.suggest(k, s.progression.lastOrNull(), s.progression).firstOrNull()?.chord else null
        val voicings = f?.let { ChordDictionary.voicings(it) } ?: emptyList()
        if (voicingIndex >= voicings.size) voicingIndex = 0
        val bar = s.beatFrames(sampleRate) * 4
        _state.value = SongwriterUiState(
            keyRoot = s.keyRoot, mode = s.mode, progression = s.progression.toList(), suggestions = s.suggestions(),
            focusChord = f, voicings = voicings, voicingIndex = voicingIndex, capo = s.capo(),
            inScale = k?.scale ?: emptySet(), outOfScale = k?.let { (0..11).toSet() - it.scale } ?: emptySet(),
            melody = s.melody, melodyChords = s.melodyChords, taps = taps, style = s.style, tempo = s.tempo, parts = parts,
            lyrics = s.progression.mapIndexed { i, c -> LyricLine(i, c, s.lyrics.getOrElse(i) { "" }, i * bar, s.playingBar == i) },
            playingBar = s.playingBar, listening = s.listening, status = s.status)
    }

    companion object {
        fun factory(session: SongwriterSession, handoff: SongwriterHandoff?): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = SongwriterViewModel(session, handoff) as T
        }
    }
}

private fun SongwriterSession.isBlankSession() = keyRoot == null && progression.isEmpty() && melody.isEmpty()

/**
 * Builds the Songwriter tracks inside the isolated SONGWRITER session of the shared engine, then deep-copies them into
 * [into] (the Studio project) via [SessionHost.handoffToStudio]. StudioVM calls this inside its undoable edit.
 * Tracks: drums, bass, guitar (MIDI, default clean rigs) and a lyric-bearing vocal track when lyrics exist.
 */
object SongwriterToStudio {
    fun send(s: SongwriterSession, host: SessionHost, into: Project, drumInstrumentId: String, sr: Int = ENGINE_SR): List<Track> {
        if (s.progression.isEmpty()) return emptyList()
        val sw = host.session(SessionKind.SONGWRITER); sw.clear()
        val parts = s.parts(sr)
        sw.addTrack("Songwriter · Drums", Family.DRUMS, drumInstrumentId, parts.drums)
        sw.addTrack("Songwriter · Bass", Family.BASS, "bass_0", parts.bass)
        sw.addTrack("Songwriter · Guitar", Family.GUITAR, "gtr_0", parts.guitar)
        if (s.lyrics.any { it.isNotBlank() }) sw.addTrack("Songwriter · Lyrics", Family.VOCAL, "vox_mic", lyrics = s.lyrics.toList())
        return host.handoffToStudio(HandoffTrigger.SONGWRITER_SEND_TO_STUDIO, into)
    }
}
