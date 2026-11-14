package com.studiospark.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.studiospark.app.core.*
import com.studiospark.app.dsp.Family
import com.studiospark.app.dsp.RigAvailability

/** Upload / import / place-audio flow. Step 4 (Tone) is the full AmpliTube-style rig, offered for every instrument. */
@Composable fun UploadFlowScreen(vm: StudioVM, u: UploadFlow) {
    val p = LocalPalette.current
    @Suppress("UNUSED_EXPRESSION") u.rigRev
    BackHandlerCompat { vm.closeUpload() }
    Column(Modifier.fillMaxSize().background(p.window)) {
        Row(Modifier.fillMaxWidth().background(p.header).padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBtn(Icons.Default.Close, "Close upload") { vm.closeUpload() }
            Column(Modifier.weight(1f)) { Text(if (u.origin == "rig") "TRACK TONE" else "UPLOAD & PLACE AUDIO", style = Type.label, color = p.accent); Text(u.sourceName.ifBlank { "Choose a source" }, style = Type.bodyStrong, color = p.text, maxLines = 1) }
        }
        // stepper
        Row(Modifier.fillMaxWidth().background(p.panel).horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            UploadStep.values().forEachIndexed { i, s -> val cur = u.step == s; val done = s.ordinal < u.step.ordinal
                Row(Modifier.clip(RoundedCornerShape(14.dp)).background(if (cur) p.accent.copy(alpha = 0.18f) else Color.Transparent).clickable(enabled = done) { u.step = s }.padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(18.dp).clip(RoundedCornerShape(9.dp)).background(if (cur || done) p.accent else p.raised), contentAlignment = Alignment.Center) { Text(if (done) "✓" else "${i + 1}", style = Type.label, color = if (cur || done) Color.Black else p.textDim) }
                    Spacer(Modifier.width(6.dp)); Text(s.label, style = Type.small, color = if (cur) p.text else p.textDim) }
                if (i < UploadStep.values().size - 1) Box(Modifier.width(16.dp).height(1.dp).background(p.line)) }
        }
        Divider(color = p.inset)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (u.step) {
                UploadStep.SOURCE -> SourceStep(vm, u)
                UploadStep.RIGHTS -> RightsStep(u)
                UploadStep.INSTRUMENT -> InstrumentStep(vm, u)
                UploadStep.TONE -> ToneStep(vm, u)
                UploadStep.PLACE -> PlaceStep(vm, u)
            }
            u.busy?.let { b -> Box(Modifier.fillMaxSize().background(Color(0xAA000000)), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) { CircularProgressIndicator(color = p.accent); Spacer(Modifier.height(12.dp)); Text(b, style = Type.body, color = Color.White) } } }
        }
        Divider(color = p.inset)
        Row(Modifier.fillMaxWidth().background(p.panel).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (u.step.ordinal > 0) ToolButton("Back", icon = Icons.Default.ArrowBack) { u.step = UploadStep.values()[u.step.ordinal - 1]; if (u.step != UploadStep.TONE) vm.audition(u, false) }
            Spacer(Modifier.weight(1f))
            val canNext = when (u.step) { UploadStep.SOURCE -> u.audio != null || u.midi != null; UploadStep.RIGHTS -> u.confirmed; else -> true }
            if (u.step != UploadStep.PLACE) PrimaryButton(if (u.step == UploadStep.TONE) "Continue to place" else "Next", enabled = canNext) { if (u.step == UploadStep.TONE) vm.audition(u, false); u.step = UploadStep.values()[u.step.ordinal + 1] }
            else PrimaryButton(if (u.origin == "rig" && u.audio == null) "Apply to track" else "Place on timeline", icon = Icons.Default.Check) { vm.finishUpload(u) }
        }
    }
}

@Composable fun BackHandlerCompat(onBack: () -> Unit) { androidx.activity.compose.BackHandler(onBack = onBack) }

