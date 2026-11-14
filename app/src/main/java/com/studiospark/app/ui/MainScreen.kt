package com.studiospark.app.ui

import android.content.res.Configuration
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.studiospark.app.core.*
import kotlinx.coroutines.delay

@Composable fun MainScreen(vm: StudioVM) {
    val p = LocalPalette.current
    if (!vm.ready) { LoadingScreen(); return }
    @Suppress("UNUSED_VARIABLE") val r = vm.rev
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    Box(Modifier.fillMaxSize().background(p.window)) {
        Column(Modifier.fillMaxSize()) {
            // Landscape: project actions fold into the single-row transport and the tabs get shorter, leaving height for content.
            if (!landscape) ProjectHeader(vm, landscape)
            TransportBar(vm, landscape)
            if (vm.bannerVisible()) BirthdayBanner(vm)
            ScreenTabs(vm, landscape)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (vm.screen) {
                    Screen.STUDIO -> StudioScreen(vm, landscape)
                    Screen.DRUMS -> DrumKitScreen(vm, landscape)
                    Screen.GUITAR -> InstrumentBuilderScreen(vm, landscape, bass = false)
                    Screen.BASS -> InstrumentBuilderScreen(vm, landscape, bass = true)
                    Screen.STEMS -> StemsScreen(vm)
                    Screen.RECORD -> RecordScreen(vm)
                    Screen.JAM -> JamRoom(vm, landscape)
                }
            }
        }
        vm.upload?.let { UploadFlowScreen(vm, it) }
        vm.workbench?.let { WorkbenchScreen(vm, it) }
        if (vm.settingsOpen) SettingsDialog(vm)
        if (vm.galleryOpen) InstrumentGallery(vm)
        vm.micGate?.let { act -> MicConfirmDialog(onYes = { vm.micGate = null; act() }, onNo = { vm.micGate = null }) }
        PendingTakesTray(vm, Modifier.align(Alignment.BottomCenter))
        vm.micError?.let { err ->
            Box(Modifier.align(Alignment.TopCenter).padding(top = 56.dp, start = 16.dp, end = 16.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xF0B3261E)).padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(err, style = Type.body, color = Color.White) } }
        vm.message?.let { msg -> LaunchedEffect(msg) { delay(3200); if (vm.message == msg) vm.message = null }
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp, start = 24.dp, end = 24.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xF0202326)).border(1.dp, p.line, RoundedCornerShape(6.dp)).clickable { vm.message = null }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                Text(msg, style = Type.body, color = Color.White) } }
    }
}

@Composable private fun ProjectHeader(vm: StudioVM, landscape: Boolean) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().background(p.header).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Photo("brand_sparknote", Modifier.size(26.dp).clip(RoundedCornerShape(5.dp)))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("StudioSpark", style = Type.label, color = p.accent)
            Text(vm.project.name, style = Type.bodyStrong, color = p.text, maxLines = 1)
        }
        IconBtn(Icons.Default.Undo, "Undo", vm.canUndo) { vm.undo() }
        IconBtn(Icons.Default.Redo, "Redo", vm.canRedo) { vm.redo() }
        IconBtn(if (vm.chromeHidden) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff, if (vm.chromeHidden) "Show controls" else "Hide controls") { vm.setHidden(!vm.chromeHidden) }
        IconBtn(Icons.Outlined.Settings, "Settings") { vm.settingsOpen = true }
    }
    Divider(color = p.inset, thickness = 1.dp)
}

@Composable fun IconBtn(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, enabled: Boolean = true, tint: Color? = null, onClick: () -> Unit) {
    val p = LocalPalette.current
    Box(Modifier.size(40.dp).clip(CircleShape).clickable(enabled = enabled, onClick = onClick).semantics { contentDescription = desc }, contentAlignment = Alignment.Center) {
        Icon(icon, desc, tint = if (!enabled) p.textFaint.copy(alpha = 0.5f) else tint ?: p.textDim, modifier = Modifier.size(20.dp)) }
}

