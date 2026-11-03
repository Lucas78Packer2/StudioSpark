package com.aimusicstudio.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.sin

val Orange = Color(0xFFF5A623); val Teal = Color(0xFF22C7B8); val Bg = Color(0xFF0B0B0C); val Panel = Color(0xFF1C1D20)

/** Port of AI_Music_StudioApp.swift entry point. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Orange, secondary = Teal, background = Bg, surface = Panel, onPrimary = Color.Black)) {
                Surface(Modifier.fillMaxSize(), color = Bg) { DawApp() }
            }
        }
    }
}

enum class Tab(val label: String, val icon: ImageVector) {
    SOUNDS("Sounds", Icons.Default.LibraryMusic), INSTRUMENTS("Instruments", Icons.Default.Piano), ARRANGE("Arrange", Icons.Default.ViewTimeline),
    MIX("Mix", Icons.Default.Tune), RECORD("Record", Icons.Default.Mic), SESSION("Session", Icons.Default.Folder)
}

fun fmt(t: Double) = String.format(Locale.US, "%d:%04.1f", (t / 60).toInt(), t % 60)

@Composable
fun StudioApp(vm: StudioViewModel = viewModel()) {
    var tab by rememberSaveable { mutableStateOf(Tab.ARRANGE) }
    Scaffold(
        containerColor = Bg,
        topBar = { Header(vm) },
        bottomBar = {
            NavigationBar(containerColor = Panel) {
                Tab.values().forEach { t ->
                    NavigationBarItem(selected = tab == t, onClick = { tab = t }, icon = { Icon(t.icon, null) }, label = { Text(t.label, fontSize = 11.sp) },
                        colors = NavigationBarItemDefaults.colors(selectedIconColor = Color.Black, indicatorColor = Orange))
                }
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                Tab.SOUNDS -> SoundsScreen(vm); Tab.INSTRUMENTS -> InstrumentsScreen(vm); Tab.ARRANGE -> ArrangeScreen(vm)
                Tab.MIX -> MixScreen(vm); Tab.RECORD -> RecordScreen(vm); Tab.SESSION -> SessionScreen(vm)
            }
        }
    }
}

@Composable
fun Header(vm: StudioViewModel) {
    val s = vm.session
    Column(Modifier.fillMaxWidth().background(Panel).statusBarsPadding().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(s.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f).semantics { heading() })
            Text("${s.tempoBPM} BPM", color = Teal)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.stop() }, Modifier.size(48.dp)) { Icon(Icons.Default.Stop, "Stop and return to start", tint = Color.White) }
            IconButton(onClick = { if (vm.isPlaying) vm.pause() else vm.play() }, Modifier.size(56.dp).clip(RoundedCornerShape(28.dp)).background(Orange)) {
                Icon(if (vm.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, if (vm.isPlaying) "Pause" else "Play", tint = Color.Black)
            }
            IconToggleButton(checked = s.isLoopEnabled, onCheckedChange = { on -> vm.update { it.copy(isLoopEnabled = on, loopEnd = if (it.loopEnd <= it.loopStart) it.barSeconds else it.loopEnd) } }, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.Repeat, if (s.isLoopEnabled) "Loop on" else "Loop off", tint = if (s.isLoopEnabled) Teal else Color.Gray)
            }
            Text(fmt(vm.playhead), color = Color.White, fontSize = 16.sp, modifier = Modifier.padding(horizontal = 8.dp).semantics { contentDescription = "Playhead ${fmt(vm.playhead)}" })
            Spacer(Modifier.weight(1f))
            MasterMeter(vm.level, Modifier.width(120.dp).height(40.dp))
        }
        if (vm.status.isNotEmpty()) Text(vm.status, color = Color.LightGray, fontSize = 12.sp, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    }
}

/** Waveform + level bar, inspired by the orange-teal mixer artwork. */
@Composable
fun MasterMeter(level: Float, modifier: Modifier) {
    val pct = (level * 100).roundToInt()
    Canvas(modifier.clip(RoundedCornerShape(6.dp)).background(Color.Black).semantics {
        contentDescription = "Master level $pct percent"; liveRegion = LiveRegionMode.Polite
        progressBarRangeInfo = ProgressBarRangeInfo(level, 0f..1f)
    }) {
        val w = size.width; val h = size.height
        for (x in 0 until w.toInt() step 3) {
            val a = (sin(x * 0.15f) * 0.5f + 0.5f) * level * h / 2
            drawLine(Teal, Offset(x.toFloat(), h / 2 - a), Offset(x.toFloat(), h / 2 + a), 2f)
        }
        drawRect(Orange, topLeft = Offset(0f, h - 6f), size = androidx.compose.ui.geometry.Size(w * level, 6f))
    }
}