@Composable private fun SourceStep(vm: StudioVM, u: UploadFlow) {
    val p = LocalPalette.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.uploadFromUri(u, uri) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Panel(Modifier.fillMaxWidth()) {
            SectionLabel("From this device")
            Text("Audio (WAV, MP3, M4A, AAC, FLAC, OGG, AIFF) or a MIDI file you created or have the rights to use.", style = Type.body, color = p.textDim)
            Spacer(Modifier.height(10.dp)); PrimaryButton("Choose file…", icon = Icons.Default.FileOpen) { picker.launch(arrayOf("audio/*", "audio/midi", "audio/x-midi", "application/x-midi")) }
            u.error?.let { Spacer(Modifier.height(8.dp)); Text(it, style = Type.body, color = p.rec) }
        }
        val takes = vm.project.takes
        Panel(Modifier.fillMaxWidth()) {
            SectionLabel("Recorded takes (${takes.size})")
            if (takes.isEmpty()) Text("Takes from Record, the Studio or the Jam Room appear here.", style = Type.body, color = p.textFaint)
            takes.reversed().take(12).forEach { t -> Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).clickable { vm.uploadFromTake(u, t) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.GraphicEq, null, tint = p.accent); Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) { Text(t.name, style = Type.body, color = p.text); Text("${t.source} · ${String.format("%.1f s", t.length / ENGINE_SR.toFloat())}${if (t.placed) " · placed" else " · private"}", style = Type.small, color = p.textFaint) }
                Text("Use", style = Type.small, color = p.accent) } }
        }
        if (vm.stems.stems.isNotEmpty()) Panel(Modifier.fillMaxWidth()) { SectionLabel("Separated stems"); Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { vm.stems.stems.keys.forEach { n -> ToolButton(n.replaceFirstChar { it.uppercase() }) { vm.uploadFromStem(u, n) } } } }
        Panel(Modifier.fillMaxWidth()) {
            SectionLabel("Not supported")
            listOf("Spotify and other streaming links", "Torrents or torrent-sourced files", "Protected (DRM) downloads such as .m4p", "Direct web links — save a file you own first").forEach { Row(Modifier.padding(vertical = 3.dp)) { Text("✕  ", color = p.rec); Text(it, style = Type.body, color = p.textDim) } }
            Text("StudioSpark cannot detect where a file came from; you confirm your rights on the next step.", style = Type.small, color = p.textFaint, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable private fun RightsStep(u: UploadFlow) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Panel(Modifier.fillMaxWidth()) {
            SectionLabel("Confirm you can use this audio")
            Text(u.sourceName, style = Type.title, color = p.text)
            u.audio?.let { Text(String.format("%.1f s · mono 48 kHz in session", it.size / ENGINE_SR.toFloat()), style = Type.small, color = p.textFaint) }
            u.midi?.let { Text("${it.tracks.size} MIDI track(s) · ${String.format("%.0f", it.tempo)} BPM", style = Type.small, color = p.textFaint) }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).clickable { u.confirmed = !u.confirmed }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(u.confirmed, { u.confirmed = it }, colors = CheckboxDefaults.colors(checkedColor = p.accent), modifier = Modifier.semantics { contentDescription = "Confirm rights" })
                Text(ImportPolicy.permissionText, style = Type.body, color = p.text) }
        }
    }
}

@Composable private fun InstrumentStep(vm: StudioVM, u: UploadFlow) {
    val p = LocalPalette.current; val all = vm.project.allInstruments()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Panel(Modifier.fillMaxWidth()) {
            SectionLabel("Destination track")
            Dropdown("Place onto", vm.project.track(u.targetTrackId)?.name ?: "New track", listOf<Track?>(null) + vm.project.tracks, { it?.name ?: "New track" }) { t -> u.targetTrackId = t?.id; if (t != null) { vm.uploadChooseInstrument(u, vm.project.instrument(t.instrumentId)); u.rig = t.rig.copy() } }
        }
        Panel(Modifier.fillMaxWidth()) {
            SectionLabel("What instrument is it?") { Text("Rig available for all", style = Type.label, color = p.play) }
            InstrumentLibrary.categories(all).forEach { cat ->
                Text(cat, style = Type.small, color = p.textDim, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    all.filter { it.category == cat }.forEach { inst -> val sel = u.instrumentId == inst.id
                        Column(Modifier.width(96.dp).clip(RoundedCornerShape(6.dp)).background(if (sel) p.accent.copy(alpha = 0.16f) else p.raised).border(1.dp, if (sel) p.accent else p.lineSoft, RoundedCornerShape(6.dp))
                            .clickable { if (RigAvailability.available(inst.family, inst.name)) vm.uploadChooseInstrument(u, inst) }.padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Photo(inst.art, Modifier.height(56.dp)); Text(inst.name, style = Type.label.copy(letterSpacing = 0.sp), color = p.text, maxLines = 2) } } }
            }
        }
    }
}

