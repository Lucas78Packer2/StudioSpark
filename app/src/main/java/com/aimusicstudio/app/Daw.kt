package com.aimusicstudio.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.media.midi.MidiReceiver
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

// Pro Tools-ish palette
val Chrome = Color(0xFF26272B); val Lane = Color(0xFF141518); val LaneAlt = Color(0xFF18191D); val Grid = Color(0xFF2E3036); val Dim = Color(0xFF8A8F98)
val Rec = Color(0xFFE53935); val Solo = Color(0xFFFFD54F); val Mute = Color(0xFFFFA000)
val TrackColors = listOf(Color(0xFF4FC3F7), Color(0xFFAED581), Color(0xFFF06292), Color(0xFFFFB74D), Color(0xFF9575CD), Color(0xFF4DB6AC), Color(0xFFE57373), Color(0xFFDCE775))
fun trackColor(i: Int) = TrackColors[i % TrackColors.size]

enum class Drawer(val label: String, val icon: ImageVector) { BROWSER("Browser", Icons.Default.LibraryMusic), INSTRUMENTS("Instruments", Icons.Default.Piano), GEAR("Pedals & Amps", Icons.Default.Cable) }
enum class Dock(val label: String) { MIX("Mix"), DRUMS("Drum Kit"), PIANO("Piano Roll"), STEPS("Step Seq"), NONE("Hide") }
enum class PlaySurface(val label: String) { PIANO("🎹 Piano keys"), STRINGS("🎸 Guitar strings"), KIT("🥁 Drum kit"), PADS("▦ Drum pads") }

fun barsBeats(s: StudioSession, t: Double): String { val beat = s.stepSeconds * (16.0 / s.timeSigDen)
    val b = (t / beat).toInt(); return String.format(Locale.US, "%3d|%d|%03d", b / s.timeSigNum + 1, b % s.timeSigNum + 1, (((t % beat) / beat) * 960).toInt()) }

@Composable
fun DawApp(vm: StudioViewModel = viewModel()) {
    var drawer by remember { mutableStateOf<Drawer?>(null) }
    var dock by remember { mutableStateOf(Dock.MIX) }
    var showSession by remember { mutableStateOf(false) }; var showTuner by remember { mutableStateOf(false) }
    var zoom by remember { mutableFloatStateOf(60f) }; var showAuto by remember { mutableStateOf(false) }
    val midiCount = rememberMidiInput(vm)
    if (vm.selectedTrackId == null) vm.session.tracks.firstOrNull()?.let { vm.selectedTrackId = it.id }

    BoxWithConstraints(Modifier.fillMaxSize().background(Bg).systemBarsPadding()) {
        val wide = maxWidth > 720.dp
        Column(Modifier.fillMaxSize()) {
            TransportBar(vm, midiCount, onSession = { showSession = true }, onTuner = { showTuner = true }, drawer = drawer, onDrawer = { drawer = if (drawer == it) null else it })
            vm.progress?.let { (m, p) -> Row(Modifier.fillMaxWidth().background(Chrome).padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(m, color = Color.White, fontSize = 12.sp, modifier = Modifier.width(200.dp).semantics { liveRegion = LiveRegionMode.Polite })
                LinearProgressIndicator(progress = { p }, Modifier.weight(1f), color = Orange, trackColor = Grid) } }
            Row(Modifier.weight(1f).fillMaxWidth()) {
                if (wide || drawer == null) EditWindow(vm, zoom, { zoom = it }, showAuto, { showAuto = it }, Modifier.weight(1f))
                drawer?.let { d -> Box(Modifier.then(if (wide) Modifier.width(360.dp) else Modifier.weight(1f)).fillMaxHeight().background(Panel).border(1.dp, Grid)) { DrawerPanel(vm, d) { drawer = null } } }
            }
            // System back / gesture closes MIDI keyboard or lyrics sheet from any track type
            if (vm.keyboardTrackId != null) androidx.activity.compose.BackHandler { vm.keyboardTrackId = null }
            if (vm.lyricsTrackId != null) androidx.activity.compose.BackHandler { vm.lyricsTrackId = null }
            vm.session.tracks.firstOrNull { it.id == vm.lyricsTrackId }?.let { lt -> LyricsPanel(vm, lt) }
            vm.session.tracks.firstOrNull { it.id == vm.keyboardTrackId }?.let { kt -> TrackKeyboard(vm, kt, vm.session.tracks.indexOf(kt)) }
            vm.pendingTake?.let { p -> AlertDialog(onDismissRequest = {}, title = { Text("Your take – add it to your piece?") },
                text = { Text("Put the recording " + (if (p.clip != null) "(${fmt(p.clip.duration)})" else "(${p.notes.size} notes)") + " on ${p.trackName}?") },
                confirmButton = { TextButton({ vm.placePendingTake() }) { Text("Place on track") } },
                dismissButton = { Row { TextButton({ vm.auditionPendingTake() }) { Text("Listen (private)") }; TextButton({ vm.discardPendingTake() }) { Text("Discard") } } }) }
            DockBar(dock) { dock = it }
            if (dock != Dock.NONE) Box(Modifier.fillMaxWidth().height(if (wide) 300.dp else 330.dp).background(Chrome)) {
                when (dock) { Dock.MIX -> MixWindow(vm); Dock.PIANO -> PianoRoll(vm); Dock.STEPS -> StepSeq(vm); Dock.DRUMS -> Column { KitSelector(vm.session.kit) { k -> vm.selectKit(null, k) }; DrumKitView(vm, vm.session.kit, Modifier.fillMaxWidth().weight(1f)) }; else -> {} }
            }
            if (vm.status.isNotEmpty()) Text("● " + vm.status, color = Teal.copy(alpha = 0.85f), fontSize = 11.sp, maxLines = 1, modifier = Modifier.fillMaxWidth().background(Color(0xFF0E0F12)).padding(horizontal = 10.dp, vertical = 4.dp).semantics { liveRegion = LiveRegionMode.Polite })
        }
    }
    if (showSession) Dialog(onDismissRequest = { showSession = false }) { Surface(color = Panel, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxHeight(0.9f)) { SessionDialog(vm) } }
    if (showTuner) Dialog(onDismissRequest = { showTuner = false }) { Surface(color = Panel, shape = RoundedCornerShape(8.dp)) { TunerPanel() } }
}

// ---------------- Transport ----------------
@Composable
fun TBtn(icon: ImageVector, desc: String, active: Boolean = false, tint: Color = Color.White, activeColor: Color = Orange, onClick: () -> Unit) {
    Box(Modifier.padding(3.dp).size(44.dp).clip(RoundedCornerShape(10.dp)).background(if (active) activeColor else Color(0xFF2E3036))
        .clickable(onClickLabel = desc) { onClick() }.semantics { contentDescription = desc; role = Role.Button; if (active) stateDescription = "on" }, contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = if (active) Color.Black else tint) }
}

@Composable
fun TransportBar(vm: StudioViewModel, midi: Int, onSession: () -> Unit, onTuner: () -> Unit, drawer: Drawer?, onDrawer: (Drawer) -> Unit) {
    val s = vm.session; val ctx = LocalContext.current
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) vm.startRecording() }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { u -> vm.importAudio(u, false) } }
    Row(Modifier.fillMaxWidth().background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xFF24252B), Color(0xFF16171B)))).border(1.dp, Orange.copy(alpha = 0.2f)).horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(R.drawable.brand_sparknote), "StudioSpark", Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)))
        Text(" StudioSpark", color = Orange, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
        Column(Modifier.clickable(onClickLabel = "Open session menu") { onSession() }.padding(horizontal = 6.dp)) {
            Text(s.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, modifier = Modifier.widthIn(max = 160.dp))
            Text("Session ▾", color = Orange, fontSize = 10.sp) }
        TBtn(Icons.Default.Undo, "Undo", tint = if (vm.canUndo) Color.White else Dim) { vm.undo() }; TBtn(Icons.Default.Redo, "Redo", tint = if (vm.canRedo) Color.White else Dim) { vm.redo() }
        Box(Modifier.padding(horizontal = 6.dp).width(1.dp).height(32.dp).background(Color(0xFF3E4148)))
        TBtn(Icons.Default.SkipPrevious, "Return to start") { vm.seek(0.0) }
        TBtn(Icons.Default.FastRewind, "Rewind one bar") { vm.seek((vm.playhead - s.barSeconds).coerceAtLeast(0.0)) }
        TBtn(Icons.Default.Stop, "Stop") { vm.stop(); if (vm.isRecording) vm.stopRecording() }
        TBtn(if (vm.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, if (vm.isPlaying) "Pause" else "Play", active = vm.isPlaying, activeColor = Color(0xFF66BB6A)) { if (vm.isPlaying) vm.pause() else vm.play() }
        TBtn(Icons.Default.FastForward, "Forward one bar") { vm.seek(vm.playhead + s.barSeconds) }
        TBtn(Icons.Default.FiberManualRecord, if (vm.isRecording) "Stop mic recording" else "Record from microphone", active = vm.isRecording, tint = Rec, activeColor = Rec) {
            if (vm.isRecording) vm.stopRecording() else if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { vm.startRecording(); if (!vm.isPlaying) vm.play() } else perm.launch(Manifest.permission.RECORD_AUDIO) }
        TBtn(Icons.Default.Repeat, "Loop", active = s.isLoopEnabled, activeColor = Teal) { vm.update { it.copy(isLoopEnabled = !it.isLoopEnabled, loopEnd = if (it.loopEnd <= it.loopStart) it.barSeconds * 2 else it.loopEnd) } }
        TBtn(Icons.Default.Piano, "MIDI and pad capture into armed track", active = vm.liveCapture, activeColor = Rec) { vm.liveCapture = !vm.liveCapture }
        Box(Modifier.padding(horizontal = 6.dp).width(1.dp).height(32.dp).background(Color(0xFF3E4148)))
        // Counter
        Column(Modifier.padding(horizontal = 8.dp).clip(RoundedCornerShape(4.dp)).background(Color.Black).padding(horizontal = 10.dp, vertical = 2.dp)
            .semantics(mergeDescendants = true) { contentDescription = "Position bar ${barsBeats(s, vm.playhead)}, ${fmt(vm.playhead)}" }) {
            Text(barsBeats(s, vm.playhead), color = Color(0xFF7CFC9A), fontFamily = FontFamily.Monospace, fontSize = 18.sp)
            Text(fmt(vm.playhead), color = Teal, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
        // Tempo & meter
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SmallBtn("−", "Tempo down") { vm.update { it.copy(tempoBPM = (it.tempoBPM - 1).coerceAtLeast(40)) } }
                Text("${s.tempoBPM}", color = Orange, fontFamily = FontFamily.Monospace, fontSize = 16.sp, modifier = Modifier.semantics { contentDescription = "Tempo ${s.tempoBPM} BPM" })
                SmallBtn("+", "Tempo up") { vm.update { it.copy(tempoBPM = (it.tempoBPM + 1).coerceAtMost(240)) } } }
            var ts by remember { mutableStateOf(false) }
            Box { Text("${s.timeSigNum}/${s.timeSigDen} ▾", color = Color.White, fontSize = 11.sp, modifier = Modifier.clickable(onClickLabel = "Change time signature") { ts = true })
                DropdownMenu(ts, { ts = false }) { listOf(2 to 4, 3 to 4, 4 to 4, 5 to 4, 6 to 8, 7 to 8, 12 to 8).forEach { (n, d) ->
                    DropdownMenuItem({ Text("$n/$d") }, { vm.update { it.copy(timeSigNum = n, timeSigDen = d) }; ts = false }) } } }
        }
        MasterMeter(vm.level, Modifier.padding(horizontal = 8.dp).width(90.dp).height(36.dp))
        Text(if (midi > 0) "MIDI ●$midi" else "MIDI ○", color = if (midi > 0) Teal else Dim, fontSize = 11.sp, modifier = Modifier.padding(4.dp))
        TBtn(Icons.Default.GraphicEq, "Tuner") { onTuner() }
        TBtn(Icons.Default.FileUpload, "Import audio file") { importer.launch(arrayOf("audio/*")) }
        Drawer.values().forEach { d -> TBtn(d.icon, "${d.label} panel", active = drawer == d) { onDrawer(d) } }
    }
}
@Composable fun Brush() = Color(0xFF1E1F23)
@Composable fun SmallBtn(t: String, desc: String, onClick: () -> Unit) =
    Box(Modifier.size(32.dp).clickable(onClickLabel = desc) { onClick() }.semantics { contentDescription = desc; role = Role.Button }, contentAlignment = Alignment.Center) { Text(t, color = Color.White, fontSize = 18.sp) }