@Composable
fun SectionTitle(t: String) = Text(t, color = Orange, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.padding(vertical = 8.dp).semantics { heading() })

// ---------------- Sounds ----------------
@Composable
fun SoundsScreen(vm: StudioViewModel) {
    var q by remember { mutableStateOf("") }; var cat by remember { mutableStateOf("All") }
    val cats = listOf("All") + vm.library.map { it.category }.distinct().sorted()
    val items = vm.library.filter { (cat == "All" || it.category == cat) && it.name.contains(q, true) }
    Column(Modifier.padding(12.dp)) {
        OutlinedTextField(q, { q = it }, label = { Text("Search sounds") }, leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp)) {
            cats.forEach { c -> FilterChip(selected = cat == c, onClick = { cat = c }, label = { Text(c) }, modifier = Modifier.padding(end = 6.dp)) }
        }
        Text("${items.size} sounds", color = Color.Gray, fontSize = 12.sp, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        LazyColumn {
            items(items, key = { it.id }) { item ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(10.dp)).background(Panel).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { vm.preview(item) }, Modifier.size(48.dp)) { Icon(Icons.Default.PlayCircle, "Preview ${item.name}", tint = Teal, modifier = Modifier.size(36.dp)) }
                    Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                        Text(item.name, color = Color.White, fontWeight = FontWeight.SemiBold)
                        Text("${item.category} · ${item.formatLabel} · ${String.format(Locale.US, "%.1f", item.duration)} s", color = Color.Gray, fontSize = 12.sp)
                    }
                    TextButton(onClick = { vm.addSampleToTrack(item, null) }, Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Add ${item.name} to selected track" }) { Text("Add to track") }
                }
            }
        }
    }
}

