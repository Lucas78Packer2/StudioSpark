package com.studiospark.app.core

import com.studiospark.app.dsp.Family

enum class RecordMode(val label: String) { NORMAL("Normal"), PUNCH("Punch In/Out"), LOOP("Loop record") }
enum class TransportState { STOPPED, PLAYING, COUNT_IN, RECORDING }

/**
 * Pro Tools-style tracking logic, independent of audio I/O so it can be unit-tested.
 * Record requires at least one record-enabled track (or an explicit capture target such as the jam room).
 * Every pass becomes a private Take; nothing is placed on the timeline until [place] is called.
 */
class TrackingSession {
    var state = TransportState.STOPPED; private set
    var mode = RecordMode.NORMAL
    var loopPlayback = false
    var countInBars = 1
    var metronome = true
    var position = 0L; private set
    var passStart = 0L; private set
    var countInRemaining = 0L; private set
    var previewTakeId: String? = null; private set
    var passIndex = 0; private set

    fun locate(pos: Long) { position = pos.coerceAtLeast(0) }
    fun returnToZero() = locate(0)
    fun play() { if (state == TransportState.STOPPED) state = TransportState.PLAYING; previewTakeId = null }
    fun stop() { state = TransportState.STOPPED; countInRemaining = 0 }

    sealed class StartResult { data class Started(val countIn: Boolean) : StartResult(); data class Refused(val why: String) : StartResult() }

    fun record(p: Project, externalTarget: Boolean = false): StartResult {
        if (p.tracks.none { it.recArm } && !externalTarget) return StartResult.Refused("Record-enable (arm) at least one track first")
        if (mode == RecordMode.PUNCH && p.punchOut <= p.punchIn) return StartResult.Refused("Set a punch range (In before Out)")
        if (mode == RecordMode.LOOP && p.loopEnd <= p.loopStart) return StartResult.Refused("Set a loop range for loop record")
        previewTakeId = null; passIndex = 0
        position = when (mode) { RecordMode.PUNCH -> (p.punchIn - p.samplesPerBar).coerceAtLeast(0); RecordMode.LOOP -> p.loopStart; RecordMode.NORMAL -> position }
        passStart = if (mode == RecordMode.PUNCH) p.punchIn else position
        countInRemaining = if (countInBars > 0 && mode != RecordMode.PUNCH) p.samplesPerBar * countInBars else 0
        state = if (countInRemaining > 0) TransportState.COUNT_IN else TransportState.RECORDING
        return StartResult.Started(countInRemaining > 0)
    }

    /** True when input should be written at the current position. */
    fun capturing(p: Project): Boolean = state == TransportState.RECORDING && (mode != RecordMode.PUNCH || position in p.punchIn until p.punchOut)

    /** Advance the playhead by [n] frames. Returns true when a loop boundary was crossed (in loop-record this closes a pass). */
    fun advance(p: Project, n: Int): Boolean {
        if (state == TransportState.STOPPED) return false
        if (state == TransportState.COUNT_IN) { countInRemaining -= n; if (countInRemaining <= 0) { countInRemaining = 0; state = TransportState.RECORDING }; return false }
        position += n
        val looping = (loopPlayback && state == TransportState.PLAYING) || (mode == RecordMode.LOOP && state == TransportState.RECORDING)
        if (looping && p.loopEnd > p.loopStart && position >= p.loopEnd) { position = p.loopStart + (position - p.loopEnd); if (state == TransportState.RECORDING) { passIndex++; passStart = p.loopStart }; return true }
        return false
    }

    /** Create private takes for a finished pass. [audio] maps trackId (or null for a new track) to (audioId, length). */
    fun finishPass(p: Project, audio: List<Triple<String?, String, Long>>, source: String, start: Long = passStart): List<Take> =
        audio.map { (trackId, audioId, len) ->
            val owner = p.track(trackId); val n = p.takes.count { it.trackId == trackId } + 1
            Take(audioId = audioId, trackId = trackId, start = start, length = len, name = "${owner?.name ?: source} · Take $n", source = source).also { p.takes.add(it) }
        }

    fun listen(takeId: String?) { previewTakeId = takeId }

    /** Place a take on its track (or a new track for mic/jam recordings). Returns the track it landed on. */
    fun place(p: Project, takeId: String, toTrackId: String? = null, family: Family = Family.AUDIO, rename: String? = null, asMidi: Boolean = false): Track {
        val take = p.takes.first { it.id == takeId }
        if (asMidi && take.notes.isNotEmpty()) { val tr = p.track(toTrackId ?: take.trackId) ?: error("MIDI takes need an instrument track")
            take.notes.forEach { tr.notes.add(it.copy(start = it.start + take.start)) }; take.placed = true; if (take.id !in tr.playlist) tr.playlist.add(take.id); if (previewTakeId == takeId) previewTakeId = null; return tr }
        val track = p.track(toTrackId ?: take.trackId) ?: Track(name = rename ?: take.source, family = family, instrumentId = if (family == Family.VOCAL) "vox_mic" else "vox_mic", color = TrackColors.all[p.tracks.size % TrackColors.all.size]).also { p.tracks.add(it) }
        val clip = Clip(audioId = take.audioId, start = take.start, length = take.length, name = take.name, takeId = take.id, printed = take.printed)
        if (mode == RecordMode.PUNCH) { // punch replaces only the punched region of overlapping clips (non-destructively: originals stay in the playlist)
            val inP = take.start; val outP = take.start + take.length
            val replaced = track.clips.filter { it.start < outP && it.end > inP }
            for (c in replaced) { track.clips.remove(c)
                if (c.start < inP) track.clips.add(c.copy(newId()).also { it.length = ((inP - c.start) * c.rate).toLong() })
                if (c.end > outP) track.clips.add(c.copy(newId()).also { val cut = ((outP - c.start) * c.rate).toLong(); it.offset = c.offset + cut; it.length = c.length - cut; it.start = outP }) }
        }
        track.clips.add(clip); take.placed = true; take.trackId = track.id; if (take.id !in track.playlist) track.playlist.add(take.id)
        if (previewTakeId == takeId) previewTakeId = null
        return track
    }