@Composable
fun DockBar(dock: Dock, onDock: (Dock) -> Unit) {
    Row(Modifier.fillMaxWidth().background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xFF16171B), Color.Black))).border(1.dp, Orange.copy(alpha = 0.25f)).padding(horizontal = 6.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("WINDOW", color = Dim, fontSize = 10.sp, modifier = Modifier.padding(end = 6.dp))
        Dock.values().forEach { d -> val on = dock == d
            Text(d.label, color = if (on) Color.Black else Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(3.dp).clip(RoundedCornerShape(8.dp)).background(if (on) Orange else Chrome).clickable { onDock(d) }.padding(horizontal = 16.dp, vertical = 9.dp)
                    .semantics { role = Role.Tab; selected = on }) }
    }
}

// ---------------- Edit window ----------------
@Composable
fun EditWindow(vm: StudioViewModel, zoom: Float, onZoom: (Float) -> Unit, showAuto: Boolean, onAuto: (Boolean) -> Unit, modifier: Modifier) {
    val s = vm.session; val hs = rememberScrollState()
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { u -> vm.importAudio(u, false) } }
    val splitter = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { u -> vm.importAudio(u, true) } }
    val midiIn = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { u -> vm.importMidi(u) } }
    val totalW = (s.length * zoom + 200).dp
    Column(modifier.fillMaxHeight().background(Lane)) {
        Row(Modifier.fillMaxWidth().background(Chrome).horizontalScroll(rememberScrollState()).padding(2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("EDIT", color = Orange, fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 6.dp))
            var add by remember { mutableStateOf(false) }
            Box { ToolChip("+ Track") { add = true }
                DropdownMenu(add, { add = false }) {
                    DropdownMenuItem({ Text("Audio track") }, { vm.addTrack(TrackKind.AUDIO); add = false })
                    DropdownMenuItem({ Text("Instrument (synth) track") }, { vm.addTrack(TrackKind.SYNTH); add = false })
                    DropdownMenuItem({ Text("Guitar track (amp + pedals)") }, { vm.addGuitarTrack(); add = false })
                    DropdownMenuItem({ Text("AI lyrics + vocals track") }, { vm.addLyricsTrack(); add = false })
                    DropdownMenuItem({ Text("Import audio file…") }, { importer.launch(arrayOf("audio/*")); add = false })
                    DropdownMenuItem({ Text("Import song & split into stems…") }, { splitter.launch(arrayOf("audio/*")); add = false })
                    DropdownMenuItem({ Text("Import MIDI file…") }, { midiIn.launch(arrayOf("audio/midi", "audio/x-midi", "*/*")); add = false })
                } }
            ToolChip("Split song → stems") { splitter.launch(arrayOf("audio/*")) }
            ToolChip(if (showAuto) "Automation ✓" else "Automation") { onAuto(!showAuto) }
            ToolChip("Zoom −") { onZoom((zoom / 1.5f).coerceAtLeast(10f)) }; ToolChip("Zoom +") { onZoom((zoom * 1.5f).coerceAtMost(500f)) }
            ToolChip("Loop = bar 1-2") { vm.update { it.copy(loopStart = 0.0, loopEnd = it.barSeconds * 2, isLoopEnabled = true) } }
        }
        // Ruler
        Row { Box(Modifier.width(168.dp).height(30.dp).background(Chrome), contentAlignment = Alignment.CenterStart) { Text("  Bars | Min:Sec", color = Dim, fontSize = 10.sp) }
            Ruler(vm, zoom, totalW, hs) }
        LazyColumn(Modifier.weight(1f)) {
            itemsIndexedTracks(s.tracks) { i, t ->
                Row(Modifier.height(72.dp).fillMaxWidth()) {
                    TrackHeader(vm, t, i)
                    Box(Modifier.horizontalScroll(hs).width(totalW).fillMaxHeight().background(if (i % 2 == 0) Lane else LaneAlt)) { TrackLane(vm, t, i, zoom) }
                }
                if (showAuto) { AutoLane(vm, t, false, zoom, totalW, hs); AutoLane(vm, t, true, zoom, totalW, hs) }
                HorizontalDivider(color = Color.Black)
            }
            item { Row(Modifier.padding(8.dp)) { ToolChip("+ Audio") { vm.addTrack(TrackKind.AUDIO) }; ToolChip("+ Synth") { vm.addTrack(TrackKind.SYNTH) }; ToolChip("+ Guitar") { vm.addGuitarTrack() } } }
        }
    }
}
fun androidx.compose.foundation.lazy.LazyListScope.itemsIndexedTracks(l: List<Track>, c: @Composable (Int, Track) -> Unit) =
    items(l.size, key = { l[it].id }) { c(it, l[it]) }

@Composable fun ToolChip(t: String, onClick: () -> Unit) = Text(t, color = Color.White, fontSize = 12.sp,
    modifier = Modifier.padding(3.dp).heightIn(min = 36.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF2E3036)).border(1.dp, Color(0xFF3E4148), RoundedCornerShape(8.dp)).clickable { onClick() }.padding(horizontal = 10.dp, vertical = 9.dp).semantics { role = Role.Button })

@Composable
fun Ruler(vm: StudioViewModel, zoom: Float, totalW: Dp, hs: ScrollState) {
    val s = vm.session; val d = LocalDensity.current.density
    Box(Modifier.horizontalScroll(hs).width(totalW).height(32.dp).background(Color(0xFF1B1C21))
        .pointerInput(zoom) { detectTapGestures { o -> vm.seek((o.x / d / zoom).toDouble()) } }.semantics { contentDescription = "Timeline ruler, tap to move playhead" }) {
        Canvas(Modifier.fillMaxSize()) {
            if (s.isLoopEnabled) drawRect(Teal.copy(alpha = 0.35f), Offset((s.loopStart * zoom).toFloat() * d, 0f), Size(((s.loopEnd - s.loopStart) * zoom).toFloat() * d, size.height / 2))
            val beat = s.stepSeconds * (16.0 / s.timeSigDen); var b = 0
            while (b * beat < s.length + 4) { val x = (b * beat * zoom).toFloat() * d; val bar = b % s.timeSigNum == 0
                drawLine(if (bar) Color.LightGray else Grid, Offset(x, if (bar) 0f else size.height * 0.6f), Offset(x, size.height), if (bar) 2f else 1f); b++ }
            val px = (vm.playhead * zoom).toFloat() * d; drawLine(Orange, Offset(px, 0f), Offset(px, size.height), 4f)
        }
        val bars = (s.length / s.barSeconds).toInt() + 2
        for (b in 0 until bars) Text("${b + 1}", color = Orange, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.offset(x = (b * s.barSeconds * zoom).dp + 3.dp))
    }
}