// ---------------- Instruments ----------------
@Composable
fun InstrumentsScreen(vm: StudioViewModel) {
    var selected by remember { mutableStateOf(InstrumentLibrary.first { it.waveform != null }) }
    var octave by remember { mutableIntStateOf(4) }
    Column(Modifier.padding(12.dp).verticalScroll(rememberScrollState())) {
        SectionTitle("Drum Kit — tap to trigger")
        val drums = InstrumentLibrary.filter { it.pad != null }
        drums.chunked(4).forEach { row ->
            Row { row.forEach { i ->
                Box(Modifier.weight(1f).padding(4.dp).height(72.dp).clip(RoundedCornerShape(10.dp)).background(Brushish(i.pad!!.ordinal))
                    .clickable(onClickLabel = "Trigger") { vm.trigger(i) }.semantics { contentDescription = "${i.name} pad"; role = Role.Button }, contentAlignment = Alignment.Center) {
                    Text(i.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }; repeat(4 - row.size) { Spacer(Modifier.weight(1f)) } }
        }
        SectionTitle("Synths")
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            InstrumentLibrary.filter { it.waveform != null }.forEach { i ->
                FilterChip(selected = selected == i, onClick = { selected = i; vm.trigger(i) }, label = { Text(i.name) }, modifier = Modifier.padding(end = 6.dp).heightIn(min = 48.dp))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Octave $octave", color = Color.White, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            IconButton(onClick = { octave = (octave - 1).coerceAtLeast(1) }) { Icon(Icons.Default.Remove, "Octave down", tint = Color.White) }
            IconButton(onClick = { octave = (octave + 1).coerceAtMost(7) }) { Icon(Icons.Default.Add, "Octave up", tint = Color.White) }
        }
        val names = listOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")
        Row(Modifier.fillMaxWidth().height(120.dp)) {
            (0 until 12).forEach { k -> val sharp = names[k].length > 1; val pitch = 12 * (octave + 1) + k
                Box(Modifier.weight(1f).fillMaxHeight().padding(1.dp).background(if (sharp) Color(0xFF222222) else Color(0xFFEFEFEF))
                    .clickable { vm.trigger(selected, pitch) }.semantics { contentDescription = "${names[k]}$octave key"; role = Role.Button },
                    contentAlignment = Alignment.BottomCenter) { Text(names[k], color = if (sharp) Color.White else Color.Black, fontSize = 10.sp) }
            }
        }
        Button(onClick = { vm.addInstrumentToSession(selected, 12 * (octave + 1)) }, Modifier.padding(top = 8.dp).heightIn(min = 48.dp)) { Text("Add ${selected.name} note to a track") }
        DrumGrid(vm)
    }
}

fun Brushish(i: Int) = if (i % 2 == 0) Color(0xFF8A3E0E) else Color(0xFF0F5E57)

@Composable
fun DrumGrid(vm: StudioViewModel) {
    val p = vm.session.drumPattern
    Row(verticalAlignment = Alignment.CenterVertically) {
        SectionTitle("16-step Drum Pattern"); Spacer(Modifier.weight(1f))
        Text("On", color = Color.White); Switch(p.isEnabled, { on -> vm.update { it.copy(drumPattern = it.drumPattern.copy(isEnabled = on)) } }, Modifier.semantics { contentDescription = "Drum pattern enabled" })
    }
    Column(Modifier.horizontalScroll(rememberScrollState())) {
        DrumPad.values().forEach { pad ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(pad.label, color = Color.White, fontSize = 12.sp, modifier = Modifier.width(80.dp))
                p.steps[pad]!!.forEachIndexed { i, on ->
                    Box(Modifier.padding(2.dp).size(32.dp).clip(RoundedCornerShape(4.dp))
                        .background(if (on) Orange else if (i % 4 == 0) Color(0xFF2B3A3E) else Color(0xFF1F2A2D))
                        .semantics { contentDescription = "${pad.label} step ${i + 1}"; stateDescription = if (on) "on" else "off"; role = Role.Checkbox }
                        .clickable { vm.update { it.copy(drumPattern = it.drumPattern.toggled(pad, i)) } })
                }
            }
        }
    }
}

// ---------------- Arrange (timeline) ----------------
@Composable
fun ArrangeScreen(vm: StudioViewModel) {
    val s = vm.session; var zoom by remember { mutableFloatStateOf(60f) } // dp per second
    val hs = rememberScrollState()
    Column(Modifier.padding(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { vm.addTrack(TrackKind.AUDIO) }, Modifier.heightIn(min = 48.dp)) { Text("+ Audio") }
            Spacer(Modifier.width(6.dp))
            Button(onClick = { vm.addTrack(TrackKind.SYNTH) }, Modifier.heightIn(min = 48.dp)) { Text("+ Synth") }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { zoom = (zoom / 1.5f).coerceAtLeast(15f) }) { Icon(Icons.Default.ZoomOut, "Zoom out", tint = Color.White) }
            IconButton(onClick = { zoom = (zoom * 1.5f).coerceAtMost(400f) }) { Icon(Icons.Default.ZoomIn, "Zoom in", tint = Color.White) }
        }
        val totalW = (s.length * zoom + 40).dp
        LazyColumn {
            item {
                Row { Spacer(Modifier.width(110.dp))
                    Box(Modifier.horizontalScroll(hs).width(totalW).height(24.dp).clickable { }.semantics { contentDescription = "Timeline ruler" }) {
                        Canvas(Modifier.fillMaxSize()) {
                            val bars = (s.length / s.barSeconds).toInt() + 1
                            for (b in 0..bars) { val x = (b * s.barSeconds * zoom).toFloat() * density; drawLine(Color.Gray, Offset(x, 0f), Offset(x, size.height), 2f) }
                            if (s.isLoopEnabled) drawRect(Teal.copy(alpha = 0.3f), Offset((s.loopStart * zoom).toFloat() * density, 0f), androidx.compose.ui.geometry.Size(((s.loopEnd - s.loopStart) * zoom).toFloat() * density, size.height))
                            val px = (vm.playhead * zoom).toFloat() * density; drawLine(Orange, Offset(px, 0f), Offset(px, size.height), 4f)
                        }
                    }
                }
            }
            items(s.tracks, key = { it.id }) { t -> TimelineRow(vm, t, zoom, totalW, hs) }
        }
        Slider(value = vm.playhead.toFloat().coerceIn(0f, s.length.toFloat()), onValueChange = { vm.seek(it.toDouble()) }, valueRange = 0f..s.length.toFloat(),
            modifier = Modifier.semantics { contentDescription = "Seek playhead"; stateDescription = fmt(vm.playhead) })
        vm.selectedTrackId?.let { id -> s.tracks.firstOrNull { it.id == id && it.kind == TrackKind.SYNTH }?.let { PianoRollLite(vm, it) } }
    }
}