    /** Discard a private take; returns the audio id that may be released. Placed takes cannot be discarded from here. */
    fun discard(p: Project, takeId: String): String? {
        val take = p.takes.firstOrNull { it.id == takeId } ?: return null
        if (take.placed) return null
        p.takes.remove(take); if (previewTakeId == takeId) previewTakeId = null
        return take.audioId
    }

    fun pending(p: Project) = p.takes.filter { !it.placed }
}

// ------------------------------------------------------------ Authorized import
object ImportPolicy {
    sealed class Decision { data class Accept(val kind: String, val note: String) : Decision(); data class Reject(val reason: String) : Decision() }
    val audioExt = setOf("wav", "mp3", "m4a", "aac", "flac", "ogg", "opus", "aif", "aiff", "3gp", "amr")
    val midiExt = setOf("mid", "midi")
    const val permissionText = "I created this file or have the rights to use it. StudioSpark cannot verify where a file came from."

    fun classify(uri: String, displayName: String?, mime: String?): Decision {
        val u = uri.lowercase().trim(); val name = (displayName ?: uri.substringAfterLast('/')).lowercase()
        val ext = name.substringAfterLast('.', "").substringBefore('?')
        if (u.startsWith("spotify:") || "spotify.com" in u) return Decision.Reject("Spotify links are not supported. Streaming services do not allow exporting their audio.")
        if (u.startsWith("magnet:") || ext == "torrent" || "torrent" in name) return Decision.Reject("Torrents and torrent-sourced files are not supported.")
        if ("youtube.com" in u || "youtu.be" in u || "soundcloud.com" in u || "music.apple.com" in u) return Decision.Reject("Streaming links are not supported. Import an audio file you own.")
        if (u.startsWith("http://") || u.startsWith("https://")) return Decision.Reject("Web links are not imported directly. Save a file you own to the device, then import it.")
        if (ext in setOf("m4p", "aa", "aax", "wma", "m4b", "drm") || (mime?.contains("drm") == true)) return Decision.Reject("Protected (DRM) downloads are not supported.")
        if (!(u.startsWith("content://") || u.startsWith("file://") || u.startsWith("/"))) return Decision.Reject("Only files chosen from this device are supported.")
        if (ext in midiExt || mime == "audio/midi" || mime == "audio/x-midi") return Decision.Accept("midi", permissionText)
        if (ext in audioExt || (ext.isEmpty() && mime?.startsWith("audio/") == true)) return Decision.Accept("audio", permissionText)
        return Decision.Reject("Unsupported file type${if (ext.isNotEmpty()) " (.$ext)" else ""}. Use WAV, MP3, M4A, AAC, FLAC, OGG, AIFF or MIDI.")
    }
}

// ------------------------------------------------------------ Virtual signal routing (jam room cords)
/** Directed signal graph. Connections that would create a feedback loop are refused. */
class SignalGraph(private val edges: MutableList<Cord> = mutableListOf()) {
    val cords: List<Cord> get() = edges
    fun reaches(from: String, to: String): Boolean { val seen = HashSet<String>(); val stack = ArrayDeque(listOf(from))
        while (stack.isNotEmpty()) { val n = stack.removeLast(); if (n == to) return true; if (seen.add(n)) edges.filter { it.from == n }.forEach { stack.addLast(it.to) } }; return false }
    fun connect(from: String, to: String): Boolean { if (from == to || reaches(to, from) || edges.any { it.from == from && it.to == to }) return false; edges.removeAll { it.from == from }; edges.add(Cord(from, to)); return true }
    fun disconnect(from: String, to: String) { edges.removeAll { it.from == from && it.to == to } }
    fun remove(node: String) { val ins = edges.filter { it.to == node }.map { it.from }; val outs = edges.filter { it.from == node }.map { it.to }; edges.removeAll { it.from == node || it.to == node }; for (i in ins) for (o in outs) connect(i, o) }
    /** Signal order starting from [source]. */
    fun chainFrom(source: String): List<String> { val out = mutableListOf(source); var cur = source; while (true) { val nxt = edges.firstOrNull { it.from == cur }?.to ?: break; if (nxt in out) break; out.add(nxt); cur = nxt }; return out }
}