/** Pro Tools-style transport and counter. Always visible (tracking is never hidden). */
@Composable fun TransportBar(vm: StudioVM, landscape: Boolean) {
    val p = LocalPalette.current; val ses = vm.mixer.session
    val recording = vm.transport == TransportState.RECORDING || vm.transport == TransportState.COUNT_IN
    if (landscape) { CompactTransport(vm); return }
    Column(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(p.panel, p.window))).padding(horizontal = 10.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TransportKey(Icons.Default.SkipPrevious, "Return to zero") { vm.rtz() }
            TransportKey(Icons.Default.FastRewind, "Rewind") { vm.rewind() }
            TransportKey(Icons.Default.Stop, "Stop", active = vm.transport == TransportState.STOPPED) { vm.stop() }
            TransportKey(Icons.Default.PlayArrow, "Play", active = vm.transport == TransportState.PLAYING, activeColor = p.play) { vm.play() }
            TransportKey(Icons.Default.FiberManualRecord, if (recording) "Stop recording" else "Record", active = recording, activeColor = p.rec, iconTint = p.rec) { vm.record() }
            TransportKey(Icons.Default.FastForward, "Fast forward") { vm.forward() }
            Spacer(Modifier.width(4.dp))
            Column(Modifier.clip(RoundedCornerShape(4.dp)).background(p.inset).border(1.dp, p.lineSoft, RoundedCornerShape(4.dp)).padding(horizontal = 10.dp, vertical = 3.dp).weight(1f)) {
                Text(vm.project.barsBeats(vm.position).trim(), style = Type.counter.copy(fontSize = if (landscape) 20.sp else 15.sp), color = if (recording) p.rec else p.text, maxLines = 1)
                Row { Text(String.format("%.0f BPM · %d/%d", vm.project.tempo, vm.project.sigNum, vm.project.sigDen), style = Type.mono, color = p.textFaint)
                    if (vm.transport == TransportState.COUNT_IN) Text("  COUNT-IN", style = Type.mono, color = p.solo)
                    if (vm.transport == TransportState.RECORDING) Text("  ● REC ${vm.recElapsed / 60000}:${String.format("%02d", vm.recElapsed / 1000 % 60)}", style = Type.mono, color = p.rec) }
            }
        }
        if (!vm.chromeHidden) {
            Spacer(Modifier.height(8.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ToolButton("Loop", icon = Icons.Default.Repeat, active = ses.loopPlayback) { vm.setLoop(!ses.loopPlayback) }
                Dropdown("", "Rec: ${ses.mode.label}", RecordMode.values().toList(), { it.label }) { vm.setMode(it) }
                ToolButton("Click", icon = Icons.Outlined.Timer, active = ses.metronome) { vm.setMetronome(!ses.metronome) }
                Dropdown("", if (ses.countInBars == 0) "No count-in" else "Count-in ${ses.countInBars}", listOf(0, 1, 2), { if (it == 0) "No count-in" else "Count-in $it bar${if (it > 1) "s" else ""}" }) { vm.setCountIn(it) }
                TempoControl(vm)
                Dropdown("", "${vm.project.sigNum}/${vm.project.sigDen}", listOf(4 to 4, 3 to 4, 6 to 8, 5 to 4, 7 to 8, 12 to 8, 2 to 4), { "${it.first}/${it.second}" }) { vm.setSig(it.first, it.second) }
                MasterMeters(vm)
            }
        }
    }
    Divider(color = p.inset)
}

