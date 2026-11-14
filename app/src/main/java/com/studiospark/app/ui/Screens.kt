package com.studiospark.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.studiospark.app.core.*
import com.studiospark.app.dsp.*
import kotlin.math.*

private val audioMimes = arrayOf("audio/*", "audio/midi", "audio/x-midi", "application/x-midi")

// ======================================================================== RECORD
@Composable fun RecordScreen(vm: StudioVM) {
    LaunchedEffect(Unit) { vm.ensureInputs() }
    val p = LocalPalette.current; @Suppress("UNUSED_VARIABLE") val r = vm.rev
    val recording = vm.transport == TransportState.RECORDING || vm.transport == TransportState.COUNT_IN
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? -> uri?.let { vm.openUpload("import", null); vm.upload?.let { u -> vm.uploadFromUri(u, it) } } }
    // Record targets: vocal/audio mic tracks plus guitar and bass (which switch their Source to Mic when picked here)
    val targets = vm.project.tracks.filter { it.family in setOf(Family.VOCAL, Family.AUDIO, Family.GUITAR, Family.BASS) && (it.family == Family.GUITAR || it.family == Family.BASS || vm.micInputFor(it)) }
    var targetId by remember { mutableStateOf<String?>(null) }
    val micTrack = targets.firstOrNull { it.id == targetId } ?: targets.firstOrNull { it.recArm && vm.micInputFor(it) } ?: targets.firstOrNull { it.family == Family.VOCAL || it.family == Family.AUDIO } ?: targets.firstOrNull { it.useMic }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Panel(Modifier.fillMaxWidth(), pad = 18.dp) {
            SectionLabel("Input")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Dropdown("Source", vm.inputDevices.firstOrNull { it.id == vm.inputDeviceId }?.name ?: "Phone microphone", vm.inputDevices, { it.name + if (it.external) "  · interface" else "" }, Modifier.weight(1f)) { vm.pickInput(it.id) }
                IconBtn(Icons.Default.Refresh, "Rescan inputs") { vm.refreshInputs() }
            }
            if (targets.isNotEmpty()) { Spacer(Modifier.height(10.dp))
                Dropdown("Record to", micTrack?.let { "${it.name} · ${if (it.family == Family.GUITAR || it.family == Family.BASS) (if (it.useMic) "Mic" else "Instrument → Mic") else "Mic"}" } ?: "New track", targets, { "${it.name} (${it.family.label})" }, Modifier.fillMaxWidth()) { targetId = it.id } }
            Spacer(Modifier.height(14.dp))
            Meter(vm.inputLevel, Modifier.fillMaxWidth().height(10.dp), vertical = false)
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) { listOf("−60", "−36", "−18", "−6", "0 dB").forEach { Text(it, style = Type.mono, color = p.textFaint, modifier = Modifier.weight(1f)) } }
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (recording) "RECORDING" else "READY", style = Type.label, color = if (recording) p.rec else p.textDim)
                    val s = vm.recElapsed / 1000; Text(String.format("%02d:%02d.%d", s / 60, s % 60, (vm.recElapsed % 1000) / 100), style = Type.counter.copy(fontSize = 34.sp), color = p.text)
                    Text(micTrack?.let { "Records to a private take for ${it.name}${if (it.useMic && it.printTone) " · tone printed" else if (it.useMic) " · dry" else ""}" } ?: "Records to a new private take", style = Type.small, color = p.textFaint)
                }
                Box(Modifier.size(84.dp).shadow(8.dp, CircleShape).clip(CircleShape).background(Brush.radialGradient(listOf(p.raised, p.inset))).border(2.dp, if (recording) p.rec else p.line, CircleShape)
                    .clickable { micTrack?.let { t -> if ((t.family == Family.GUITAR || t.family == Family.BASS) && !t.useMic && !recording) { vm.withMic { vm.setTrackSource(t, true); vm.setTrack(t.id) { recArm = true }; vm.record() }; return@clickable }
                        if (!t.recArm) vm.setTrack(t.id) { recArm = true } }; vm.record() }.semantics { contentDescription = if (recording) "Stop recording" else "Record" }, contentAlignment = Alignment.Center) {
                    if (recording) Box(Modifier.size(26.dp).clip(RoundedCornerShape(4.dp)).background(p.text)) else Box(Modifier.size(34.dp).clip(CircleShape).background(p.rec)) }
            }
            Spacer(Modifier.height(10.dp))
            Text("The microphone is only used while you record or monitor, and StudioSpark asks each time. Nothing leaves the device.", style = Type.small, color = p.textFaint)
        }
        InstrumentRecordPanel(vm)
        Panel(Modifier.fillMaxWidth()) {
            SectionLabel("Takes") { ToolButton("Import file", icon = Icons.Default.FileOpen) { picker.launch(audioMimes) } }
            val takes = vm.project.takes.filter { !it.placed }.reversed()
            if (takes.isEmpty()) EmptyState("No takes yet", "Record a pass or import a file you own. Each pass stays private until you Place it.")
            takes.forEach { t ->
                Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(6.dp)).background(p.inset).padding(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text(t.name, style = Type.bodyStrong, color = p.text); Text("${t.source} · ${String.format("%.1f s", t.length / ENGINE_SR.toFloat())}${if (t.savedToLibrary) " · in library" else ""}", style = Type.small, color = p.textFaint) }
                        vm.pool.get(t.audioId)?.let { a -> Waveform(a, Modifier.width(110.dp).height(30.dp)) }
                    }
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ToolButton(if (vm.previewingTakeId == t.id) "Stop" else "Listen", icon = Icons.Default.Headphones, active = vm.previewingTakeId == t.id) { vm.listen(t) }
                        ToolButton("Save to library", icon = Icons.Outlined.BookmarkAdd, enabled = !t.savedToLibrary) { vm.saveToLibrary(t) }
                        ToolButton("Add to Studio", icon = Icons.Default.Input, accent = p.play, active = true) { vm.place(t) }
                        ToolButton("Tone…", icon = Icons.Default.Tune) { vm.openUpload("take", null); vm.upload?.let { u -> vm.uploadFromTake(u, t) } }
                        Spacer(Modifier.weight(1f)); IconBtn(Icons.Outlined.Delete, "Discard take") { vm.discard(t) }
                    }
                }
            }
        }
    }
}