@Composable
fun TimelineRow(vm: StudioViewModel, t: Track, zoom: Float, totalW: androidx.compose.ui.unit.Dp, hs: androidx.compose.foundation.ScrollState) {
    val sel = vm.selectedTrackId == t.id
    Row(Modifier.padding(vertical = 3.dp).height(64.dp)) {
        Column(Modifier.width(110.dp).fillMaxHeight().background(if (sel) Color(0xFF2A3A3E) else Panel)
            .clickable(onClickLabel = "Select track") { vm.selectedTrackId = t.id }.padding(4.dp)
            .semantics(mergeDescendants = true) { stateDescription = if (sel) "selected" else "not selected" }) {
            Text(t.name, color = Color.White, fontSize = 12.sp, maxLines = 1)
            Text(if (t.kind == TrackKind.SYNTH) "Synth · ${t.waveform.label}" else "Audio", color = Color.Gray, fontSize = 10.sp)
        }
        Box(Modifier.horizontalScroll(hs).width(totalW).fillMaxHeight().background(Color(0xFF111A1C))) {
            t.clips.forEach { c ->
                var menu by remember { mutableStateOf(false) }
                Box(Modifier.offset(x = (c.startTime * zoom).dp).width((c.duration * zoom).dp.coerceAtLeast(12.dp)).fillMaxHeight().padding(2.dp)
                    .clip(RoundedCornerShape(6.dp)).background(Teal.copy(alpha = 0.7f)).border(1.dp, Teal, RoundedCornerShape(6.dp))
                    .clickable(onClickLabel = "Edit clip") { menu = true }
                    .semantics {
                        contentDescription = "Clip ${SampleLibrary.displayName(c.fileName)} at ${fmt(c.startTime)}, ${String.format(Locale.US, "%.1f", c.duration)} seconds"
                        customActions = listOf(
                            CustomAccessibilityAction("Move earlier") { vm.moveClip(t.id, c.id, -vm.session.stepSeconds * 4); true },
                            CustomAccessibilityAction("Move later") { vm.moveClip(t.id, c.id, vm.session.stepSeconds * 4); true },
                            CustomAccessibilityAction("Delete clip") { vm.deleteClip(t.id, c.id); true })
                    }) {
                    Text(SampleLibrary.displayName(c.fileName), fontSize = 10.sp, color = Color.Black, modifier = Modifier.padding(4.dp))
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem({ Text("Move ◀ 1 beat") }, { vm.moveClip(t.id, c.id, -vm.session.stepSeconds * 4) })
                        DropdownMenuItem({ Text("Move ▶ 1 beat") }, { vm.moveClip(t.id, c.id, vm.session.stepSeconds * 4) })
                        DropdownMenuItem({ Text("Shorten 1 beat") }, { vm.trimClip(t.id, c.id, -vm.session.stepSeconds * 4) })
                        DropdownMenuItem({ Text("Lengthen 1 beat") }, { vm.trimClip(t.id, c.id, vm.session.stepSeconds * 4) })
                        DropdownMenuItem({ Text("Delete clip") }, { vm.deleteClip(t.id, c.id); menu = false })
                    }
                }
            }
            Canvas(Modifier.fillMaxSize()) {
                t.notes.forEach { n -> val y = size.height * (1 - ((n.pitch - 36).coerceIn(0, 48) / 48f))
                    drawRect(Orange, Offset((n.startTime * zoom).toFloat() * density, y - 3f), androidx.compose.ui.geometry.Size((n.duration * zoom).toFloat() * density, 6f)) }
            }
        }
    }
}