@Composable private fun ToneStep(vm: StudioVM, u: UploadFlow) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().background(p.panel).padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (u.audio != null) { ToolButton(if (u.auditioning) "Stop audition" else "Audition through rig", icon = if (u.auditioning) Icons.Default.Stop else Icons.Default.PlayArrow, active = u.auditioning, accent = p.play) { vm.audition(u, !u.auditioning) }
                Waveform(u.audio!!, Modifier.weight(1f).height(36.dp)) }
            else Text("Play the surface below to hear the rig live on this track.", style = Type.body, color = p.textDim, modifier = Modifier.weight(1f))
            Meter(vm.meters["__preview"] ?: vm.meters[u.targetTrackId] ?: 0f, Modifier.width(8.dp).height(36.dp))
        }
        RigEditor(vm, u.rig, u.family, onStructure = { vm.uploadRigChanged(u) }, modifier = Modifier.fillMaxWidth())
        if (u.audio == null && u.targetTrackId != null) vm.project.track(u.targetTrackId)?.let { t -> Box(Modifier.padding(10.dp)) { PlaySurfaceFor(vm, t, compact = true) } }
    }
}

@Composable private fun PlaceStep(vm: StudioVM, u: UploadFlow) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Panel(Modifier.fillMaxWidth()) {
            SectionLabel("Summary")
            Text(u.sourceName, style = Type.title, color = p.text)
            Text("Instrument: ${vm.project.instrument(u.instrumentId).name}", style = Type.body, color = p.textDim)
            Text("Track: ${vm.project.track(u.targetTrackId)?.name ?: "New track"}", style = Type.body, color = p.textDim)
            Text("Rig: ${u.rig.presetName} — ${u.rig.slots.joinToString(" → ") { it.gear.name }}", style = Type.body, color = p.textDim)
        }
        if (u.audio != null) Panel(Modifier.fillMaxWidth()) {
            SectionLabel("Position")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { ToolButton("At playhead (${vm.project.barsBeats(vm.position).trim()})", active = u.placeAtPlayhead) { u.placeAtPlayhead = true }; ToolButton("Bar 1", active = !u.placeAtPlayhead) { u.placeAtPlayhead = false } }
            Spacer(Modifier.height(12.dp)); SectionLabel("Tone")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { ToolButton("Keep rig live (non-destructive)", active = !u.printTone) { u.printTone = false }; ToolButton("Print tone into audio", active = u.printTone) { u.printTone = true } }
            Text(if (u.printTone) "The processed sound is rendered into a new audio file; the original stays in the session." else "Audio stays dry; the rig processes it in real time and stays editable.", style = Type.small, color = p.textFaint, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable fun Waveform(a: ShortArray, modifier: Modifier) {
    val p = LocalPalette.current
    val peaks = remember(a) { val n = 400; FloatArray(n) { i -> var m = 0; val s = i * a.size / n; val e = minOf(a.size, (i + 1) * a.size / n); for (k in s until e step 4) m = maxOf(m, kotlin.math.abs(a[k].toInt())); m / 32768f } }
    androidx.compose.foundation.Canvas(modifier.clip(RoundedCornerShape(3.dp)).background(p.inset)) { val w = size.width / peaks.size; val mid = size.height / 2
        peaks.forEachIndexed { i, v -> drawLine(p.select, Offset(i * w, mid - v * mid), Offset(i * w, mid + v * mid), maxOf(1f, w)) } }
}