// ======================================================================== STEMS
@Composable fun StemsScreen(vm: StudioVM) {
    val p = LocalPalette.current; val s = vm.stems
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? -> uri?.let { vm.stemsImport(it) } }
    val colors = mapOf("vocals" to Color(0xFFAD5C82), "drums" to Color(0xFFC09A3E), "bass" to Color(0xFF4F7CAC), "other" to Color(0xFF5E9A6B))
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Panel(Modifier.fillMaxWidth(), pad = 16.dp) {
            SectionLabel("Stem separation") { Text("UMX-HQ · on device", style = Type.label, color = p.textFaint) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) { Text(s.sourceName ?: "No song loaded", style = Type.title, color = p.text); Text(s.status.ifBlank { "Import a song you own or have rights to." }, style = Type.small, color = p.textDim) }
                ToolButton("Import song", icon = Icons.Default.FileOpen, enabled = !s.running) { picker.launch(arrayOf("audio/*")) }
                if (s.running && s.mix != null) ToolButton("Cancel", accent = p.rec, active = true) { s.cancel = true }
                else PrimaryButton("Separate", enabled = s.stereo44 != null, icon = Icons.Default.CallSplit) { vm.stemsSeparate() }
            }
            if (s.running && s.progress > 0f) { Spacer(Modifier.height(12.dp)); LinearProgressIndicator(progress = { s.progress }, Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)), color = p.accent, trackColor = p.inset) }
            s.error?.let { Text(it, style = Type.small, color = p.rec, modifier = Modifier.padding(top = 8.dp)) }
        }
        if (s.mix == null) EmptyState("Separate any song into four stems", "Vocals, drums, bass and other are estimated by the open UMX model running on this phone, then line up sample-accurately on the Studio timeline.")
        else Panel(Modifier.fillMaxWidth()) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (listOf("all") + StemNames).forEach { t -> ToolButton(t.replaceFirstChar { it.uppercase() }, active = s.tab == t, enabled = t == "all" || s.stems.containsKey(t)) { vm.stemsPreview(t) } }
                ToolButton("Stop preview", icon = Icons.Default.Stop) { vm.stopPreview() }
            }
            Spacer(Modifier.height(10.dp))
            LaneRow("Original mix", p.textDim, s.mix!!, null) {}
            StemNames.forEach { n -> s.stems[n]?.let { a -> LaneRow(n.replaceFirstChar { it.uppercase() }, colors[n]!!, a, n in s.selected) { s.selected = if (n in s.selected) s.selected - n else s.selected + n } } }
            if (s.stems.isNotEmpty()) { Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { PrimaryButton("Add ${s.selected.size} selected to Studio", icon = Icons.Default.Add, enabled = s.selected.isNotEmpty()) { vm.stemsAddSelected() }
                    ToolButton("Send one to Upload + Rig", icon = Icons.Default.Tune, enabled = s.selected.size == 1) { vm.openUpload("stem", null); vm.upload?.let { u -> vm.uploadFromStem(u, s.selected.first()) } } } }
        }
    }
}
private val StemNames = listOf("vocals", "drums", "bass", "other")

@Composable private fun LaneRow(name: String, color: Color, a: ShortArray, selected: Boolean?, onToggle: () -> Unit) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).height(52.dp).clip(RoundedCornerShape(4.dp)).background(p.inset), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(color))
        if (selected != null) Checkbox(selected, { onToggle() }, colors = CheckboxDefaults.colors(checkedColor = p.accent)) else Spacer(Modifier.width(12.dp))
        Text(name, style = Type.bodyStrong, color = p.text, modifier = Modifier.width(96.dp))
        Box(Modifier.weight(1f).fillMaxHeight().padding(6.dp).background(color.copy(alpha = 0.12f))) { Waveform(a, Modifier.fillMaxSize()) }
    }
}

// ======================================================================== JAM ROOM
private const val WORLD_W = 1600f; private const val WORLD_H = 1000f