@Composable
fun TrackHeader(vm: StudioViewModel, t: Track, i: Int) {
    val sel = vm.selectedTrackId == t.id; var menu by remember { mutableStateOf(false) }
    Row(Modifier.width(176.dp).fillMaxHeight().padding(vertical = 1.dp).clip(RoundedCornerShape(topEnd = 8.dp, bottomEnd = 8.dp)).background(if (sel) Color(0xFF34363C) else Chrome).border(if (sel) 1.dp else 0.dp, if (sel) Orange.copy(alpha = 0.6f) else Color.Transparent, RoundedCornerShape(topEnd = 8.dp, bottomEnd = 8.dp)).clickable(onClickLabel = "Select track and open or close keyboard") {
        vm.selectedTrackId = t.id
        if (t.kind == TrackKind.LYRICS) {
            vm.keyboardTrackId = null
            vm.lyricsTrackId = if (vm.lyricsTrackId == t.id) null else t.id
        } else {
            vm.lyricsTrackId = null
            vm.keyboardTrackId = if (vm.keyboardTrackId == t.id) null else t.id
        }
    }) {
        Box(Modifier.width(6.dp).fillMaxHeight().background(trackColor(i)))
        Column(Modifier.padding(4.dp).weight(1f)) {
            Text(t.name, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            val voice = when (t.kind) { TrackKind.AUDIO -> "Audio"; TrackKind.SYNTH -> "Inst · ${t.waveform.label}"; TrackKind.GUITAR -> "${t.guitar.label} → ${t.amp.label}"; TrackKind.LYRICS -> "Lyrics · ${t.voiceGender.label} ${t.vocalStyle.label}" }
            if (t.kind == TrackKind.LYRICS) VocalLiveStrip(vm, t, compact = true)
            Box { Text("$voice ▾", color = Teal, fontSize = 10.sp, maxLines = 1, modifier = Modifier.clickable(onClickLabel = "Assign instrument or sound to ${t.name}") { menu = true })
                AssignMenu(vm, t, menu) { menu = false } }
            Row { MSR("M", "Mute ${t.name}", t.isMuted, Mute) { vm.updateTrack(t.id) { it.copy(isMuted = !it.isMuted) } }
                MSR("S", "Solo ${t.name}", t.isSoloed, Solo) { vm.updateTrack(t.id) { it.copy(isSoloed = !it.isSoloed) } }
                MSR("R", "Record arm ${t.name}", vm.armedTrackId == t.id, Rec) { vm.armedTrackId = if (vm.armedTrackId == t.id) null else t.id }
                MSR("✕", "Delete track ${t.name}", false, Rec) { vm.removeTrack(t.id) } }
        }
    }
}
@Composable fun MSR(l: String, desc: String, on: Boolean, c: Color, onClick: () -> Unit) =
    Box(Modifier.padding(1.dp).size(30.dp, 24.dp).clip(RoundedCornerShape(3.dp)).background(if (on) c else Color(0xFF45474E)).clickable { onClick() }
        .semantics { contentDescription = desc; role = Role.Switch; stateDescription = if (on) "on" else "off" }, contentAlignment = Alignment.Center) {
        Text(l, color = if (on) Color.Black else Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold) }

@Composable
fun AssignMenu(vm: StudioViewModel, t: Track, open: Boolean, close: () -> Unit) {
    DropdownMenu(open, close) {
        Text("  Onboard instruments", color = Orange, fontSize = 11.sp)
        InstrumentLibrary.filter { it.waveform != null }.forEach { i -> DropdownMenuItem({ Text(i.name) }, { vm.assignInstrument(t.id, i); close() }) }
        Text("  Drum hit → clip at playhead (${vm.session.kit.label} kit)", color = Orange, fontSize = 11.sp)
        InstrumentLibrary.filter { it.pad != null }.forEach { i -> DropdownMenuItem({ Text(i.name) }, { vm.assignInstrument(t.id, i); close() }) }
        Text("  Guitars", color = Orange, fontSize = 11.sp)
        GuitarModel.values().forEach { g -> DropdownMenuItem({ Text(g.label) }, { vm.updateTrack(t.id) { it.copy(kind = TrackKind.GUITAR, guitar = g) }; close() }) }
        Text("  Onboard & imported sounds → this lane", color = Orange, fontSize = 11.sp)
        vm.library.forEach { s -> DropdownMenuItem({ Text(s.name) }, { if (t.kind == TrackKind.AUDIO) vm.addSampleToTrack(s, t.id) else vm.addSampleToTrack(s, null); close() }) }
    }
}

@Composable
fun TrackLane(vm: StudioViewModel, t: Track, idx: Int, zoom: Float) {
    val col = trackColor(idx); val d = LocalDensity.current.density
    t.clips.forEach { c -> var menu by remember { mutableStateOf(false) }
        Box(Modifier.offset(x = (c.startTime * zoom).dp).width((c.duration * zoom).dp.coerceAtLeast(10.dp)).fillMaxHeight().padding(vertical = 3.dp)
            .clip(RoundedCornerShape(3.dp)).background(col.copy(alpha = 0.35f)).border(1.dp, col, RoundedCornerShape(3.dp)).clickable(onClickLabel = "Edit clip") { menu = true }
            .semantics { contentDescription = "Clip ${SampleLibrary.displayName(c.fileName)} at ${fmt(c.startTime)}"
                customActions = listOf(CustomAccessibilityAction("Move earlier") { vm.moveClip(t.id, c.id, -vm.session.stepSeconds * 4); true },
                    CustomAccessibilityAction("Move later") { vm.moveClip(t.id, c.id, vm.session.stepSeconds * 4); true },
                    CustomAccessibilityAction("Delete clip") { vm.deleteClip(t.id, c.id); true }) }) {
            Text(SampleLibrary.displayName(c.fileName), fontSize = 9.sp, color = Color.White, maxLines = 1, modifier = Modifier.background(col.copy(alpha = 0.7f)).fillMaxWidth().padding(horizontal = 3.dp))
            Canvas(Modifier.fillMaxSize().padding(top = 12.dp)) { // pseudo waveform
                val h = size.height; var x = 0f; var k = c.fileName.hashCode()
                while (x < size.width) { k = k * 1103515245 + 12345; val a = (((k ushr 16) and 255) / 255f) * h * 0.45f; drawLine(col, Offset(x, h / 2 - a), Offset(x, h / 2 + a), 1.5f); x += 3f } }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem({ Text("Nudge ◀ 1 beat") }, { vm.moveClip(t.id, c.id, -vm.session.stepSeconds * 4) })
                DropdownMenuItem({ Text("Nudge ▶ 1 beat") }, { vm.moveClip(t.id, c.id, vm.session.stepSeconds * 4) })
                DropdownMenuItem({ Text("Stretch +1 bar (snap)") }, { vm.resizeClipBars(t.id, c.id, 1) })
                DropdownMenuItem({ Text("Shrink −1 bar (snap)") }, { vm.resizeClipBars(t.id, c.id, -1) })
                DropdownMenuItem({ Text("Duplicate") }, { vm.duplicateClip(t.id, c.id); menu = false })
                DropdownMenuItem({ Text("✨ Polish (snap to bars)") }, { vm.polishTrack(t.id, c.id); menu = false })
                DropdownMenuItem({ Text("Trim −1 beat") }, { vm.trimClip(t.id, c.id, -vm.session.stepSeconds * 4) })
                DropdownMenuItem({ Text("Extend +1 beat") }, { vm.trimClip(t.id, c.id, vm.session.stepSeconds * 4) })
                DropdownMenuItem({ Text("Move to playhead") }, { vm.moveClip(t.id, c.id, vm.playhead - c.startTime); menu = false })
                DropdownMenuItem({ Text("Split stems from this clip") }, { vm.splitStems(java.io.File(vm.stemsDir, c.fileName)); menu = false })
                DropdownMenuItem({ Text("Delete") }, { vm.deleteClip(t.id, c.id); menu = false })
            }
        }
    }
    Canvas(Modifier.fillMaxSize()) {
        t.notes.forEach { n -> val y = size.height * (1 - ((n.pitch - 36).coerceIn(0, 48) / 48f))
            drawRect(col, Offset((n.startTime * zoom).toFloat() * d, y - 3f), Size((n.duration * zoom).toFloat() * d, 6f)) }
        val px = (vm.playhead * zoom).toFloat() * d; drawLine(Orange, Offset(px, 0f), Offset(px, size.height), 2f)
    }
}

@Composable
fun AutoLane(vm: StudioViewModel, t: Track, pan: Boolean, zoom: Float, totalW: Dp, hs: ScrollState) {
    val d = LocalDensity.current.density; val pts = if (pan) t.panAuto else t.volAuto
    Row(Modifier.height(40.dp)) {
        Row(Modifier.width(168.dp).fillMaxHeight().background(Color(0xFF202126)).padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (pan) "pan" else "volume", color = Teal, fontSize = 10.sp, modifier = Modifier.weight(1f))
            Text("clear", color = Dim, fontSize = 10.sp, modifier = Modifier.clickable { vm.clearAuto(t.id, pan) }.padding(6.dp)) }
        Box(Modifier.horizontalScroll(hs).width(totalW).fillMaxHeight().background(Color(0xFF101114))
            .pointerInput(t.id, zoom, pan) { detectTapGestures { o -> val v = 1 - o.y / size.height; vm.addAutoPoint(t.id, pan, (o.x / d / zoom).toDouble(), if (pan) v * 2 - 1 else v) } }
            .semantics { contentDescription = "${t.name} ${if (pan) "pan" else "volume"} automation, ${pts.size} points. Tap to add a breakpoint." }) {
            Canvas(Modifier.fillMaxSize()) {
                fun y(v: Float) = size.height * (1 - if (pan) (v + 1) / 2 else v)
                val def = if (pan) t.pan else t.volume
                if (pts.isEmpty()) drawLine(Teal.copy(alpha = 0.5f), Offset(0f, y(def)), Offset(size.width, y(def)), 2f)
                var prev: Offset? = null
                pts.forEach { p -> val o = Offset((p.time * zoom).toFloat() * d, y(p.value)); prev?.let { drawLine(Teal, it, o, 2f) } ?: drawLine(Teal, Offset(0f, o.y), o, 2f); drawCircle(Orange, 5f, o); prev = o }
                prev?.let { drawLine(Teal, it, Offset(size.width, it.y), 2f) }
            }
        }
    }
}