/** Piano-roll lite: 16 steps × one octave of SynthNotes for the selected synth track. */
@Composable
fun PianoRollLite(vm: StudioViewModel, t: Track) {
    val names = listOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")
    SectionTitle("Piano roll — ${t.name}")
    Column(Modifier.horizontalScroll(rememberScrollState()).verticalScroll(rememberScrollState()).heightIn(max = 260.dp)) {
        (71 downTo 60).forEach { pitch ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(names[pitch % 12] + (pitch / 12 - 1), color = Color.White, fontSize = 11.sp, modifier = Modifier.width(36.dp))
                (0 until 16).forEach { step ->
                    val on = t.notes.any { it.pitch == pitch && kotlin.math.abs(it.startTime - step * vm.session.stepSeconds) < 1e-6 }
                    Box(Modifier.padding(1.dp).size(28.dp, 20.dp).background(if (on) Orange else if (step % 4 == 0) Color(0xFF2B3A3E) else Color(0xFF1F2A2D))
                        .semantics { contentDescription = "${names[pitch % 12]} step ${step + 1}"; stateDescription = if (on) "on" else "off"; role = Role.Checkbox }
                        .clickable { vm.toggleNote(t.id, pitch, step) })
                }
            }
        }
    }
}

// ---------------- Mix ----------------
@Composable
fun MixScreen(vm: StudioViewModel) {
    val s = vm.session
    LazyColumn(Modifier.padding(12.dp)) {
        items(s.tracks, key = { it.id }) { t -> ChannelStrip(vm, t) }
        item {
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).clip(RoundedCornerShape(12.dp)).background(Panel).padding(12.dp)) {
                SectionTitle("Master")
                MasterMeter(vm.level, Modifier.fillMaxWidth().height(56.dp))
                LabeledSlider("Master volume", s.masterVolume, 0f..1f, "${(s.masterVolume * 100).roundToInt()} percent") { v -> vm.update { it.copy(masterVolume = v) } }
                Text("Mix changes apply the next time you press Play.", color = Color.Gray, fontSize = 12.sp)
            }
        }
    }
}

@Composable
fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, valueText: String, steps: Int = 0, onChange: (Float) -> Unit) {
    Column {
        Text("$label: $valueText", color = Color.White, fontSize = 13.sp)
        Slider(value, onChange, valueRange = range, steps = steps, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = label; stateDescription = valueText },
            colors = SliderDefaults.colors(thumbColor = Orange, activeTrackColor = Orange))
    }
}