@Composable fun JamRoom(vm: StudioVM, landscape: Boolean) {
    LaunchedEffect(Unit) { vm.ensureRoom() }
    val p = LocalPalette.current; val proj = vm.project; @Suppress("UNUSED_VARIABLE") val r = vm.rev
    var selected by remember { mutableStateOf<String?>("amp1") }
    var cableFrom by remember { mutableStateOf<String?>(null) }; var cableMode by remember { mutableStateOf(false) }
    var addOpen by remember { mutableStateOf(false) }
    var viewSize by remember { mutableStateOf(IntSize(1, 1)) }
    val inst = proj.room.firstOrNull { it.id == vm.roomInstrument }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().background(p.header).horizontalScroll(rememberScrollState()).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            ToolButton(if (vm.roomRecording) "Stop" else "Record jam", icon = if (vm.roomRecording) Icons.Default.Stop else Icons.Default.FiberManualRecord, active = vm.roomRecording, accent = p.rec) { if (vm.roomRecording) vm.stopRoomRecording() else vm.startRoomRecording() }
            if (vm.roomRecording) Text(String.format("%d:%02d", vm.recElapsed / 60000, vm.recElapsed / 1000 % 60), style = Type.mono, color = p.rec)
            ToolButton("Click", icon = Icons.Default.Timer, active = vm.metronomeFree) { vm.setFreeMetronome(!vm.metronomeFree) }
            ToolButton(if (vm.jamBandOn) "Stop band" else "Play band", icon = if (vm.jamBandOn) Icons.Default.Stop else Icons.Default.PlayArrow, active = vm.jamBandOn) { if (vm.jamBandOn) vm.jamStop() else vm.jamPlay() }
            ToolButton("Add gear", icon = Icons.Default.Add) { addOpen = true }
            ToolButton(if (cableMode) "Tap source, then target" else "Patch cable", icon = Icons.Default.Cable, active = cableMode) { cableMode = !cableMode; cableFrom = null }
            ToolButton("Reset view", icon = Icons.Default.CenterFocusStrong) { vm.setCamera(0f, 0f, 1f) }
            ToolButton("Save room", icon = Icons.Default.Save) { vm.roomSave() }
        }
        Box(Modifier.fillMaxWidth().weight(if (landscape) 1f else 0.9f).onSizeChanged { viewSize = it }.clipToBounds().background(Color(0xFF1B1714))
            .pointerInput(Unit) { detectTransformGestures { centroid, pan, zoom, _ -> val z = (proj.camZoom * zoom).coerceIn(0.5f, 3f)
                // zoom around centroid, pan with drag (camera only — gear drags are handled on each item)
                val nx = (proj.camX + pan.x) + (centroid.x - proj.camX) * (1 - zoom); val ny = (proj.camY + pan.y) + (centroid.y - proj.camY) * (1 - zoom); vm.setCamera(nx, ny, z) } }) {
            val base = viewSize.width / WORLD_W
            val sc = base * proj.camZoom
            fun toScreen(x: Float, y: Float) = Offset(proj.camX + x * WORLD_W * sc, proj.camY + y * WORLD_H * sc)
            Canvas(Modifier.fillMaxSize()) {
                val tl = toScreen(0f, 0f); val br = toScreen(1f, 1f); val floorY = tl.y + (br.y - tl.y) * 0.42f
                drawRect(Brush.verticalGradient(listOf(Color(0xFF2B2622), Color(0xFF3A322B)), tl.y, floorY), tl, Size(br.x - tl.x, floorY - tl.y))
                for (i in 0..14) { val x = tl.x + (br.x - tl.x) * i / 14f; drawLine(Color.Black.copy(alpha = 0.25f), Offset(x, tl.y), Offset(x, floorY), 2f) } // acoustic panels
                drawRect(Brush.verticalGradient(listOf(Color(0xFF5A3B22), Color(0xFF3B2614)), floorY, br.y), Offset(tl.x, floorY), Size(br.x - tl.x, br.y - floorY))
                for (i in 0..24) { val y = floorY + (br.y - floorY) * (i / 24f).pow(1.4f); drawLine(Color.Black.copy(alpha = 0.18f), Offset(tl.x, y), Offset(br.x, y), 1.5f) }
                drawOval(Color(0xFF6B1F1F).copy(alpha = 0.55f), Offset(tl.x + (br.x - tl.x) * 0.2f, floorY + (br.y - floorY) * 0.2f), Size((br.x - tl.x) * 0.6f, (br.y - floorY) * 0.7f)) // rug
                for (c in proj.cords) { val a = proj.room.firstOrNull { it.id == c.from } ?: continue; val b = proj.room.firstOrNull { it.id == c.to } ?: continue
                    val o1 = toScreen(a.x, a.y); val o2 = toScreen(b.x, b.y); val sag = 60f * sc / base
                    val path = Path().apply { moveTo(o1.x, o1.y); cubicTo(o1.x, o1.y + sag, o2.x, o2.y + sag, o2.x, o2.y) }
                    drawPath(path, Color(0xFF0A0A0A), style = Stroke(7f * proj.camZoom)); drawPath(path, Color(0xFF3A3A3A), style = Stroke(2f * proj.camZoom))
                    drawCircle(Color(0xFFB8B8B8), 5f * proj.camZoom, o1); drawCircle(Color(0xFFB8B8B8), 5f * proj.camZoom, o2) }
            }
            val density = LocalDensity.current
            proj.room.forEach { item ->
                val o = toScreen(item.x, item.y)
                val wPx = (when (item.kind) { "amp" -> 300f; "cab" -> 260f; "pedal" -> 110f; "kit" -> 380f; "rack" -> 300f; else -> 140f }) * sc * item.scale
                val hPx = (when (item.kind) { "amp" -> 200f; "cab" -> 260f; "pedal" -> 160f; "kit" -> 280f; "rack" -> 90f; else -> 360f }) * sc * item.scale
                val art = when (item.kind) { "instrument" -> proj.instrument(item.refId).art; "kit" -> "gear_kit_overhead"; else -> GearCatalog.find(item.refId)?.art ?: "gear_pedal" }
                val sel = selected == item.id || cableFrom == item.id
                Box(Modifier.offset { IntOffset((o.x - wPx / 2).roundToInt(), (o.y - hPx / 2).roundToInt()) }.size(with(density) { wPx.toDp() }, with(density) { hPx.toDp() })
                    .then(if (sel) Modifier.border(2.dp, if (cableFrom == item.id) p.monitor else p.accent, RoundedCornerShape(6.dp)) else Modifier)
                    .pointerInput(item.id, cableMode) { detectTapGestures { if (cableMode) { val f = cableFrom; if (f == null) cableFrom = item.id else { if (f != item.id) vm.roomConnect(f, item.id); cableFrom = null; cableMode = false } }
                        else { selected = item.id; if (item.kind == "instrument" || item.kind == "kit") { vm.roomInstrument = item.id; vm.rebuildRoom() } } } }
                    .pointerInput(item.id, sc) { detectDragGestures(onDragEnd = { vm.roomSave() }) { ch, d -> ch.consume(); vm.roomMove(item.id, item.x + d.x / (WORLD_W * sc), item.y + d.y / (WORLD_H * sc)) } }
                    .semantics { contentDescription = "${item.kind} ${item.refId}" }) {
                    Photo(art, Modifier.fillMaxSize(), desc = null)
                    if (!item.enabled) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)))
                }
            }
            Text("Pinch to zoom · drag empty space to move the camera · drag gear to arrange", style = Type.label.copy(letterSpacing = 0.sp), color = Color.White.copy(alpha = 0.6f), modifier = Modifier.align(Alignment.BottomStart).padding(8.dp))
        }
        // lower deck: selected gear controls + play surface
        Column(Modifier.fillMaxWidth().weight(if (landscape) 0.9f else 1.1f).background(p.window).verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val item = proj.room.firstOrNull { it.id == selected }
            val def = item?.let { GearCatalog.find(it.refId) }
            if (item != null && def != null) {
                if (item.values == null || item.values!!.size != def.params.size) item.values = def.defaults()
                val slot = remember(item.id, item.values) { RigSlot(0, def.id, item.values!!, item.enabled) }
                Panel(Modifier.fillMaxWidth()) {
                    SectionLabel(def.name) { Switch(item.enabled, { on -> item.enabled = on; slot.enabled = on; vm.rebuildRoom(); vm.roomSave() }, colors = SwitchDefaults.colors(checkedTrackColor = p.accent)); Spacer(Modifier.width(6.dp)); IconBtn(Icons.Outlined.Delete, "Remove from room") { vm.roomRemove(item.id); selected = null } }
                    val changed = { vm.roomSave() }
                    when (def.kind) { GearKind.AMP -> AmpView(slot, changed); GearKind.CAB -> CabView(slot, changed); GearKind.RACK -> RackView(slot, changed); else -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { PedalView(slot, changed) } }
                }
            } else if (item != null) Panel(Modifier.fillMaxWidth()) { SectionLabel(if (item.kind == "kit") "Drum kit" else proj.instrument(item.refId).name) { IconBtn(Icons.Outlined.Delete, "Remove from room") { vm.roomRemove(item.id); selected = null } }
                Text("This is the room's active instrument. Play it below; it runs through every connected pedal, amp and cab.", style = Type.small, color = p.textDim) }
            JamBandPanel(vm)
            Panel(Modifier.fillMaxWidth()) {
                SectionLabel("Play · ${inst?.let { if (it.kind == "kit") DrumKits.byId(it.refId, proj.customKits)?.name ?: "Kit" else proj.instrument(it.refId).name } ?: "No instrument"}")
                when {
                    inst == null -> EmptyState("No instrument in the room", "Add a guitar, bass, keys or a drum kit.")
                    inst.kind == "kit" -> DrumKitSurface(DrumKits.byId(inst.refId, proj.customKits) ?: DrumKits.studio, Modifier.fillMaxWidth().aspectRatio(1.6f).clip(RoundedCornerShape(6.dp)), onHit = { pc, v -> vm.noteOn(pc.midi, v, null) })
                    proj.instrument(inst.refId).family in setOf(Family.GUITAR, Family.BASS) -> { val d = proj.instrument(inst.refId); val tu = Tunings.find(d.tuning ?: "")?.midi ?: Tunings.guitar[0].midi
                        proj.build(d.baseId)?.let { b -> InstrumentStage(b, Modifier.fillMaxWidth().aspectRatio(3f).clip(RoundedCornerShape(6.dp))) { n, v, _ -> vm.noteOn(n, v, null) }; Spacer(Modifier.height(6.dp)) }
                        GuitarStrings(tu, 15, Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(4.dp)), bass = d.family == Family.BASS) { n, v -> vm.noteOn(n, v, null) } }
                    else -> PianoKeys(48, 2, Modifier.fillMaxWidth().height(150.dp), onOn = { n, v -> vm.noteOn(n, v, null) }, onOff = { vm.noteOff(it) })
                }
            }
            Spacer(Modifier.height(60.dp))
        }
    }
    if (addOpen) RoomAddDialog(vm) { addOpen = false }
}

