package com.studiospark.app.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.studiospark.app.songwriter.*

/**
 * Songwriter tab: its own blank workspace, driven by [SongwriterViewModel] (state) over the tab's SongwriterSession.
 * Both are created only when this tab is first opened. Audio (play, mic listen) runs on StudioVM's shared engine; the
 * Studio project is reached only through "Send to Studio" (StudioVM.songwriterToStudio → shared SessionHost handoff).
 */
@Composable fun SongwriterScreen(vm: StudioVM) {
    val model: SongwriterViewModel = viewModel(factory = SongwriterViewModel.factory(vm.songwriter()) { vm.songwriterToStudio() })
    val st by model.state.collectAsState()
    CompositionLocalProvider(LocalPalette provides ImportPalette) {
        val p = LocalPalette.current
        Column(Modifier.fillMaxSize().background(p.window).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Panel(Modifier.fillMaxWidth(), pad = 16.dp) {
                SectionLabel("Songwriter · key") { Text(st.key?.let { "${Notes.name(it.root, it.flats)} ${it.mode.label} locked" } ?: "pick a root to lock the key", style = Type.label, color = p.accent) }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (0 until 12).forEach { r -> val sel = st.keyRoot == r
                        Box(Modifier.size(40.dp).clip(CircleShape).background(if (sel) p.accent else p.raised).clickable { model.lockRoot(r) }.semantics { contentDescription = "Root ${Notes.name(r)}" }, contentAlignment = Alignment.Center) {
                            Text(Notes.name(r, st.key?.flats == true), style = Type.bodyStrong, color = if (sel) p.window else p.text) } }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    Mode.values().forEach { m -> ToolButton(m.label, active = st.mode == m) { model.setMode(m) } } }
            }
            val k = st.key
            Panel(Modifier.fillMaxWidth()) {
                SectionLabel(if (k == null) "Fretboard" else "Fretboard · notes outside ${Notes.name(k.root, k.flats)} ${k.mode.label} dimmed") {
                    if (st.melody.isNotEmpty()) ToolButton("Clear melody") { model.clearMelody() } }
                Text(if (k == null) "Blank. Lock a key to see the scale; tap strings to enter a melody." else "Green dots: ${st.focusChord?.name(k.flats) ?: "chord"} fingering. Tap a string/fret to enter melody notes (one beat each).",
                    style = Type.small, color = p.textFaint)
                Fretboard(Modifier.padding(top = 6.dp), key = k, fingering = st.fingering, capo = st.capo?.fret ?: 0, taps = st.taps) { string, fret -> model.tapFret(string, fret) }
                if (st.voicings.size > 1) ToolButton("Next voicing (${st.voicingIndex + 1}/${st.voicings.size})", modifier = Modifier.padding(top = 6.dp)) { model.nextVoicing() }
                if (st.melodyChords.isNotEmpty() && k != null) Text("Fits: " + st.melodyChords.joinToString(" | ") { it.name(k.flats) }, style = Type.bodyStrong, color = p.accent)
            }
            if (k == null) EmptyState("Blank workspace", "Lock a key with the root picker. Suggestions, fingerings and the fretboard all follow it.")
            else {
                Panel(Modifier.fillMaxWidth()) {
                    SectionLabel("Progression") { Text("${st.progression.size} bars", style = Type.label, color = p.textFaint) }
                    if (st.progression.isEmpty()) Text("Tap a suggestion to start.", style = Type.small, color = p.textDim)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        st.lyrics.forEach { line -> Column(Modifier.width(120.dp).clip(RoundedCornerShape(4.dp)).background(if (line.playing) p.accentDim else p.raised).clickable { model.focusChord(line.chord) }.padding(6.dp)) {
                            Text("${line.bar + 1}. ${line.chord.name(k.flats)}  ${k.roman(line.chord)}", style = Type.bodyStrong, color = p.text)
                            BasicTextField(line.text, { model.setLyric(line.bar, it) }, textStyle = Type.small.copy(color = p.text), cursorBrush = SolidColor(p.accent),
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp).background(p.inset).padding(4.dp).semantics { contentDescription = "Lyric bar ${line.bar + 1}" },
                                decorationBox = { inner -> if (line.text.isEmpty()) Text("lyric…", style = Type.small, color = p.textFaint); inner() })
                        } }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        ToolButton("Undo chord", icon = Icons.Default.Undo, enabled = st.progression.isNotEmpty()) { model.undoChord() }
                        Dropdown("", "Groove: ${st.style.label}", Accompaniment.Style.values().toList(), { it.label }) { model.setStyle(it) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        PrimaryButton("Play with drums, bass + guitar", icon = Icons.Default.PlayArrow, enabled = st.progression.isNotEmpty()) { model.generate(); vm.songwriterPlay { model.setPlayingBar(it) } }
                        ToolButton("Stop", icon = Icons.Default.Stop) { vm.songwriterStop { model.setPlayingBar(it) } }
                    }
                    st.parts?.let { pt -> Text("Generated: ${pt.drums.size} drum hits · ${pt.bass.size} bass notes · ${pt.guitar.size} guitar notes (rule-based)", style = Type.small, color = p.textFaint, modifier = Modifier.padding(top = 6.dp)) }
                    ToolButton("Send to Studio as new tracks", icon = Icons.Default.Input, enabled = st.progression.isNotEmpty(), modifier = Modifier.padding(top = 8.dp)) { model.sendToStudio() }
                    st.capo?.let { cp -> Text("Capo tip: capo ${cp.fret}, then play ${cp.shapes.joinToString(" ") { it.name() }} shapes — no barre chords.", style = Type.small, color = p.accent, modifier = Modifier.padding(top = 8.dp)) }
                }
                Panel(Modifier.fillMaxWidth()) {
                    SectionLabel("Next chord · in ${Notes.name(k.root, k.flats)} ${k.mode.label}") { Text("common moves (hand-written table) + your session", style = Type.label, color = p.textFaint) }
                    st.suggestions.take(5).forEach { sg -> SuggestionRow(sg, k) { model.pickSuggestion(sg) } }
                }
                Panel(Modifier.fillMaxWidth()) {
                    SectionLabel("Hum or play a melody") { Text(if (st.listening) "listening…" else "", style = Type.label, color = p.rec) }
                    Text("Sing, hum or play one note at a time for up to 8 s; StudioSpark proposes chords for each bar.", style = Type.small, color = p.textDim)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                        PrimaryButton(if (st.listening) "Stop" else "Listen", icon = Icons.Default.Mic) { if (st.listening) vm.songwriterStopListening() else vm.songwriterListen { model.refresh() } }
                        if (st.melodyChords.isNotEmpty()) ToolButton("Use these chords") { model.useMelodyChords() }
                    }
                    if (st.melody.isNotEmpty()) Text("Heard ${st.melody.size} notes: " + st.melody.take(16).joinToString(" ") { Notes.name(it.pitch, k.flats) }, style = Type.small, color = p.textDim, modifier = Modifier.padding(top = 6.dp))
                    st.status?.let { Text(it, style = Type.small, color = p.textFaint) }
                }
            }
        }
    }
}