// ---------------- Mix window ----------------
@Composable
fun MixWindow(vm: StudioViewModel) {
    var layout by remember { mutableStateOf(MixerLayout.CONSOLE) }; val s = vm.session
    Column {
        Row(Modifier.fillMaxWidth().background(Color(0xFF1A1B1F)).padding(2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("MIX", color = Orange, fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 6.dp))
            MixerLayout.values().forEach { l -> FilterChip(selected = layout == l, onClick = { layout = l }, label = { Text(l.label, fontSize = 11.sp) }, modifier = Modifier.padding(end = 4.dp)) }
            Spacer(Modifier.weight(1f)); ToolChip("Bounce WAV") { vm.bounce() }
        }
        LazyRow(Modifier.fillMaxSize().padding(4.dp)) {
            items(s.tracks, key = { it.id }) { t -> val i = s.tracks.indexOf(t)
                when (layout) { MixerLayout.CONSOLE -> ConsoleStrip(vm, t, i); MixerLayout.COMPACT -> CompactStrip(vm, t, i); MixerLayout.BUS -> BusStrip(vm, t, i) } }
            item { ReturnStrip("Reverb Rtn", s.reverbReturn, Color(0xFF8050D0)) { v -> vm.update { it.copy(reverbReturn = v) } } }
            item { ReturnStrip("Delay Rtn", s.delayReturn, Color(0xFF20A060)) { v -> vm.update { it.copy(delayReturn = v) } } }
            item { Column(Modifier.width(96.dp).fillMaxHeight().padding(2.dp).background(Color(0xFF2A1E10)).padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("MASTER", color = Orange, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Row(Modifier.weight(1f)) { Fader("Master", s.masterVolume, Orange) { v -> vm.update { it.copy(masterVolume = v) } }; Meter(vm.level) }
                Text("${(s.masterVolume * 100).roundToInt()}", color = Color.White, fontSize = 11.sp) } }
        }
    }
}

@Composable
fun Fader(label: String, v: Float, c: Color, modifier: Modifier = Modifier, onChange: (Float) -> Unit) {
    var h by remember { mutableFloatStateOf(1f) }
    Box(modifier.width(34.dp).fillMaxHeight().padding(vertical = 4.dp).clip(RoundedCornerShape(3.dp)).background(Color(0xFF0E0F11))
        .pointerInput(Unit) { h = size.height.toFloat(); detectVerticalDragGestures { ch, dy -> ch.consume(); onChange((v - dy / h).coerceIn(0f, 1f)) } }
        .pointerInput(Unit) { detectTapGestures { o -> onChange((1 - o.y / size.height).coerceIn(0f, 1f)) } }
        .semantics { contentDescription = "$label fader"; stateDescription = "${(v * 100).roundToInt()} percent"; progressBarRangeInfo = ProgressBarRangeInfo(v, 0f..1f)
            setProgress { x -> onChange(x.coerceIn(0f, 1f)); true } }) {
        Canvas(Modifier.fillMaxSize()) { val cx = size.width / 2; drawLine(Grid, Offset(cx, 6f), Offset(cx, size.height - 6f), 4f)
            val y = (1 - v) * (size.height - 20f) + 10f; drawRect(Color(0xFFBDBDBD), Offset(4f, y - 10f), Size(size.width - 8f, 20f)); drawLine(c, Offset(4f, y), Offset(size.width - 4f, y), 3f) }
    }
}
@Composable fun Meter(level: Float) = Canvas(Modifier.width(10.dp).fillMaxHeight().padding(vertical = 4.dp).background(Color.Black)) {
    val h = size.height * level.coerceIn(0f, 1f); drawRect(Teal, Offset(0f, size.height - h), Size(size.width, h))
    if (level > 0.85f) drawRect(Rec, Offset(0f, 0f), Size(size.width, 6f)) }

@Composable
fun Knob(label: String, v: Float, range: ClosedFloatingPointRange<Float>, text: String, onChange: (Float) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(44.dp)) {
        val frac = (v - range.start) / (range.endInclusive - range.start)
        Canvas(Modifier.size(30.dp).pointerInput(range) { detectVerticalDragGestures { ch, dy -> ch.consume(); onChange((v - dy / 150f * (range.endInclusive - range.start)).coerceIn(range)) } }
            .semantics { contentDescription = label; stateDescription = text; setProgress { x -> onChange(x.coerceIn(range)); true }; progressBarRangeInfo = ProgressBarRangeInfo(v, range) }) {
            drawCircle(Color(0xFF3A3C42)); drawArc(Orange, 135f, 270f * frac, false, style = androidx.compose.ui.graphics.drawscope.Stroke(4f)) }
        Text(label, color = Dim, fontSize = 8.sp, maxLines = 1)
    }
}

@Composable
fun StripShell(t: Track, i: Int, width: Dp, content: @Composable ColumnScope.() -> Unit) =
    Column(Modifier.width(width).fillMaxHeight().padding(2.dp).background(Color(0xFF232428)).border(1.dp, Color.Black), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().height(4.dp).background(trackColor(i))); content()
        Text(t.name, color = Color.White, fontSize = 10.sp, maxLines = 1, modifier = Modifier.fillMaxWidth().background(trackColor(i).copy(alpha = 0.3f)).padding(2.dp)) }

@Composable
fun ConsoleStrip(vm: StudioViewModel, t: Track, i: Int) = StripShell(t, i, 112.dp) {
    var fx by remember { mutableStateOf(false) }
    val inserts = t.pedals.map { (if (it.on) "" else "⊘ ") + it.type.label } + listOfNotNull(t.amp.takeIf { it != AmpModel.NONE }?.label, "EQ3", if (t.compressor.isEnabled) "Comp" else null)
    Column(Modifier.fillMaxWidth().clickable(onClickLabel = "Edit inserts EQ and compressor") { fx = true }.padding(2.dp)) {
        Text("INSERTS", color = Dim, fontSize = 8.sp); inserts.take(4).forEach { Text(it, color = Color.White, fontSize = 9.sp, maxLines = 1, modifier = Modifier.fillMaxWidth().background(Color(0xFF34363C)).padding(1.dp)) }
        if (inserts.size > 4) Text("+${inserts.size - 4}", color = Dim, fontSize = 8.sp) }
    Row { Knob("Rev", t.reverbSend, 0f..1f, "${(t.reverbSend * 100).roundToInt()}%") { v -> vm.updateTrack(t.id) { it.copy(reverbSend = v) } }
        Knob("Dly", t.delaySend, 0f..1f, "${(t.delaySend * 100).roundToInt()}%") { v -> vm.updateTrack(t.id) { it.copy(delaySend = v) } } }
    Knob("Pan", t.pan, -1f..1f, "${(t.pan * 100).roundToInt()}") { v -> vm.updateTrack(t.id) { it.copy(pan = v) } }
    Row { MSR("M", "Mute ${t.name}", t.isMuted, Mute) { vm.updateTrack(t.id) { it.copy(isMuted = !it.isMuted) } }; MSR("S", "Solo ${t.name}", t.isSoloed, Solo) { vm.updateTrack(t.id) { it.copy(isSoloed = !it.isSoloed) } } }
    Row(Modifier.weight(1f)) { Fader(t.name, t.volume, trackColor(i)) { v -> vm.updateTrack(t.id) { it.copy(volume = v) } }; Meter(if (MixRenderer.audible(t, vm.session.tracks)) vm.level * t.volume else 0f) }
    if (fx) Dialog(onDismissRequest = { fx = false }) { Surface(color = Panel, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxHeight(0.85f)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(8.dp)) { ChannelStrip(vm, vm.session.tracks.firstOrNull { it.id == t.id } ?: t)
            Text("Pedalboard & amp", color = Orange, fontWeight = FontWeight.Bold); GearEditor(vm, vm.session.tracks.firstOrNull { it.id == t.id } ?: t) } } }
}
@Composable
fun CompactStrip(vm: StudioViewModel, t: Track, i: Int) = StripShell(t, i, 64.dp) {
    Row { MSR("M", "Mute ${t.name}", t.isMuted, Mute) { vm.updateTrack(t.id) { it.copy(isMuted = !it.isMuted) } } }
    Row { MSR("S", "Solo ${t.name}", t.isSoloed, Solo) { vm.updateTrack(t.id) { it.copy(isSoloed = !it.isSoloed) } } }
    Fader(t.name, t.volume, trackColor(i), Modifier.weight(1f)) { v -> vm.updateTrack(t.id) { it.copy(volume = v) } }
}
@Composable
fun BusStrip(vm: StudioViewModel, t: Track, i: Int) = StripShell(t, i, 96.dp) {
    Text("SENDS", color = Dim, fontSize = 8.sp)
    Text("Reverb", color = Color(0xFFB39DDB), fontSize = 10.sp)
    Fader("${t.name} reverb send", t.reverbSend, Color(0xFF8050D0), Modifier.weight(1f)) { v -> vm.updateTrack(t.id) { it.copy(reverbSend = v) } }
    Text("Delay", color = Color(0xFF81C784), fontSize = 10.sp)
    Fader("${t.name} delay send", t.delaySend, Color(0xFF20A060), Modifier.weight(1f)) { v -> vm.updateTrack(t.id) { it.copy(delaySend = v) } }
}
@Composable
fun ReturnStrip(label: String, v: Float, c: Color, onChange: (Float) -> Unit) =
    Column(Modifier.width(80.dp).fillMaxHeight().padding(2.dp).background(Color(0xFF1C1A24)).padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = c, fontSize = 10.sp, fontWeight = FontWeight.Bold); Fader(label, v, c, Modifier.weight(1f), onChange); Text("${(v * 100).roundToInt()}", color = Color.White, fontSize = 10.sp) }