/** Backing band: pick genre/part/pattern, play or loop it, choose which parts sound. The band plays from jam tracks; you play live on the room bus. */
@Composable private fun JamBandPanel(vm: StudioVM) {
    val p = LocalPalette.current; var open by remember { mutableStateOf(false) }
    Panel(Modifier.fillMaxWidth()) {
        SectionLabel("Backing band") { Text(vm.jamLoaded ?: "Nothing loaded", style = Type.label, color = p.textFaint, maxLines = 1) }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            ToolButton(if (vm.jamBandOn) "Stop" else "Play", icon = if (vm.jamBandOn) Icons.Default.Stop else Icons.Default.PlayArrow, active = vm.jamBandOn, modifier = Modifier.semantics { contentDescription = if (vm.jamBandOn) "Stop backing band" else "Play backing band" }) { if (vm.jamBandOn) vm.jamStop() else vm.jamPlay() }
            ToolButton("Loop", icon = Icons.Default.Repeat, active = vm.jamLoop) { vm.chooseJamLoop(!vm.jamLoop) }
            com.studiospark.app.core.JamBand.Mode.values().forEach { m -> ToolButton(m.label, active = vm.jamMode == m) { vm.chooseJamMode(m) } }
            ToolButton(if (open) "Hide patterns" else "Patterns", icon = Icons.Default.AutoAwesome, active = open) { open = !open }
        }
        if (open) { Spacer(Modifier.height(8.dp))
            PatternPicker(vm, if (vm.jamBandOn) "Swap at next bar" else "Load") { vm.jamLoad(it) } }
        Text("Pattern changes land on the next bar. The band uses the Jam Drums/Bass/Guitar tracks (paused in the Studio until you unpause them); what you play here stays on the jam bus.", style = Type.label, color = p.textFaint)
    }
}