@Composable
fun ChannelStrip(vm: StudioViewModel, t: Track) {
    var fx by remember { mutableStateOf(false) }
    val audible = MixRenderer.audible(t, vm.session.tracks)
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(12.dp)).background(Panel).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(t.name, color = if (audible) Color.White else Color.Gray, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).semantics { heading() })
            FilterChip(selected = t.isMuted, onClick = { vm.updateTrack(t.id) { it.copy(isMuted = !it.isMuted) } }, label = { Text("M") },
                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Mute ${t.name}"; stateDescription = if (t.isMuted) "muted" else "not muted"; role = Role.Switch })
            Spacer(Modifier.width(6.dp))
            FilterChip(selected = t.isSoloed, onClick = { vm.updateTrack(t.id) { it.copy(isSoloed = !it.isSoloed) } }, label = { Text("S") },
                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Solo ${t.name}"; stateDescription = if (t.isSoloed) "soloed" else "not soloed"; role = Role.Switch })
            IconButton(onClick = { vm.removeTrack(t.id) }, Modifier.size(48.dp)) { Icon(Icons.Default.Delete, "Remove track ${t.name}", tint = Color.Gray) }
        }
        LabeledSlider("${t.name} volume", t.volume, 0f..1f, "${(t.volume * 100).roundToInt()} percent") { v -> vm.updateTrack(t.id) { it.copy(volume = v) } }
        val panText = when { t.pan < -0.02f -> "${(-t.pan * 100).roundToInt()} left"; t.pan > 0.02f -> "${(t.pan * 100).roundToInt()} right"; else -> "center" }
        LabeledSlider("${t.name} pan", t.pan, -1f..1f, panText) { v -> vm.updateTrack(t.id) { it.copy(pan = v) } }
        if (t.kind == TrackKind.SYNTH) Row(Modifier.horizontalScroll(rememberScrollState())) {
            SynthWaveform.values().forEach { w -> FilterChip(selected = t.waveform == w, onClick = { vm.updateTrack(t.id) { it.copy(waveform = w) } }, label = { Text(w.label) }, modifier = Modifier.padding(end = 4.dp)) }
        }
        TextButton(onClick = { fx = !fx }, Modifier.heightIn(min = 48.dp).semantics { stateDescription = if (fx) "expanded" else "collapsed" }) { Text(if (fx) "Hide EQ & compressor" else "Show EQ & compressor") }
        if (fx) {
            fun db(v: Float) = String.format(Locale.US, "%+.1f dB", v)
            LabeledSlider("Low EQ", t.eq.lowGain, -12f..12f, db(t.eq.lowGain)) { v -> vm.updateTrack(t.id) { it.copy(eq = it.eq.copy(lowGain = v)) } }
            LabeledSlider("Mid EQ", t.eq.midGain, -12f..12f, db(t.eq.midGain)) { v -> vm.updateTrack(t.id) { it.copy(eq = it.eq.copy(midGain = v)) } }
            LabeledSlider("High EQ", t.eq.highGain, -12f..12f, db(t.eq.highGain)) { v -> vm.updateTrack(t.id) { it.copy(eq = it.eq.copy(highGain = v)) } }
            val c = t.compressor
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Compressor", color = Color.White, modifier = Modifier.weight(1f))
                Switch(c.isEnabled, { on -> vm.updateTrack(t.id) { it.copy(compressor = it.compressor.copy(isEnabled = on)) } }, Modifier.semantics { contentDescription = "Compressor on ${t.name}" })
            }
            if (c.isEnabled) {
                LabeledSlider("Threshold", c.threshold, -40f..0f, db(c.threshold)) { v -> vm.updateTrack(t.id) { it.copy(compressor = it.compressor.copy(threshold = v)) } }
                LabeledSlider("Ratio", c.ratio, 1f..20f, String.format(Locale.US, "%.1f to 1", c.ratio)) { v -> vm.updateTrack(t.id) { it.copy(compressor = it.compressor.copy(ratio = v)) } }
                LabeledSlider("Attack", c.attackMs, 1f..100f, "${c.attackMs.roundToInt()} ms") { v -> vm.updateTrack(t.id) { it.copy(compressor = it.compressor.copy(attackMs = v)) } }
                LabeledSlider("Release", c.releaseMs, 20f..1000f, "${c.releaseMs.roundToInt()} ms") { v -> vm.updateTrack(t.id) { it.copy(compressor = it.compressor.copy(releaseMs = v)) } }
                LabeledSlider("Makeup gain", c.makeupGain, 0f..24f, db(c.makeupGain)) { v -> vm.updateTrack(t.id) { it.copy(compressor = it.compressor.copy(makeupGain = v)) } }
            }
        }
    }
}