// ---------------- Piano roll & step sequencer docks ----------------
@Composable
fun PianoRoll(vm: StudioViewModel) {
    val t = vm.session.tracks.firstOrNull { it.id == vm.selectedTrackId && it.kind != TrackKind.AUDIO }
    if (t == null) { Text("Select an instrument or guitar track (tap its header) to edit MIDI notes.", color = Dim, modifier = Modifier.padding(12.dp)); return }
    val names = Tuner.names; var octave by remember { mutableIntStateOf(4) }; val s = vm.session
    val steps = ((s.length / s.stepSeconds).toInt()).coerceAtLeast(32)
    val midiOut = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/midi")) { u -> u?.let { val f = vm.exportMidi(t.id); if (f != null) vm.viewModelScopeCopy(f, it) } }
    Column {
        Row(Modifier.background(Color(0xFF1A1B1F)).padding(2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("PIANO ROLL — ${t.name}", color = Orange, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp))
            ToolChip("Oct −") { octave = (octave - 1).coerceAtLeast(1) }; Text("C$octave", color = Color.White); ToolChip("Oct +") { octave = (octave + 1).coerceAtMost(7) }
            ToolChip("Export .mid") { midiOut.launch(t.name + ".mid") }; ToolChip("Clear") { vm.updateTrack(t.id) { it.copy(notes = emptyList()) } }
        }
        val hs = rememberScrollState()
        Column(Modifier.verticalScroll(rememberScrollState())) {
            ((octave + 2) * 12 - 1 downTo (octave + 1) * 12 - 0).forEach { pitch -> val sharp = names[pitch % 12].length > 1
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(48.dp).height(18.dp).background(if (sharp) Color(0xFF222222) else Color(0xFFE0E0E0)).clickable { vm.triggerTrackNote(t, pitch) }
                        .semantics { contentDescription = "Play ${names[pitch % 12]}${pitch / 12 - 1}"; role = Role.Button }) { Text(names[pitch % 12] + (pitch / 12 - 1), fontSize = 9.sp, color = if (sharp) Color.White else Color.Black, modifier = Modifier.padding(start = 2.dp)) }
                    Row(Modifier.horizontalScroll(hs)) { (0 until steps).forEach { st ->
                        val on = t.notes.any { it.pitch == pitch && kotlin.math.abs(it.startTime - st * s.stepSeconds) < 1e-3 }
                        Box(Modifier.padding(0.5.dp).size(22.dp, 17.dp).background(if (on) trackColor(s.tracks.indexOf(t)) else if (st % 4 == 0) Color(0xFF2B2D33) else Color(0xFF1E2024))
                            .clickable { vm.toggleNote(t.id, pitch, st); if (!on) vm.triggerTrackNote(t, pitch) }
                            .semantics { contentDescription = "${names[pitch % 12]} step ${st + 1}"; stateDescription = if (on) "on" else "off"; role = Role.Checkbox }) } }
                }
            }
        }
    }
}
fun StudioViewModel.viewModelScopeCopy(f: java.io.File, u: android.net.Uri) { runCatching { getApplication<android.app.Application>().contentResolver.openOutputStream(u)?.use { o -> f.inputStream().use { it.copyTo(o) } } } }

@Composable
fun StepSeq(vm: StudioViewModel) = Column(Modifier.verticalScroll(rememberScrollState()).padding(6.dp)) {
    KitPicker(vm); DrumGrid(vm)
}
@Composable
fun KitPicker(vm: StudioViewModel) { val sel = vm.session.tracks.firstOrNull { it.id == vm.selectedTrackId }
    KitSelector(sel?.kit ?: vm.session.kit) { k -> vm.selectKit(sel?.id, k) } }

// ---------------- Drawers ----------------
@Composable
fun DrawerPanel(vm: StudioViewModel, d: Drawer, close: () -> Unit) = Column(Modifier.fillMaxSize()) {
    Row(Modifier.fillMaxWidth().background(Chrome).border(1.dp, Orange.copy(alpha = 0.3f)).padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(d.icon, null, tint = Orange); Text("  ${d.label.uppercase()}", color = Color.White, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp, modifier = Modifier.weight(1f).semantics { heading() })
        IconButton(onClick = close) { Icon(Icons.Default.Close, "Close ${d.label}", tint = Color.White) } }
    val sel = vm.session.tracks.firstOrNull { it.id == vm.selectedTrackId }
    Text("Target track: ${sel?.name ?: "new track"}", color = Teal, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 8.dp))
    when (d) { Drawer.BROWSER -> SoundBrowser(vm); Drawer.INSTRUMENTS -> InstrumentRack(vm, sel); Drawer.GEAR -> if (sel != null) Column(Modifier.verticalScroll(rememberScrollState()).padding(8.dp)) { GearEditor(vm, sel) } else Text("Select a track", color = Dim) }
}

@Composable
fun SoundBrowser(vm: StudioViewModel) {
    var q by remember { mutableStateOf("") }; var cat by remember { mutableStateOf("All") }; var slicing by remember { mutableStateOf<SampleLibraryItem?>(null) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { u -> vm.importAudio(u, false) } }
    val cats = listOf("All") + vm.library.map { it.category }.distinct().sorted()
    val items = vm.library.filter { (cat == "All" || it.category == cat) && it.name.contains(q, true) }
    Column(Modifier.padding(6.dp)) {
        Text("SPARK 8-TRACK SOUND LIBRARY", color = Orange, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Text("Boss-style 8-track bank: ${vm.library.size} built-in kits, keys, synths, guitars & basses. ▶ audition, → Track adds it.", color = Dim, fontSize = 10.sp)
        Row(verticalAlignment = Alignment.CenterVertically) { OutlinedTextField(q, { q = it }, placeholder = { Text("Search sounds") }, singleLine = true, modifier = Modifier.weight(1f)); ToolChip("Import") { importer.launch(arrayOf("audio/*")) } }
        Row(Modifier.horizontalScroll(rememberScrollState())) { cats.forEach { c -> FilterChip(selected = cat == c, onClick = { cat = c }, label = { Text(c, fontSize = 11.sp) }, modifier = Modifier.padding(end = 4.dp)) } }
        LazyColumn { items(items, key = { it.id }) { item ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp).background(Chrome).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.preview(item) }) { Icon(Icons.Default.PlayArrow, "Audition ${item.name}", tint = Teal) }
                Column(Modifier.weight(1f)) { Text(item.name, color = Color.White, fontSize = 12.sp, maxLines = 1); Text("${item.category} · ${String.format(Locale.US, "%.1f", item.duration)}s", color = Dim, fontSize = 10.sp) }
                ToolChip("→ Track") { vm.addSampleToTrack(item, null) }
                IconButton(onClick = { slicing = item }) { Icon(Icons.Default.ContentCut, "Slice ${item.name}", tint = Color.White) }
                IconButton(onClick = { vm.splitStems(item.file) }) { Icon(Icons.Default.CallSplit, "Split ${item.name} into stems", tint = Color.White) }
            } } }
    }
    slicing?.let { it2 -> SliceDialog(vm, it2) { slicing = null } }
}

@Composable
fun SliceDialog(vm: StudioViewModel, item: SampleLibraryItem, close: () -> Unit) = Dialog(onDismissRequest = close) {
    var n by remember { mutableIntStateOf(8) }
    Surface(color = Panel, shape = RoundedCornerShape(8.dp)) { Column(Modifier.padding(12.dp)) {
        Text("Slice ${item.name}", color = Orange, fontWeight = FontWeight.Bold)
        Row { listOf(4, 8, 16).forEach { k -> FilterChip(selected = n == k, onClick = { n = k }, label = { Text("$k slices") }, modifier = Modifier.padding(end = 4.dp)) } }
        Text("Slice pads — tap to play", color = Dim, fontSize = 11.sp)
        (0 until n).chunked(4).forEach { row -> Row { row.forEach { k ->
            Box(Modifier.padding(3.dp).size(56.dp).clip(RoundedCornerShape(4.dp)).background(if (k % 2 == 0) Color(0xFF8A3E0E) else Color(0xFF0F5E57)).clickable { vm.previewSlice(item, n, k) }
                .semantics { contentDescription = "Slice ${k + 1}"; role = Role.Button }, contentAlignment = Alignment.Center) { Text("${k + 1}", color = Color.White) } } } }
        Button(onClick = { vm.sliceToTrack(item, n); close() }) { Text("Place $n slices as clips on a new track") }
    } }
}

@Composable
fun InstrumentRack(vm: StudioViewModel, sel: Track?) = Column(Modifier.verticalScroll(rememberScrollState()).padding(6.dp)) {
    KitPicker(vm)
    Text("Drum pads (${(sel?.kit ?: vm.session.kit).label}) — tap to play; ⤓ puts the hit on the target track at the playhead", color = Dim, fontSize = 10.sp)
    DrumPad.values().toList().chunked(4).forEach { row -> Row { row.forEach { p ->
        Column(Modifier.weight(1f).padding(2.dp)) {
            Box(Modifier.fillMaxWidth().height(54.dp).clip(RoundedCornerShape(4.dp)).background(if (p.ordinal % 2 == 0) Color(0xFF8A3E0E) else Color(0xFF0F5E57)).clickable { vm.triggerPad(p, sel?.kit) }
                .semantics { contentDescription = "${p.label} pad"; role = Role.Button }, contentAlignment = Alignment.Center) { Text(p.label, color = Color.White, fontSize = 11.sp) }
            Text("⤓ track", color = Teal, fontSize = 10.sp, modifier = Modifier.clickable(onClickLabel = "Place ${p.label} on track") {
                val inst = InstrumentLibrary.first { it.pad == p }; if (sel != null) vm.assignInstrument(sel.id, inst) else { vm.addTrack(TrackKind.AUDIO); vm.assignInstrument(vm.session.tracks.last().id, inst) } }.padding(4.dp))
        } }; repeat(4 - row.size) { Spacer(Modifier.weight(1f)) } } }
    Text("Synths", color = Orange, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
    InstrumentLibrary.filter { it.waveform != null }.forEach { i -> Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { vm.trigger(i) }) { Icon(Icons.Default.PlayArrow, "Audition ${i.name}", tint = Teal) }
        Text(i.name, color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(1f))
        ToolChip("Assign") { if (sel != null && sel.kind != TrackKind.AUDIO) vm.assignInstrument(sel.id, i) else vm.addInstrumentToSession(i) } } }
    Text("Guitars", color = Orange, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
    Row(Modifier.horizontalScroll(rememberScrollState())) { GuitarModel.values().forEach { g -> ToolChip(g.label) {
        val t = sel?.takeIf { it.kind == TrackKind.GUITAR } ?: run { vm.addGuitarTrack(); vm.session.tracks.last().also { vm.selectedTrackId = it.id } }
        vm.updateTrack(t.id) { it.copy(guitar = g) }; vm.triggerTrackNote(t.copy(guitar = g), 52) } } }
    val playT = vm.session.tracks.firstOrNull { it.id == vm.selectedTrackId && it.kind != TrackKind.AUDIO }
    Text("Keyboard → ${playT?.name ?: "default synth"} (records into armed track when capture is on)", color = Dim, fontSize = 10.sp, modifier = Modifier.padding(top = 8.dp))
    var oct by remember { mutableIntStateOf(4) }
    Row(verticalAlignment = Alignment.CenterVertically) { ToolChip("Oct −") { oct = (oct - 1).coerceAtLeast(1) }; Text("C$oct", color = Color.White); ToolChip("Oct +") { oct = (oct + 1).coerceAtMost(7) } }
    Row(Modifier.fillMaxWidth().height(90.dp)) { (0 until 12).forEach { k -> val sharp = Tuner.names[k].length > 1; val pitch = 12 * (oct + 1) + k
        Box(Modifier.weight(1f).fillMaxHeight().padding(1.dp).background(if (sharp) Color(0xFF222222) else Color(0xFFEFEFEF))
            .clickable { if (playT != null) vm.triggerTrackNote(playT, pitch) else vm.midiNote(pitch, 100) }
            .semantics { contentDescription = "${Tuner.names[k]}$oct"; role = Role.Button }) } }
}