@Composable private fun RoomAddDialog(vm: StudioVM, onDismiss: () -> Unit) {
    val p = LocalPalette.current; var tab by remember { mutableStateOf("Gear") }
    if (tab == "Gear") { var browse by remember { mutableStateOf(false) }
        Dialog(onDismiss) { Column(Modifier.clip(RoundedCornerShape(10.dp)).background(p.panel).padding(16.dp).width(340.dp)) {
            Text("Add to the room", style = Type.title, color = p.text); Spacer(Modifier.height(12.dp))
            ToolButton("Pedal, amp, cab or rack…", icon = Icons.Default.Tune, modifier = Modifier.fillMaxWidth()) { browse = true }
            Spacer(Modifier.height(8.dp)); ToolButton("Instrument or drum kit…", icon = Icons.Default.Piano, modifier = Modifier.fillMaxWidth()) { tab = "Inst" }
            if (browse) GearBrowser({ browse = false }) { g -> vm.roomAdd(when (g.kind) { GearKind.AMP -> "amp"; GearKind.CAB -> "cab"; GearKind.RACK -> "rack"; else -> "pedal" }, g.id); browse = false; onDismiss() }
        } } }
    else Dialog(onDismiss) { Column(Modifier.clip(RoundedCornerShape(10.dp)).background(p.panel).padding(16.dp).width(360.dp).heightIn(max = 560.dp)) {
        Text("Instrument for the room", style = Type.title, color = p.text); Spacer(Modifier.height(8.dp))
        LazyCol { vm.project.allInstruments().filter { it.source != SoundSource.MIC && it.source != SoundSource.VOICE }.forEach { d -> Row(Modifier.fillMaxWidth().clickable { if (d.family == Family.DRUMS) vm.roomAdd("kit", d.voice.removePrefix("kit:")) else vm.roomAdd("instrument", d.id); onDismiss() }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Photo(d.art, Modifier.size(36.dp)); Spacer(Modifier.width(10.dp)); Column { Text(d.name, style = Type.body, color = p.text); Text(d.category, style = Type.small, color = p.textFaint) } } } }
    } }
}
@Composable private fun LazyCol(content: @Composable ColumnScope.() -> Unit) = Column(Modifier.verticalScroll(rememberScrollState()), content = content)

// ======================================================================== WORKBENCH (custom guitars & kits)
@Composable fun WorkbenchScreen(vm: StudioVM, kind: String) {
    val p = LocalPalette.current
    Dialog({ vm.workbench = null }, DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(p.window)) {
            Row(Modifier.fillMaxWidth().background(p.header).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconBtn(Icons.Default.Close, "Close") { vm.workbench = null }
                Text(if (kind == "drums") "Drum station" else "Guitar workbench", style = Type.title, color = p.text, modifier = Modifier.weight(1f).padding(start = 8.dp))
            }
            if (kind == "drums") DrumStation(vm) else GuitarBench(vm)
        }
    }
}