/** Landscape: one scrollable row with transport, counter, session controls and the project actions (undo, redo, hide, settings). */
@Composable private fun CompactTransport(vm: StudioVM) {
    val p = LocalPalette.current; val ses = vm.mixer.session
    val recording = vm.transport == TransportState.RECORDING || vm.transport == TransportState.COUNT_IN
    Row(Modifier.fillMaxWidth().background(p.panel).horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TransportKey(Icons.Default.SkipPrevious, "Return to zero") { vm.rtz() }
        TransportKey(Icons.Default.Stop, "Stop", active = vm.transport == TransportState.STOPPED) { vm.stop() }
        TransportKey(Icons.Default.PlayArrow, "Play", active = vm.transport == TransportState.PLAYING, activeColor = p.play) { vm.play() }
        TransportKey(Icons.Default.FiberManualRecord, if (recording) "Stop recording" else "Record", active = recording, activeColor = p.rec, iconTint = p.rec) { vm.record() }
        Column(Modifier.clip(RoundedCornerShape(4.dp)).background(p.inset).padding(horizontal = 8.dp, vertical = 2.dp).width(132.dp)) {
            Text(vm.project.barsBeats(vm.position).trim(), style = Type.counter.copy(fontSize = 15.sp), color = if (recording) p.rec else p.text, maxLines = 1)
            Text(if (vm.transport == TransportState.RECORDING) "● REC ${vm.recElapsed / 60000}:${String.format("%02d", vm.recElapsed / 1000 % 60)}" else String.format("%.0f BPM · %d/%d", vm.project.tempo, vm.project.sigNum, vm.project.sigDen), style = Type.mono, color = if (recording) p.rec else p.textFaint, maxLines = 1) }
        if (!vm.chromeHidden) {
            ToolButton("Loop", icon = Icons.Default.Repeat, active = ses.loopPlayback) { vm.setLoop(!ses.loopPlayback) }
            Dropdown("", "Rec: ${ses.mode.label}", RecordMode.values().toList(), { it.label }) { vm.setMode(it) }
            ToolButton("Click", icon = Icons.Outlined.Timer, active = ses.metronome) { vm.setMetronome(!ses.metronome) }
            TempoControl(vm)
            Dropdown("", "${vm.project.sigNum}/${vm.project.sigDen}", listOf(4 to 4, 3 to 4, 6 to 8, 5 to 4, 7 to 8, 12 to 8, 2 to 4), { "${it.first}/${it.second}" }) { vm.setSig(it.first, it.second) }
        }
        IconBtn(Icons.Default.Undo, "Undo", vm.canUndo) { vm.undo() }
        IconBtn(Icons.Default.Redo, "Redo", vm.canRedo) { vm.redo() }
        IconBtn(if (vm.chromeHidden) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff, if (vm.chromeHidden) "Show controls" else "Hide controls") { vm.setHidden(!vm.chromeHidden) }
        IconBtn(Icons.Outlined.Settings, "Settings") { vm.settingsOpen = true }
    }
    Divider(color = p.inset)
}

@Composable private fun TempoControl(vm: StudioVM) {
    val p = LocalPalette.current
    Row(Modifier.height(32.dp).clip(RoundedCornerShape(4.dp)).background(p.raised).border(1.dp, p.line, RoundedCornerShape(4.dp)), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(32.dp).clickable { vm.setTempo(vm.project.tempo - 1) }, contentAlignment = Alignment.Center) { Text("−", color = p.text) }
        Text(String.format("%.0f", vm.project.tempo), style = Type.mono.copy(fontSize = 13.sp), color = p.text, modifier = Modifier.width(34.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Box(Modifier.size(32.dp).clickable { vm.setTempo(vm.project.tempo + 1) }, contentAlignment = Alignment.Center) { Text("+", color = p.text) }
    }
}

@Composable private fun MasterMeters(vm: StudioVM) {
    val p = LocalPalette.current
    Row(Modifier.height(32.dp).clip(RoundedCornerShape(4.dp)).background(p.inset).padding(horizontal = 6.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("OUT", style = Type.label, color = p.textFaint); Spacer(Modifier.width(6.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) { Meter(vm.masterL, Modifier.width(90.dp).height(8.dp), vertical = false); Meter(vm.masterR, Modifier.width(90.dp).height(8.dp), vertical = false) }
        Spacer(Modifier.width(8.dp)); Text("DSP ${(vm.cpu * 100).toInt()}%", style = Type.mono, color = if (vm.cpu > 0.8f) p.rec else p.textFaint)
    }
}

@Composable private fun TransportKey(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, active: Boolean = false, activeColor: Color? = null, iconTint: Color? = null, onClick: () -> Unit) {
    val p = LocalPalette.current; val ac = activeColor ?: p.textDim
    Box(Modifier.size(width = 40.dp, height = 40.dp).clip(RoundedCornerShape(5.dp))
        .background(Brush.verticalGradient(if (active) listOf(ac.copy(alpha = 0.35f), ac.copy(alpha = 0.18f)) else listOf(p.raised, p.header)))
        .border(1.dp, if (active) ac else p.line, RoundedCornerShape(5.dp)).semantics { contentDescription = desc }.clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, desc, tint = iconTint ?: if (active) ac else p.text, modifier = Modifier.size(22.dp)) }
}

@Composable private fun ScreenTabs(vm: StudioVM, landscape: Boolean = false) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().background(p.panel).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState())) { Screen.values().forEach { s -> val sel = vm.screen == s
            Column(Modifier.clickable { vm.screen = s; if (s == Screen.JAM) vm.ensureRoom() }.semantics { contentDescription = "${s.label} tab" }.padding(horizontal = 12.dp, vertical = if (landscape) 5.dp else 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(s.label, style = Type.bodyStrong, color = if (sel) p.text else p.textFaint, maxLines = 1)
                Spacer(Modifier.height(if (landscape) 3.dp else 6.dp)); Box(Modifier.height(2.dp).width(28.dp).background(if (sel) p.accent else Color.Transparent))
            } } }
        Spacer(Modifier.width(6.dp))
        ToolButton("Instruments", icon = Icons.Default.Add) { vm.galleryOpen = true }
    }
    Divider(color = p.inset)
}