@Composable
fun GearEditor(vm: StudioViewModel, t0: Track) {
    val t = vm.session.tracks.firstOrNull { it.id == t0.id } ?: t0
    val testPitch = if (t.guitar.label.contains("Bass") || SongAI.role(t) == SongAI.Role.BASS) 40 else 52
    fun live(f: (Track) -> Track) { vm.updateTrack(t.id, f); vm.session.tracks.firstOrNull { it.id == t.id }?.let { vm.triggerTrackNote(it, testPitch) } }
    var ampMenu by remember { mutableStateOf(false) }; var addMenu by remember { mutableStateOf(false) }
    Text("LIVE RIG — ${t.name}  (changes are heard on the next note while you play)", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
    Row(Modifier.horizontalScroll(rememberScrollState())) { FxPresets.forEach { p -> ToolChip(p.name) { vm.applyPreset(t.id, p) } } }
    Text("INSTRUMENT", color = Orange, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        InstrumentLibrary.filter { it.waveform != null }.take(12).forEach { i -> VisualInstrument(t, t.kind == TrackKind.SYNTH && t.waveform == i.waveform, null, i.name) {
            vm.updateTrack(t.id) { it.copy(kind = if (it.kind == TrackKind.AUDIO || it.kind == TrackKind.LYRICS) it.kind else TrackKind.SYNTH, waveform = i.waveform!!) }; vm.trigger(i) } }
        GuitarModel.values().forEach { g -> VisualInstrument(t, t.kind == TrackKind.GUITAR && t.guitar == g, g, g.label) {
            vm.updateTrack(t.id) { it.copy(kind = TrackKind.GUITAR, guitar = g) }; vm.triggerTrackNote(t.copy(kind = TrackKind.GUITAR, guitar = g), if (g.label.contains("Bass")) 33 else 52) } }
    }
    Text("PEDALBOARD  IN ▸ … ▸ AMP (stomp to bypass, turn knobs live)", color = Orange, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
    Row(Modifier.horizontalScroll(rememberScrollState()).clip(RoundedCornerShape(8.dp)).background(Color(0xFF2B2B2B)).padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
        t.pedals.forEach { p -> VisualPedal(p, { v -> vm.updatePedal(t.id, p.id) { it.copy(amount = v) }; vm.session.tracks.firstOrNull { it.id == t.id }?.let { vm.triggerTrackNote(it, testPitch) } },
            { v -> vm.updatePedal(t.id, p.id) { it.copy(tone = v) }; vm.session.tracks.firstOrNull { it.id == t.id }?.let { vm.triggerTrackNote(it, testPitch) } },
            { vm.updatePedal(t.id, p.id) { it.copy(on = !it.on) }; vm.session.tracks.firstOrNull { it.id == t.id }?.let { vm.triggerTrackNote(it, testPitch) } }) {
            Row { SmallBtn("◀", "Move ${p.type.label} earlier") { vm.movePedal(t.id, p.id, -1) }; SmallBtn("🗑", "Remove ${p.type.label} pedal") { vm.removePedal(t.id, p.id); vm.status = "${p.type.label} pedal removed" }; SmallBtn("▶", "Move ${p.type.label} later") { vm.movePedal(t.id, p.id, 1) } } } }
        if (t.pedals.isNotEmpty()) ToolChip("Remove all pedals") { vm.updateTrack(t.id) { it.copy(pedals = emptyList()) }; vm.status = "All pedals removed" }
        Box { ToolChip("+ Pedal") { addMenu = true }
            DropdownMenu(addMenu, { addMenu = false }) { PedalType.values().forEach { p -> DropdownMenuItem({ Text(p.label) }, { vm.addPedal(t.id, p); addMenu = false }) } } }
    }
    Text("AMP", color = Orange, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
    Box { VisualAmp(t, { k, v -> live { tr -> when (k) { "Gain" -> tr.copy(ampGain = v); "Bass" -> tr.copy(ampBass = v); "Mid" -> tr.copy(ampMid = v); "Treble" -> tr.copy(ampTreble = v); "Presence" -> tr.copy(ampPresence = v); else -> tr.copy(ampMaster = v) } } }) { ampMenu = true }
        DropdownMenu(ampMenu, { ampMenu = false }) { AmpModel.values().forEach { a -> DropdownMenuItem({ Text(a.label) }, { live { it.copy(amp = a) }; ampMenu = false }) } } }
    Row { ToolChip("▶ Test E2") { vm.triggerTrackNote(t, 40) }; ToolChip("▶ Test A2") { vm.triggerTrackNote(t, 45) }; ToolChip("▶ Test E3") { vm.triggerTrackNote(t, 52) } }
    Text("Synthesized amp/pedal models (brand names describe the tone style; no licensed samples or IRs). Chain applies live, on playback and bounce.", color = Dim, fontSize = 10.sp)
}

// ---------------- Session dialog ----------------
@Composable
fun SessionDialog(vm: StudioViewModel) = Column {
    Text("Session", color = Orange, fontWeight = FontWeight.Bold, modifier = Modifier.padding(12.dp))
    val s = vm.session
    Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) { Text("Time sig", color = Color.White, modifier = Modifier.padding(end = 8.dp))
        listOf(3 to 4, 4 to 4, 6 to 8).forEach { (n, d) -> FilterChip(selected = s.timeSigNum == n && s.timeSigDen == d, onClick = { vm.update { it.copy(timeSigNum = n, timeSigDen = d) } }, label = { Text("$n/$d") }, modifier = Modifier.padding(end = 4.dp)) } }
    LabeledSlider("Delay time (beats)", s.delayTimeBeats, 0.25f..2f, String.format(Locale.US, "%.2f", s.delayTimeBeats)) { v -> vm.update { it.copy(delayTimeBeats = v) } }
    Box(Modifier.weight(1f)) { SessionScreen(vm) }
}

// ---------------- Tuner ----------------
@Composable
fun TunerPanel() {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    var reading by remember { mutableStateOf<Pair<String, Int>?>(null) }; var hz by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(granted) { if (!granted) { perm.launch(Manifest.permission.RECORD_AUDIO); return@LaunchedEffect }
        withContext(Dispatchers.IO) { val n = 4096
            @Suppress("MissingPermission") val rec = AudioRecord(MediaRecorder.AudioSource.MIC, SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT, maxOf(n * 4, AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)))
            val buf = FloatArray(n); rec.startRecording()
            try { while (isActive) { val r = rec.read(buf, 0, n, AudioRecord.READ_BLOCKING); if (r > 0) Tuner.detect(buf, r)?.let { f -> hz = f; reading = Tuner.describe(f) } } }
            finally { rec.stop(); rec.release() } } }
    Column(Modifier.padding(20.dp).width(280.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("TUNER", color = Orange, fontWeight = FontWeight.Bold)
        val (note, cents) = reading ?: ("—" to 0)
        Text(note, color = if (kotlin.math.abs(cents) < 5 && reading != null) Color(0xFF7CFC9A) else Color.White, fontSize = 48.sp, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        Text(if (reading != null) String.format(Locale.US, "%.1f Hz  %+d cents", hz, cents) else "Play a string…", color = Dim)
        Canvas(Modifier.fillMaxWidth().height(30.dp)) { val cx = size.width / 2; drawLine(Color.Gray, Offset(cx, 0f), Offset(cx, size.height), 2f)
            val x = cx + cents / 50f * cx; drawLine(Orange, Offset(x, 0f), Offset(x, size.height), 6f) }
    }
}

// ---------------- MIDI input ----------------
@Composable
fun rememberMidiInput(vm: StudioViewModel): Int {
    val ctx = LocalContext.current; var count by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val mm = if (ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_MIDI)) ctx.getSystemService(Context.MIDI_SERVICE) as? MidiManager else null
        val opened = ArrayList<java.io.Closeable>(); val h = Handler(Looper.getMainLooper())
        val receiver = object : MidiReceiver() { override fun onSend(msg: ByteArray, off: Int, cnt: Int, ts: Long) {
            var i = off; while (i + 2 < off + cnt + 1 && i + 2 <= off + cnt) { val st = msg[i].toInt() and 0xF0
                if (st == 0x90 && (msg[i + 2].toInt() and 127) > 0) { val p = msg[i + 1].toInt() and 127; val v = msg[i + 2].toInt() and 127; h.post { vm.midiNote(p, v) } }
                i += if (st == 0xC0 || st == 0xD0) 2 else 3 } } }
        fun open(info: MidiDeviceInfo) { if (info.outputPortCount == 0) return
            mm?.openDevice(info, { dev -> dev ?: return@openDevice; opened.add(dev); dev.openOutputPort(0)?.let { it.connect(receiver); opened.add(it); count++ } }, h) }
        @Suppress("DEPRECATION") mm?.devices?.forEach { open(it) }
        val cb = object : MidiManager.DeviceCallback() { override fun onDeviceAdded(d: MidiDeviceInfo) = open(d); override fun onDeviceRemoved(d: MidiDeviceInfo) { count = (count - 1).coerceAtLeast(0) } }
        @Suppress("DEPRECATION") mm?.registerDeviceCallback(cb, h)
        onDispose { @Suppress("DEPRECATION") mm?.unregisterDeviceCallback(cb); opened.forEach { runCatching { it.close() } } }
    }
    return count
}


// ---------------- Per-track on-screen MIDI keyboard ----------------
@Composable
fun TrackKeyboard(vm: StudioViewModel, t: Track, idx: Int) {
    var oct by remember(t.id) { mutableIntStateOf(if (t.name.contains("bass", true) || t.waveform.name.contains("SAW") && t.name.contains("303")) 2 else 4) }
    val armed = vm.armedTrackId == t.id
    fun closeKeyboard() { vm.keyboardTrackId = null }
    Column(Modifier.fillMaxWidth().background(Panel).border(1.dp, trackColor(idx))) {
        // Sticky exit row — always visible on every track type (synth, guitar, bass, audio, drums)
        Row(Modifier.fillMaxWidth().background(Chrome).padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.padding(end = 6.dp).height(40.dp).clip(RoundedCornerShape(6.dp)).background(Orange)
                    .clickable(onClickLabel = "Close keyboard and return to session") { closeKeyboard() }
                    .semantics { contentDescription = "Close keyboard"; role = Role.Button }
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Close, null, tint = Color.Black, modifier = Modifier.size(18.dp))
                    Text(" Done", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            }
            Box(Modifier.size(10.dp).background(trackColor(idx)))
            Text(" ${t.name} keys", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1)
            Text(if (armed && vm.isKeyboardRecording) "● REC" else if (armed) "armed" else "", color = Rec, fontSize = 11.sp)
            IconButton(onClick = { closeKeyboard() }, modifier = Modifier.size(40.dp).semantics { contentDescription = "Close keyboard" }) {
                Icon(Icons.Default.Close, "Close keyboard", tint = Color.White)
            }
        }
        Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            ToolChip("Oct −") { oct = (oct - 1).coerceAtLeast(0) }; Text("C$oct", color = Color.White, fontSize = 12.sp); ToolChip("Oct +") { oct = (oct + 1).coerceAtMost(7) }
            MSR("R", "Record arm ${t.name}", armed, Rec) { vm.armedTrackId = if (armed) null else t.id }
            ToolChip(if (vm.isKeyboardRecording && armed) "■ Stop rec" else "● Record") { vm.toggleTrackRecord(t.id) }
            ToolChip("✨ Polish") { vm.polishTrack(t.id) }
        }
        val s = vm.session; val key = remember(s.tracks) { SongAI.detectKey(s) }; val deg = SongAI.chordAt(s, key, vm.playhead)
        val prog = SongAI.progression(key); val nextDeg = prog[(prog.indexOf(deg) + 1) % prog.size]
        val hints = remember(vm.lastKeyPitch, deg, key) { SongAI.nextHints(s, vm.lastKeyPitch, vm.playhead).toSet() }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("AI ✦ ${key.name} · now ${SongAI.chordName(key, deg)} → next ${SongAI.chordName(key, nextDeg)}  ", color = Teal, fontSize = 11.sp)
            ToolChip("Write 4 bars (${SongAI.role(t).label})") { vm.aiGenerate(t.id) }
            SongAI.Role.values().filter { it != SongAI.role(t) }.forEach { r -> ToolChip(r.label) { vm.aiGenerate(t.id, 4, r) } }
            ToolChip("Regenerate") { vm.undo(); vm.aiGenerate(t.id) }
            Text("  Chords:", color = Dim, fontSize = 10.sp)
            prog.distinct().forEach { d -> ToolChip("+" + SongAI.chordName(key, d)) { vm.aiChordToTrack(t.id, d) } }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Sound: ", color = Dim, fontSize = 10.sp)
            FxPresets.forEach { p -> ToolChip(p.name) { vm.applyPreset(t.id, p) } }
        }
        var surface by remember(t.id) { mutableStateOf(if (t.kind == TrackKind.GUITAR) PlaySurface.STRINGS else PlaySurface.PIANO) }
        var surfMenu by remember { mutableStateOf(false) }
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("PLAY SURFACE ", color = Orange, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Box { ToolChip(surface.label + " ▾") { surfMenu = true }
                DropdownMenu(surfMenu, { surfMenu = false }) { PlaySurface.values().forEach { p -> DropdownMenuItem({ Text(p.label) }, { surface = p; surfMenu = false }) } } }
            Text(if (surface == PlaySurface.KIT || surface == PlaySurface.PADS) "  hits record into the drum loop" else "  notes record into ${t.name}", color = Dim, fontSize = 10.sp)
        }
        if (surface == PlaySurface.STRINGS) GuitarStrings(vm, t)
        if (surface == PlaySurface.KIT) { KitSelector(t.kit) { k -> vm.selectKit(t.id, k) }; DrumKitView(vm, t.kit, Modifier.fillMaxWidth().height(200.dp)) }
        if (surface == PlaySurface.PIANO) {
        // two octaves: white keys row with black keys overlaid
        val whites = listOf(0, 2, 4, 5, 7, 9, 11); val blackAfter = mapOf(0 to 1, 1 to 3, 3 to 6, 4 to 8, 5 to 10)
        BoxWithConstraints(Modifier.fillMaxWidth().height(120.dp).padding(2.dp)) {
            val ww = maxWidth / 14
            Row(Modifier.fillMaxSize()) { (0 until 14).forEach { w -> val pitch = 12 * (oct + 1) + 12 * (w / 7) + whites[w % 7]
                PianoKey(vm, t, pitch, false, hints, Modifier.width(ww).fillMaxHeight()) } }
            (0 until 14).forEach { w -> blackAfter[w % 7]?.let { semi -> val pitch = 12 * (oct + 1) + 12 * (w / 7) + semi
                PianoKey(vm, t, pitch, true, hints, Modifier.offset(x = ww * (w + 1) - ww * 0.3f).width(ww * 0.6f).fillMaxHeight(0.6f)) } }
        } }
        if (surface == PlaySurface.PADS) {
        KitSelector(t.kit) { k -> vm.selectKit(t.id, k) }
        Text("DRUM PADS (${t.kit.label}) – recorded into the step pattern while ● Record runs", color = Dim, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 6.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp)) { DrumPad.values().forEach { p ->
            Box(Modifier.padding(2.dp).size(64.dp, 40.dp).clip(RoundedCornerShape(4.dp)).background(if (p.ordinal % 2 == 0) Color(0xFF8A3E0E) else Color(0xFF0F5E57)).clickable { vm.triggerPad(p, t.kit) }
                .semantics { contentDescription = "${p.label} pad"; role = Role.Button }, contentAlignment = Alignment.Center) { Text(p.label, color = Color.White, fontSize = 10.sp) } } } }
        var rigOpen by remember(t.id) { mutableStateOf(true) }
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("RIG HOOKUP:  🎸 ${if (t.kind == TrackKind.GUITAR) t.guitar.label else t.waveform.label} ▸ ${t.pedals.count { it.on }} pedals ▸ ${t.amp.label} ▸ OUT", color = Teal, fontSize = 11.sp, modifier = Modifier.weight(1f), maxLines = 1)
            ToolChip(if (rigOpen) "Rig ▴" else "Rig ▾") { rigOpen = !rigOpen } }
        if (rigOpen) Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()).padding(horizontal = 6.dp)) {
            if (false) Text("INSTRUMENT INPUT", color = Orange, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            GearEditor(vm, vm.session.tracks.firstOrNull { it.id == t.id } ?: t)
        }
        } // scrollable body
    }
}