@Composable private fun GuitarBench(vm: StudioVM) {
    val p = LocalPalette.current; val t = vm.selectedTrack(); val base = t?.let { vm.project.instrument(it.instrumentId) }?.takeIf { it.family == Family.GUITAR || it.family == Family.BASS } ?: vm.project.instrument("gtr_0")
    val bass = base.family == Family.BASS
    var name by remember { mutableStateOf(if (base.custom) base.name else "My ${base.name}") }
    var tuning by remember { mutableStateOf(Tunings.find(base.tuning ?: "") ?: if (bass) Tunings.bass[0] else Tunings.guitar[0]) }
    var strings by remember { mutableStateOf(StringSets.find(base.strings ?: "") ?: if (bass) StringSets.bass[0] else StringSets.guitar[0]) }
    var bodyArt by remember { mutableStateOf(base.art) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Panel(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) { Photo(bodyArt, Modifier.size(width = 90.dp, height = 160.dp)); Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("NAME", style = Type.label, color = p.textDim)
                    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(p.inset).padding(10.dp)) { BasicTextField(name, { name = it.take(40) }, textStyle = Type.body.copy(color = p.text), cursorBrush = SolidColor(p.accent), singleLine = true) }
                    Dropdown("Tuning", tuning.name, if (bass) Tunings.bass else Tunings.guitar, { it.name }) { tuning = it }
                    Dropdown("Strings", strings.name, if (bass) StringSets.bass else StringSets.guitar, { "${it.name} · ${it.gauges}" }) { strings = it }
                } }
        }
        Panel(Modifier.fillMaxWidth()) { SectionLabel("Body")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { InstrumentLibrary.stock.filter { it.family == base.family }.map { it.art }.distinct().forEach { a ->
                Box(Modifier.size(64.dp, 96.dp).clip(RoundedCornerShape(6.dp)).background(if (a == bodyArt) p.raised else p.inset).border(1.dp, if (a == bodyArt) p.accent else Color.Transparent, RoundedCornerShape(6.dp)).clickable { bodyArt = a }.padding(4.dp)) { Photo(a, Modifier.fillMaxSize()) } } } }
        Panel(Modifier.fillMaxWidth()) { SectionLabel("Try it")
            GuitarStrings(tuning.midi, if (bass) 12 else 15, Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(4.dp)), bass = bass) { n, v -> vm.noteOn(n, v, t?.id) }
            Text("String set shapes brightness and sustain (${(strings.brightness * 100).toInt()}% bright · ${(strings.sustain * 100).toInt()}% sustain).", style = Type.small, color = p.textFaint, modifier = Modifier.padding(top = 6.dp)) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton("Save to my instruments", icon = Icons.Default.Save) {
                val d = base.copy(id = "cust_${newId()}", name = name.ifBlank { "Custom guitar" }, custom = true, tuning = tuning.name, strings = strings.name, baseId = base.id, art = bodyArt, brightness = strings.brightness, category = "My instruments")
                vm.saveCustomInstrument(d); t?.let { vm.setInstrument(it, d) }; vm.workbench = null }
        }
    }
}

@Composable private fun DrumStation(vm: StudioVM) {
    val p = LocalPalette.current; val t = vm.selectedTrack()
    val start = DrumKits.byId(t?.kitId ?: "", vm.project.customKits) ?: DrumKits.studio
    var kit by remember { mutableStateOf(if (start.custom) start else start.copy(id = "ck_${newId()}", name = "My ${start.name}", baseId = start.id, custom = true)) }
    var sel by remember { mutableStateOf<String?>(null) }; var editing by remember { mutableStateOf(true) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Panel(Modifier.fillMaxWidth()) {
            SectionLabel(kit.name) { ToolButton(if (editing) "Arrange: on" else "Arrange: off", active = editing) { editing = !editing } }
            DrumKitSurface(kit, Modifier.fillMaxWidth().aspectRatio(1.5f).clip(RoundedCornerShape(6.dp)), editing = editing,
                onHit = { pc, v -> sel = pc.id; vm.noteOn(pc.midi, (v * kit.stick.velScale).toInt().coerceIn(1, 127), t?.id) },
                onMove = { pc, x, y -> kit = kit.copy(pieces = kit.pieces.map { if (it.id == pc.id) it.copy(x = x.coerceIn(0.05f, 0.95f), y = y.coerceIn(0.05f, 0.95f)) else it }) })
        }
        Panel(Modifier.fillMaxWidth()) {
            SectionLabel("Kit")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Dropdown("Sticks", kit.stick.label, Stick.values().toList(), { it.label }) { kit = kit.copy(stick = it) }
                Dropdown("Add piece", "Choose…", DrumSounds.types.entries.toList(), { it.value }) { e -> kit = DrumKits.addPiece(kit, e.key, e.value); sel = kit.pieces.last().id }
            }
        }
        kit.pieces.firstOrNull { it.id == sel }?.let { pc -> Panel(Modifier.fillMaxWidth()) {
            SectionLabel(pc.name) { IconBtn(Icons.Outlined.Delete, "Remove piece") { kit = kit.copy(pieces = kit.pieces.filter { it.id != pc.id }); sel = null } }
            fun upd(f: (KitPiece) -> KitPiece) { kit = kit.copy(pieces = kit.pieces.map { if (it.id == pc.id) f(it) else it }) }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Knob((pc.tune + 12) / 24, { v -> upd { it.copy(tune = v * 24 - 12) } }, "Tune", String.format("%+.1f st", pc.tune), size = 48.dp)
                Knob((pc.gainDb + 24) / 30, { v -> upd { it.copy(gainDb = v * 30 - 24) } }, "Level", String.format("%+.1f dB", pc.gainDb), size = 48.dp)
                Knob(pc.size / 0.3f, { v -> upd { it.copy(size = (v * 0.3f).coerceIn(0.06f, 0.3f)) } }, "Size", "${(pc.size * 100).toInt()}", size = 48.dp)
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Dropdown("Sound", DrumSounds.types[pc.sample] ?: pc.sample, DrumSounds.types.entries.toList(), { it.value }) { e -> upd { it.copy(sample = e.key) } }
                Dropdown("Choke group", if (pc.chokeGroup == 0) "None" else "Group ${pc.chokeGroup}", (0..8).toList(), { if (it == 0) "None" else "Group $it" }) { g -> upd { it.copy(chokeGroup = g) } }
            }
        } }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f).clip(RoundedCornerShape(4.dp)).background(p.inset).padding(10.dp)) { BasicTextField(kit.name, { kit = kit.copy(name = it.take(40)) }, textStyle = Type.body.copy(color = p.text), cursorBrush = SolidColor(p.accent), singleLine = true) }
            PrimaryButton("Save kit", icon = Icons.Default.Save) { vm.saveCustomKit(kit); t?.let { tr -> vm.setTrack(tr.id) { kitId = kit.id } }; vm.workbench = null }
        }
    }
}