@Composable private fun SuggestionRow(sg: Progressions.Suggestion, k: Key, onPick: () -> Unit) {
    val p = LocalPalette.current; val f = ChordDictionary.best(sg.chord)
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(4.dp)).background(p.raised).clickable(onClick = onPick).padding(8.dp).semantics { contentDescription = "Suggestion ${sg.chord.name()}" }, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("${sg.chord.name(k.flats)}  ·  ${sg.roman}", style = Type.title, color = p.text)
            f?.let { Text(it.shape + (it.barre?.let { b -> " @ fret $b" } ?: "") + "  ·  muted: " + (it.muted.joinToString(",") { s -> "EADGBe"[s].toString() }.ifEmpty { "none" }), style = Type.small, color = p.textDim) }
            if (f?.awkward == true) ChordDictionary.capoFor(listOf(sg.chord))?.let { Text("Easier: capo ${it.fret} + ${it.shapes[0].name()} shape", style = Type.small, color = p.accent) }
        }
        f?.let { ChordBox(it) }
    }
}

/** Chord box: 6 strings, 5 frets from the voicing's lowest fret; x = muted, o = open, dot number = finger. */
@Composable fun ChordBox(f: Fingering, modifier: Modifier = Modifier) {
    val p = LocalPalette.current; val base = if (f.maxFret <= 4) 1 else f.minFret
    Column(modifier.width(96.dp)) {
        Row { (0..5).forEach { s -> Text(when { f.frets[s] < 0 -> "x"; f.frets[s] == 0 -> "o"; else -> " " }, style = Type.mono, color = p.textDim, modifier = Modifier.width(16.dp)) } }
        Canvas(Modifier.size(96.dp, 80.dp)) {
            val sw = size.width / 6; val fh = size.height / 5
            for (s in 0..5) drawLine(Color(0xFFB4BFB9), Offset(sw * s + sw / 2, 0f), Offset(sw * s + sw / 2, size.height), 1.5f)
            for (r in 0..5) drawLine(Color(0xFFB4BFB9), Offset(sw / 2, fh * r), Offset(sw * 5.5f, fh * r), if (r == 0 && base == 1) 5f else 1.5f)
            for (s in 0..5) { val fr = f.frets[s]; if (fr > 0) { val row = fr - base; if (row in 0..4) drawCircle(Color(0xFF3DDC97), minOf(sw, fh) * 0.36f, Offset(sw * s + sw / 2, fh * row + fh / 2)) } }
        }
        Row { (0..5).forEach { s -> Text(if (f.fingers[s] > 0) "${f.fingers[s]}" else " ", style = Type.mono, color = p.accent, modifier = Modifier.width(16.dp)) } }
        if (base > 1) Text("fret $base", style = Type.mono, color = p.textFaint)
    }
}