@Composable
fun PianoKey(vm: StudioViewModel, t: Track, pitch: Int, black: Boolean, hints: Set<Int>, modifier: Modifier) {
    var down by remember { mutableStateOf(false) }
    val name = "${Tuner.names[pitch % 12]}${pitch / 12 - 1}"
    val ivory = if (down) listOf(Color(0xFFD8D2C4), Color(0xFFBDB6A6)) else if (pitch in hints) listOf(Color(0xFFE9FBF8), Color(0xFF9FE8DF)) else listOf(Color(0xFFFFFFF8), Color(0xFFE6E2D8))
    val ebony = if (down) listOf(Color(0xFF000000), Color(0xFF2A2A2A)) else if (pitch in hints) listOf(Color(0xFF0F6E66), Color(0xFF06302C)) else listOf(Color(0xFF3A3A3A), Color(0xFF050505))
    Box(modifier.padding(horizontal = if (black) 0.dp else 0.5.dp).padding(top = if (down) 2.dp else 0.dp) // key dips when pressed
        .clip(RoundedCornerShape(bottomStart = if (black) 3.dp else 5.dp, bottomEnd = if (black) 3.dp else 5.dp))
        .background(androidx.compose.ui.graphics.Brush.verticalGradient(if (black) ebony else ivory))
        .border(0.5.dp, Color(0xFF555555), RoundedCornerShape(bottomStart = 5.dp, bottomEnd = 5.dp))
        .pointerInput(t.id, pitch) { detectTapGestures(onPress = { down = true; val st = vm.keyDown(t.id, pitch)
            tryAwaitRelease(); down = false; vm.keyUp(t.id, pitch, st) }) }
        .semantics { contentDescription = "$name on ${t.name}"; role = Role.Button }, contentAlignment = Alignment.BottomCenter) {
        if (!black && pitch % 12 == 0) Text(name, color = Color.DarkGray, fontSize = 9.sp)
    }
}