// ======================================================================== SETTINGS
@Composable fun SettingsDialog(vm: StudioVM) {
    val p = LocalPalette.current
    Dialog({ vm.settingsOpen = false }) { Column(Modifier.clip(RoundedCornerShape(10.dp)).background(p.panel).border(1.dp, p.line, RoundedCornerShape(10.dp)).padding(18.dp).widthIn(min = 320.dp, max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Settings", style = Type.title, color = p.text)
        SettingRow("Gold theme", "Warm gold studio finish, remembered between sessions") { Switch(vm.gold, { vm.setGoldTheme(it) }, colors = SwitchDefaults.colors(checkedTrackColor = p.accent)) }
        SettingRow("MIDI keyboards", "Listen for USB / Bluetooth MIDI controllers (off until you turn it on)") { Switch(vm.midiInput, { vm.setMidiInputEnabled(it) }, colors = SwitchDefaults.colors(checkedTrackColor = p.accent)) }
        SettingRow("Hide optional panels", "Transport and tracking always stay visible") { Switch(vm.chromeHidden, { vm.setHidden(it) }, colors = SwitchDefaults.colors(checkedTrackColor = p.accent)) }
        Divider(color = p.lineSoft)
        Text("SESSION", style = Type.label, color = p.textDim)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(8, 16).forEach { n -> ToolButton("$n-track", active = vm.project.trackCount == n) { vm.applyTrackCount(n) } }
            Dropdown("", "Meter ${vm.project.sigNum}/${vm.project.sigDen}", listOf(4 to 4, 3 to 4, 6 to 8, 5 to 4, 7 to 8), { "${it.first}/${it.second}" }) { vm.setSig(it.first, it.second) } }
        Divider(color = p.lineSoft)
        Text("LATENCY", style = Type.label, color = p.textDim)
        val est = vm.estimatedLatencyMs(); val cur = if (vm.latencyMs >= 0) vm.latencyMs else est
        Text("Measured device path ≈ ${String.format("%.1f", est)} ms. Recorded takes are shifted earlier by the compensation below.", style = Type.small, color = p.textFaint)
        Row(verticalAlignment = Alignment.CenterVertically) { Slider(cur.coerceIn(0f, 300f), { vm.setLatency(it) }, valueRange = 0f..300f, modifier = Modifier.weight(1f), colors = SliderDefaults.colors(thumbColor = p.accent, activeTrackColor = p.accent))
            Text(String.format("%.0f ms", cur), style = Type.mono, color = p.text, modifier = Modifier.width(56.dp)) }
        Divider(color = p.lineSoft)
        Text("BIRTHDAY BANNER", style = Type.label, color = p.textDim)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val months = listOf("Off", "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
            Dropdown("Month", months[vm.birthdayMonth], (0..12).toList(), { months[it] }) { vm.setBirthday(it, if (it == 0) 0 else vm.birthdayDay.coerceAtLeast(1)) }
            if (vm.birthdayMonth > 0) Dropdown("Day", "${vm.birthdayDay}", (1..31).toList(), { "$it" }) { vm.setBirthday(vm.birthdayMonth, it) }
        }
        Divider(color = p.lineSoft)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ToolButton("Bounce mix", icon = Icons.Default.SaveAlt) { vm.exportMix() }; ToolButton("Export MIDI", icon = Icons.Default.Piano) { vm.exportMidi() }
            ToolButton("Write AI vocal", icon = Icons.Default.AutoAwesome) { vm.aiVocalTrack("tonight", "Pop"); vm.settingsOpen = false }
        }
        var confirm by remember { mutableStateOf(false) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { ToolButton(if (confirm) "Tap again to start new" else "New session", icon = Icons.Default.NoteAdd, accent = if (confirm) p.rec else null, active = confirm) { if (confirm) { vm.newSession(); vm.settingsOpen = false } else confirm = true }
            Spacer(Modifier.weight(1f)); PrimaryButton("Done") { vm.settingsOpen = false } }
        Text("StudioSpark ${com.studiospark.app.BuildConfig.VERSION_NAME} · engine ${ENGINE_SR / 1000} kHz", style = Type.mono, color = p.textFaint)
    } }
}
@Composable private fun SettingRow(title: String, sub: String, trailing: @Composable () -> Unit) {
    val p = LocalPalette.current
    Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(title, style = Type.bodyStrong, color = p.text); Text(sub, style = Type.small, color = p.textFaint) }; trailing() }
}

