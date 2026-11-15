package com.studiospark.app.songwriter

import androidx.compose.runtime.*
import com.studiospark.app.core.MidiNote

/**
 * The Songwriter tab's own blank workspace. It never touches the active Studio project; its progression, lyrics and
 * generated parts reach Studio only through StudioVM.songwriterToStudio, which the user triggers.
 */
class SongwriterSession {
    var keyRoot by mutableStateOf<Int?>(null)          // null = no key locked yet
    var mode by mutableStateOf(Mode.MAJOR)
    val key: Key? get() = keyRoot?.let { Key(it, mode) }
    val progression = mutableStateListOf<Chord>()
    /** One lyric line per bar of the progression (timeline-synced). */
    val lyrics = mutableStateListOf<String>()
    var tempo by mutableFloatStateOf(100f)
    var style by mutableStateOf(Accompaniment.Style.POP)
    var melody by mutableStateOf<List<MidiNote>>(emptyList())
    var melodyChords by mutableStateOf<List<Chord>>(emptyList())
    var listening by mutableStateOf(false)
    var playingBar by mutableIntStateOf(-1)
    var status by mutableStateOf<String?>(null)

    fun lockKey(root: Int) { keyRoot = root; val k = key!!; val snapped = progression.map { k.snap(it) }; progression.clear(); progression.addAll(snapped) }
    fun suggestions(): List<Progressions.Suggestion> = key?.let { Progressions.suggest(it, progression.lastOrNull(), progression) } ?: emptyList()
    fun add(c: Chord) { val k = key; progression += if (k != null) k.snap(c) else c; while (lyrics.size < progression.size) lyrics += "" }
    fun removeLast() { if (progression.isNotEmpty()) progression.removeAt(progression.lastIndex); while (lyrics.size > progression.size && lyrics.lastOrNull().isNullOrEmpty()) lyrics.removeAt(lyrics.lastIndex) }
    fun setLyric(bar: Int, text: String) { while (lyrics.size <= bar) lyrics += ""; lyrics[bar] = text }
    fun capo() = ChordDictionary.capoFor(progression)
    fun beatFrames(sr: Int) = (sr * 60.0 / tempo).toLong()
    fun parts(sr: Int) = Accompaniment.generate(progression, beatFrames(sr), style)
    /** Melody → chords (requires a locked key; uses the detected melody's most common tonic otherwise). */
    fun harmonize(sr: Int) { val k = key ?: return; melodyChords = Harmonizer.chordsFor(k, melody, beatFrames(sr) * 4) }
    /** Tap-entry melody: appends [midi] as a one-beat note, then re-harmonizes. */
    fun tapNote(midi: Int, sr: Int) { val b = beatFrames(sr); val start = melody.maxOfOrNull { it.start + it.length } ?: 0L
        melody = melody + MidiNote(midi, start, b, 100); harmonize(sr) }
    fun clearMelody() { melody = emptyList(); melodyChords = emptyList() }
    fun useMelodyChords() { melodyChords.forEach { add(it) } }
}