// ---------------- Lyrics track: AI lyric sheet + natural AI vocals ----------------
@Composable
fun LyricsPanel(vm: StudioViewModel, t: Track) {
    var theme by remember(t.id) { mutableStateOf("") }; var sheet by remember(t.id, t.lyrics) { mutableStateOf(t.lyrics) }
    Column(Modifier.fillMaxWidth().heightIn(max = 380.dp).background(Panel).border(1.dp, Orange).verticalScroll(rememberScrollState()).padding(6.dp)) {
        Row(Modifier.fillMaxWidth().background(Chrome).padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.padding(end = 6.dp).height(40.dp).clip(RoundedCornerShape(6.dp)).background(Orange)
                    .clickable(onClickLabel = "Close lyrics and return to session") {
                        if (sheet != t.lyrics) vm.updateTrack(t.id) { it.copy(lyrics = sheet) }; vm.lyricsTrackId = null
                    }
                    .semantics { contentDescription = "Close lyrics"; role = Role.Button }
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Close, null, tint = Color.Black, modifier = Modifier.size(18.dp))
                    Text(" Done", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            }
            Text("✎ ${t.name}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1f))
            IconButton(onClick = {
                if (sheet != t.lyrics) vm.updateTrack(t.id) { it.copy(lyrics = sheet) }; vm.lyricsTrackId = null
            }, modifier = Modifier.size(40.dp).semantics { contentDescription = "Close lyrics" }) {
                Icon(Icons.Default.Close, "Close lyrics", tint = Color.White)
            }
        }
        Text("AI thinks this song is: ${t.songInfo.ifBlank { "(tap Analyze)" }}", color = Teal, fontSize = 11.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(theme, { theme = it }, label = { Text("Theme (optional – blank lets AI invent)") }, singleLine = true, modifier = Modifier.weight(1f))
            ToolChip("Analyze") { vm.reanalyze(t.id) } }
        VocalLiveStrip(vm, t)
        Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            ToolChip("✦ AI song concept") { vm.inventConcept(t.id, theme) }
            ToolChip("✎ AI write lyrics") { vm.rewriteLyrics(t.id, theme) }
            ToolChip("🎤 AI write + sing") { vm.writeAndSing(t.id, theme) } }
        Text("Lyric sheet – edit freely. [Section] lines add a bar of space.", color = Dim, fontSize = 10.sp)
        OutlinedTextField(sheet, { sheet = it; vm.updateTrack(t.id) { tr -> tr.copy(lyrics = it) } }, modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp),
            textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 13.sp))
        Text("AI VOCALS – natural human voice from your phone's speech engine", color = Orange, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.horizontalScroll(rememberScrollState())) { VoiceGender.values().forEach { g -> FilterChip(t.voiceGender == g, { vm.updateTrack(t.id) { it.copy(voiceGender = g) } }, { Text(g.label) }, Modifier.padding(end = 4.dp)) } }
        Row(Modifier.horizontalScroll(rememberScrollState())) { VocalStyle.values().forEach { v -> FilterChip(t.vocalStyle == v, { vm.updateTrack(t.id) { it.copy(vocalStyle = v) } }, { Text(v.label) }, Modifier.padding(end = 4.dp)) } }
        Row(verticalAlignment = Alignment.CenterVertically) {
            ToolChip(if (t.clips.isEmpty()) "🎤 AI sing" else "🎤 AI re-sing") { vm.updateTrack(t.id) { it.copy(lyrics = sheet) }; vm.singLyrics(t.id) }
            ToolChip("Vocal Polish FX") { vm.applyPreset(t.id, FxPresets.first { it.name == "Vocal Polish" }) }
            Text("  ${vm.voiceInfo}", color = Dim, fontSize = 10.sp) }
        Text("Vocals start at the playhead, one line per 2 bars. Your phone's speech engine voices each line in rhythm onto this track – a natural voice, not pitched studio singing or a licensed vocal model. Pedals and amps on this track also process the vocal.", color = Dim, fontSize = 10.sp)
    }
}


/** Acoustic kit seen from the drummer's throne: cymbals up top, toms over the kick, hats left, snare front-left. Tap a piece to play it. */
@Composable
fun DrumKitView(vm: StudioViewModel, kit: DrumKit, modifier: Modifier) {
    val shell = when (kit) { DrumKit.MASK_METAL, DrumKit.METAL, DrumKit.DJENT -> Color(0xFF1A1A1A); DrumKit.PROG_ARENA -> Color(0xFFB8860B)
        DrumKit.TR808, DrumKit.VINTAGE909, DrumKit.ELECTRONIC, DrumKit.HOUSE, DrumKit.TRAP, DrumKit.DNB -> Color(0xFF444C5C); DrumKit.JAZZ_BRUSH, DrumKit.ACOUSTIC -> Color(0xFFB0782E); else -> Color(0xFF9E1E1E) }
    // (pad, x, y, diameter) in fractions of the area width/height
    val parts = listOf(Triple(DrumPad.CYMBAL, 0.12f to 0.05f, 0.24f), Triple(DrumPad.OPEN_HAT, 0.78f to 0.02f, 0.2f), Triple(DrumPad.CLOSED_HAT, 0.02f to 0.42f, 0.17f),
        Triple(DrumPad.HIGH_TOM, 0.36f to 0.08f, 0.14f), Triple(DrumPad.MID_TOM, 0.52f to 0.08f, 0.15f), Triple(DrumPad.LOW_TOM, 0.72f to 0.42f, 0.2f),
        Triple(DrumPad.SNARE, 0.2f to 0.5f, 0.17f), Triple(DrumPad.KICK, 0.4f to 0.38f, 0.26f), Triple(DrumPad.RIMSHOT, 0.18f to 0.84f, 0.09f),
        Triple(DrumPad.CLAP, 0.88f to 0.82f, 0.09f), Triple(DrumPad.COWBELL, 0.66f to 0.18f, 0.08f))
    BoxWithConstraints(modifier.background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xFF15161A), Color(0xFF2A2018)))).padding(4.dp)) {
        val W = maxWidth; val H = maxHeight
        parts.forEach { (p, pos, d) -> var hit by remember { mutableStateOf(false) }
            val cym = p == DrumPad.CYMBAL || p == DrumPad.OPEN_HAT || p == DrumPad.CLOSED_HAT
            val size = minOf(W * d, H * (d * 1.9f))
            Box(Modifier.offset(x = W * pos.first, y = H * pos.second).size(size).clip(androidx.compose.foundation.shape.CircleShape)
                .background(if (cym) androidx.compose.ui.graphics.Brush.radialGradient(listOf(Color(if (hit) 0xFFFFF3B0 else 0xFFE8C66A), Color(0xFF9C7A2A), Color(0xFFC9A34A)))
                    else androidx.compose.ui.graphics.Brush.radialGradient(listOf(if (hit) Color.White else Color(0xFFF2EFE6), Color(0xFFD9D4C6), shell, shell)))
                .border(if (cym) 1.dp else 3.dp, if (cym) Color(0xFF6E5520) else Color(0xFFBFBFBF), androidx.compose.foundation.shape.CircleShape)
                .pointerInput(p, kit) { detectTapGestures(onPress = { hit = true; vm.triggerPad(p, kit); tryAwaitRelease(); hit = false }) }
                .semantics { contentDescription = "${p.label} – ${kit.label}"; role = Role.Button }, contentAlignment = Alignment.Center) {
                Text(p.label, color = if (cym) Color(0xFF3A2A00) else Color(0xFF222222), fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
        }
        Text(if (vm.liveCapture && vm.isPlaying) "● capturing hits into the loop" else "Tap ● MIDI capture + Play to record hits into the loop", color = Dim, fontSize = 9.sp, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

/** Six-string fretboard play surface (standard tuning; bass tracks use the low 4). Tap a fret to play; recorded like keys. */
@Composable
fun GuitarStrings(vm: StudioViewModel, t: Track) {
    val bass = t.guitar.label.contains("Bass") || SongAI.role(t) == SongAI.Role.BASS
    val open = if (bass) listOf(43, 38, 33, 28) else listOf(64, 59, 55, 50, 45, 40)
    Column(Modifier.fillMaxWidth().background(Color(0xFF3B2414)).padding(vertical = 4.dp).horizontalScroll(rememberScrollState())) {
        open.forEachIndexed { si, o -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(Tuner.names[o % 12], color = Color(0xFFE8D9B5), fontSize = 10.sp, modifier = Modifier.width(18.dp))
            (0..12).forEach { f -> val pitch = o + f; var down by remember { mutableStateOf(false) }
                Box(Modifier.size(if (f == 0) 34.dp else (46 - f).dp, 26.dp).background(if (f == 0) Color(0xFF1E1E1E) else Color(0xFF4A2E18))
                    .border(1.dp, Color(0xFFC0C0C0).copy(alpha = if (f == 0) 0f else 0.8f))
                    .pointerInput(t.id, pitch) { detectTapGestures(onPress = { down = true; val st = vm.keyDown(t.id, pitch); tryAwaitRelease(); down = false; vm.keyUp(t.id, pitch, st) }) }
                    .semantics { contentDescription = "String ${si + 1} fret $f"; role = Role.Button }, contentAlignment = Alignment.Center) {
                    Box(Modifier.fillMaxWidth().height((1 + si * 0.4f).dp).background(if (down) Orange else Color(0xFFD8D8D8)))
                    if (si == open.size / 2 && f in listOf(3, 5, 7, 9)) Box(Modifier.size(6.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Color(0xFFEDE6D6)))
                    if (down) Box(Modifier.size(12.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Orange)) } } } }
    }
}