// ---------------- Record ----------------
@Composable
fun RecordScreen(vm: StudioViewModel) {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it; if (it) vm.startRecording() }
    Column(Modifier.padding(16.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        SectionTitle("Record a take")
        Text(ctx.getString(R.string.mic_rationale), color = Color.Gray, fontSize = 13.sp)
        Spacer(Modifier.height(16.dp))
        MasterMeter(vm.level, Modifier.fillMaxWidth().height(80.dp))
        Spacer(Modifier.height(24.dp))
        Button(onClick = { if (vm.isRecording) vm.stopRecording() else if (granted) vm.startRecording() else launcher.launch(Manifest.permission.RECORD_AUDIO) },
            colors = ButtonDefaults.buttonColors(containerColor = if (vm.isRecording) Color.Red else Orange), modifier = Modifier.size(120.dp),
            shape = RoundedCornerShape(60.dp)) {
            Icon(if (vm.isRecording) Icons.Default.Stop else Icons.Default.FiberManualRecord, if (vm.isRecording) "Stop recording" else "Start recording", modifier = Modifier.size(48.dp))
        }
        Text(if (vm.isRecording) "Recording…" else "Takes are saved to the sound library and added as a new track at the playhead.", color = Color.White,
            modifier = Modifier.padding(top = 12.dp).semantics { liveRegion = LiveRegionMode.Polite })
    }
}

// ---------------- Session ----------------
@Composable
fun SessionScreen(vm: StudioViewModel) {
    val s = vm.session
    Column(Modifier.padding(12.dp).verticalScroll(rememberScrollState())) {
        SectionTitle("Studio session")
        OutlinedTextField(s.name, { n -> vm.update { it.copy(name = n) } }, label = { Text("Session name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        LabeledSlider("Tempo", s.tempoBPM.toFloat(), 40f..240f, "${s.tempoBPM} BPM") { v -> vm.update { it.copy(tempoBPM = v.roundToInt()) } }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Loop region", color = Color.White, modifier = Modifier.weight(1f))
            Switch(s.isLoopEnabled, { on -> vm.update { it.copy(isLoopEnabled = on, loopEnd = if (it.loopEnd <= it.loopStart) it.barSeconds else it.loopEnd) } }, Modifier.semantics { contentDescription = "Loop enabled" })
        }
        LabeledSlider("Loop start", s.loopStart.toFloat(), 0f..s.length.toFloat(), fmt(s.loopStart)) { v -> vm.update { it.copy(loopStart = minOf(v.toDouble(), it.loopEnd)) } }
        LabeledSlider("Loop end", s.loopEnd.toFloat().coerceAtMost(s.length.toFloat()), 0f..s.length.toFloat(), fmt(s.loopEnd)) { v -> vm.update { it.copy(loopEnd = maxOf(v.toDouble(), it.loopStart)) } }
        LabeledSlider("Master volume", s.masterVolume, 0f..1f, "${(s.masterVolume * 100).roundToInt()} percent") { v -> vm.update { it.copy(masterVolume = v) } }
        Row(Modifier.padding(vertical = 8.dp)) {
            Button(onClick = { vm.save() }, Modifier.heightIn(min = 48.dp)) { Text("Save") }; Spacer(Modifier.width(8.dp))
            Button(onClick = { vm.newSession() }, Modifier.heightIn(min = 48.dp)) { Text("New") }; Spacer(Modifier.width(8.dp))
            Button(onClick = { vm.bounce() }, Modifier.heightIn(min = 48.dp)) { Text("Bounce mix to WAV") }
        }
        SectionTitle("Saved sessions")
        vm.sessions.forEach { x ->
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClickLabel = "Open session") { vm.open(x.id) }.padding(8.dp)
                .semantics(mergeDescendants = true) { stateDescription = if (x.id == s.id) "open" else "" }, verticalAlignment = Alignment.CenterVertically) {
                Text(x.name + if (x.id == s.id) "  (open)" else "", color = Color.White, modifier = Modifier.weight(1f))
                Text("${x.tracks.size} tracks", color = Color.Gray, fontSize = 12.sp)
                IconButton(onClick = { vm.deleteSession(x.id) }) { Icon(Icons.Default.Delete, "Delete session ${x.name}", tint = Color.Gray) }
            }
        }
    }
}