@Composable private fun BirthdayBanner(vm: StudioVM) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().background(Brush.horizontalGradient(listOf(p.accent.copy(alpha = 0.25f), p.panel))).padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("🎂  Happy birthday! Make something loud today.", style = Type.body, color = p.text, modifier = Modifier.weight(1f))
        Text("Dismiss", style = Type.small, color = p.accent, modifier = Modifier.clickable { vm.dismissBanner() }.padding(6.dp))
    }
}

@Composable private fun MicConfirmDialog(onYes: () -> Unit, onNo: () -> Unit) {
    val p = LocalPalette.current
    AlertDialog(onDismissRequest = onNo, containerColor = p.panel, title = { Text("Use the microphone?", style = Type.title, color = p.text) },
        text = { Text("StudioSpark asks every time the mic is turned on. Audio stays on this device until you place or export it.", style = Type.body, color = p.textDim) },
        confirmButton = { PrimaryButton("Allow this time", onClick = onYes) }, dismissButton = { ToolButton("Not now", onClick = onNo) })
}

/** Listen → Place / Discard tray that appears after any recording pass (studio, record, jam room). */
@Composable private fun PendingTakesTray(vm: StudioVM, modifier: Modifier) {
    val p = LocalPalette.current; val pending = vm.mixer.session.pending(vm.project)
    if (pending.isEmpty() || vm.screen == Screen.RECORD || vm.upload != null) return
    var expanded by remember { mutableStateOf(true) }
    Column(modifier.fillMaxWidth().padding(10.dp).clip(RoundedCornerShape(8.dp)).background(p.raised).border(1.dp, p.accent.copy(alpha = 0.5f), RoundedCornerShape(8.dp)).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Text("${pending.size} PRIVATE TAKE${if (pending.size > 1) "S" else ""} — NOT ON THE TIMELINE YET", style = Type.label, color = p.accent, modifier = Modifier.weight(1f))
            Text(if (expanded) "Hide" else "Show", style = Type.small, color = p.textDim, modifier = Modifier.clickable { expanded = !expanded }.padding(4.dp)) }
        if (expanded) pending.takeLast(3).forEach { t -> TakeRow(vm, t) }
    }
}

@Composable fun TakeRow(vm: StudioVM, t: Take) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Column(Modifier.weight(1f)) { Text(t.name, style = Type.bodyStrong, color = p.text, maxLines = 1); Text("${t.source} · ${String.format("%.1f", t.length / ENGINE_SR.toFloat())} s${if (t.notes.isNotEmpty()) " · ${t.notes.size} notes" else ""}${if (t.savedToLibrary) " · in library" else ""}", style = Type.small, color = p.textFaint) }
        ToolButton(if (vm.previewingTakeId == t.id) "Stop" else "Listen", icon = if (vm.previewingTakeId == t.id) Icons.Default.Stop else Icons.Default.Headphones, active = vm.previewingTakeId == t.id) { vm.listen(t) }
        ToolButton("Place", icon = Icons.Default.Input, accent = p.play, active = true) { vm.place(t) }
        if (t.notes.isNotEmpty() && t.trackId != null) ToolButton("MIDI") { vm.place(t, asMidi = true) }
        IconBtn(Icons.Outlined.Delete, "Discard take") { vm.discard(t) }
    }
}

/** Light first frame shown while the session loads in the background. */
@Composable private fun LoadingScreen() {
    val p = LocalPalette.current
    Box(Modifier.fillMaxSize().background(p.window), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = p.accent)
            Spacer(Modifier.height(12.dp))
            Text("Loading StudioSpark…", style = Type.body, color = p.textDim)
        }
    }
}