// ======================================================================== INSTRUMENT GALLERY
@Composable fun InstrumentGallery(vm: StudioVM) {
    val p = LocalPalette.current; val all = vm.project.allInstruments(); val gearCats = listOf("Amps — heads, combos & stacks", "Cabinets", "Pedals", "Rack")
    val cats = InstrumentLibrary.categories(all) + gearCats; var cat by remember { mutableStateOf(cats.first()) }
    Dialog({ vm.galleryOpen = false }, DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.86f).clip(RoundedCornerShape(10.dp)).background(p.panel).border(1.dp, p.line, RoundedCornerShape(10.dp))) {
            Row(Modifier.fillMaxWidth().background(p.header).padding(12.dp), verticalAlignment = Alignment.CenterVertically) { Text("Instruments", style = Type.title, color = p.text, modifier = Modifier.weight(1f)); IconBtn(Icons.Default.Close, "Close") { vm.galleryOpen = false } }
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) { cats.forEach { c -> ToolButton(c, active = c == cat) { cat = c } } }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
                if (cat in gearCats) { val kind = when (cat) { gearCats[0] -> GearKind.AMP; gearCats[1] -> GearKind.CAB; gearCats[2] -> GearKind.STOMP; else -> GearKind.RACK }
                    GearCatalog.all.filter { it.kind == kind }.forEach { g -> Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(6.dp)).background(p.inset).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Photo(g.art, Modifier.size(48.dp)); Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) { Text(g.name, style = Type.bodyStrong, color = p.text); Text(listOf(g.category, g.era, g.blurb).filter { it.isNotBlank() }.joinToString(" · "), style = Type.small, color = p.textFaint, maxLines = 2) }
                        vm.selectedTrack()?.let { t -> ToolButton("Track rig") { vm.setTrack(t.id) { rig.add(g.id) }; vm.galleryOpen = false; vm.openUpload("rig", vm.selectedTrack()) }; Spacer(Modifier.width(6.dp)) }
                        ToolButton("Room", icon = Icons.Default.Add) { vm.roomAdd(when (kind) { GearKind.AMP -> "amp"; GearKind.CAB -> "cab"; GearKind.RACK -> "rack"; else -> "pedal" }, g.id); vm.galleryOpen = false; vm.screen = Screen.JAM }
                    } } }
                all.filter { it.category == cat }.forEach { d -> Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(6.dp)).background(p.inset).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Photo(d.art, Modifier.size(if (d.art.startsWith("build:")) 96.dp else 48.dp, 48.dp)); Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) { Text(d.name, style = Type.bodyStrong, color = p.text); Text(d.source.label, style = Type.small, color = p.textFaint, maxLines = 2) }
                    if (d.custom && (d.art.startsWith("build:") || d.art.startsWith("kit:"))) { ToolButton("Edit") { vm.editInstrument(d) }; Spacer(Modifier.width(6.dp)) }
                    ToolButton("Track", icon = Icons.Default.Add) { vm.addTrack(d); vm.galleryOpen = false; vm.screen = Screen.STUDIO }
                    Spacer(Modifier.width(6.dp))
                    if (d.source != SoundSource.MIC && d.source != SoundSource.VOICE) ToolButton("Room") { if (d.family == Family.DRUMS) vm.roomAdd("kit", d.voice.removePrefix("kit:")) else vm.roomAdd("instrument", d.id); vm.galleryOpen = false; vm.screen = Screen.JAM }
                } }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}


/** Record any instrument — stock or your saved kits, guitars and basses — straight from the Record tab. Takes stay private until placed. */
@Composable private fun InstrumentRecordPanel(vm: StudioVM) {
    val p = LocalPalette.current
    val playable = vm.project.allInstruments().filter { it.source != SoundSource.MIC && it.source != SoundSource.VOICE }
    var pick by remember { mutableStateOf<String?>(null) }
    val d = playable.firstOrNull { it.id == pick } ?: playable.firstOrNull { it.custom } ?: playable.first()
    val t = vm.project.tracks.firstOrNull { it.instrumentId == d.id }
    Panel(Modifier.fillMaxWidth()) {
        SectionLabel("Play & record an instrument") { Text("${playable.count { it.custom }} of yours", style = Type.label, color = p.textFaint) }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Photo(d.art, Modifier.size(if (d.art.startsWith("build:")) 84.dp else 42.dp, 42.dp))
            Dropdown("Instrument", d.name, playable, { "${it.category} · ${it.name}" }) { pick = it.id }
            if (t == null) PrimaryButton("Add track", icon = Icons.Default.Add) { vm.addTrack(d) }
            else ToolButton(if (vm.transport == TransportState.RECORDING) "Stop" else "Record ${t.name}", icon = Icons.Default.FiberManualRecord, accent = p.rec, active = vm.transport == TransportState.RECORDING) { vm.selectedTrackId = t.id; vm.recordOn(t) }
            if (d.custom) ToolButton("Edit") { vm.editInstrument(d) }
        }
        if (t != null) { Spacer(Modifier.height(10.dp)); PlaySurfaceFor(vm, t, compact = true) }
    }
}
