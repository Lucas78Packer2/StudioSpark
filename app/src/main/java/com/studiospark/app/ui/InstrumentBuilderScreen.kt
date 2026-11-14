package com.studiospark.app.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.*
import com.studiospark.app.core.*
import com.studiospark.app.dsp.*
import com.studiospark.app.ui.GuitarRender.drawInstrument
import kotlinx.coroutines.delay

/** Playable photoreal instrument: tap the drawn neck to fret a note, tap over the pickups to pluck an open string. */
@Composable fun InstrumentStage(b: StringBuild, modifier: Modifier, onNote: (pitch: Int, vel: Int, string: Int) -> Unit) {
    var sz by remember { mutableStateOf(IntSize(1, 1)) }
    val ringing = remember { mutableStateMapOf<Int, Long>() }; var now by remember { mutableLongStateOf(0L) }
    LaunchedEffect(ringing.size, ringing.values.maxOrNull()) { while (ringing.isNotEmpty()) { now = System.currentTimeMillis(); ringing.entries.removeAll { now - it.value > 900 }; delay(16) } }
    val tuning = (Tunings.find(b.tuning) ?: Tunings.find(StringCatalog.defaultTuning(b.body, b.bass, b.stringCount)))!!.midi
    val cur by rememberUpdatedState(b); val curTuning by rememberUpdatedState(tuning)
    Box(modifier.onSizeChanged { sz = it }.background(Brush.verticalGradient(listOf(Color(0xFF2A2420), Color(0xFF15110E)))).semantics { contentDescription = "${b.name} — playable" }
        .pointerInput(Unit) { awaitEachGesture { val d = awaitFirstDown(requireUnconsumed = false); val g = GuitarRender.geo(cur, Offset(size.width * 0.02f, size.height * 0.04f), size.width * 0.96f, size.height * 0.92f)
            val seen = HashSet<Int>()
            fun strike(o: Offset) { GuitarRender.hit(g, o, cur)?.let { (i, fret) -> if (i in seen) return; seen += i; val pitch = curTuning.getOrElse(i) { curTuning.last() } + fret; onNote(pitch, 96, i); ringing[i] = System.currentTimeMillis(); now = ringing[i]!! } }
            strike(d.position)
            // drag across strings = strum
            while (true) { val ev = awaitPointerEvent(); val ch = ev.changes.firstOrNull { it.id == d.id } ?: break; if (!ch.pressed) break; strike(ch.position) } } }) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Brush.radialGradient(listOf(Color(0x33FFD27A), Color.Transparent), Offset(size.width * 0.35f, size.height * 0.4f), size.width * 0.5f))
            drawInstrument(b, Offset(size.width * 0.02f, size.height * 0.04f), size.width * 0.96f, size.height * 0.92f, ringing.toMap(), now)
        }
    }
}

@Composable fun InstrumentBuilderScreen(vm: StudioVM, landscape: Boolean, bass: Boolean) {
    val p = LocalPalette.current; @Suppress("UNUSED_VARIABLE") val r = vm.rev
    val b = vm.buildOf(bass); val track = vm.stringTrack(bass); val snd = StringCatalog.sound(b)
    val tuning = Tunings.find(b.tuning)?.midi ?: Tunings.find(StringCatalog.defaultTuning(b.body, bass, b.stringCount))!!.midi
    val recording = vm.transport == TransportState.RECORDING || vm.transport == TransportState.COUNT_IN
    val word = if (bass) "bass" else "guitar"
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().background(p.header).horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Dropdown("", "Start from…", listOf(StringCatalog.blank(bass)) + StringCatalog.starters(bass), { if (it.starterId == "blank") "From scratch — build it yourself" else "${it.name} · ${StringCatalog.part(it.body)?.name}" }) { vm.startBuildFrom(it.id, bass) }
            ToolButton(if (recording) "Stop" else "Record", icon = if (recording) Icons.Default.Stop else Icons.Default.FiberManualRecord, active = recording, accent = p.rec) { vm.recordOn(vm.ensureStringTrack(bass)) }
            ToolButton("Click", icon = Icons.Outlined.Timer, active = vm.mixer.session.metronome || vm.metronomeFree) { val on = !(vm.mixer.session.metronome || vm.metronomeFree); vm.setMetronome(on); vm.setFreeMetronome(on) }
            Dropdown("", "Tuning: ${b.tuning.ifEmpty { "Standard" }.substringBefore(" (")}", Tunings.guitar.plus(Tunings.bass).filter { (it.name.startsWith("Bass")) == bass }, { it.name }) { t -> vm.applyBuild(StringCatalog.apply(b, StringCatalog.tunings.first { it.name == t.name })) }
            SaveBuildButton(vm, b)
            val sameFamily = vm.project.tracks.filter { it.family == (if (bass) Family.BASS else Family.GUITAR) }
            if (b.saved) Dropdown("", "Use on track…", sameFamily, { it.name }) { t -> vm.setInstrument(t, StringCatalog.toInstrument(b)); vm.message = "${b.name} now plays on ${t.name}" }
            if (b.saved) ToolButton("Jam Room", icon = Icons.Default.Groups) { vm.sendToRoom(StringCatalog.toInstrument(b)) }
        }
        Divider(color = p.inset)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            InstrumentStage(b, Modifier.fillMaxWidth().aspectRatio(if (landscape) 2.8f else 2.0f)) { pitch, vel, _ -> vm.stringNote(pitch, vel, bass) }
            GuitarStrings(tuning, if (bass) 12 else 15, Modifier.fillMaxWidth().height(if (bass) 120.dp else 150.dp), bass = bass) { pitch, vel -> vm.stringNote(pitch, vel, bass) }
            Row(Modifier.fillMaxWidth().background(p.panel).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) { Text(b.name + when { b.saved -> ""; vm.project.build(b.id) == null -> " · starter"; else -> " · unsaved draft" }, style = Type.title, color = p.text, maxLines = 1)
                    Text("${StringCatalog.part(b.body)?.name} · ${StringCatalog.part(b.pickups)?.name} · ${b.stringCount} strings · plays on ${track?.name ?: "a new ${word.replaceFirstChar { it.uppercase() }} track"}", style = Type.small, color = p.textFaint, maxLines = 2) }
                Meter(track?.let { vm.meters[it.id] } ?: 0f, Modifier.width(6.dp).height(30.dp))
            }
            Divider(color = p.inset)
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ControlsPanel(vm, b)
                RigHookup(vm, b, track)
                SoundPanel(b, snd)
                PartCatalog(vm, b)
                Spacer(Modifier.height(90.dp))
            }
        }
    }
}

@Composable private fun ControlsPanel(vm: StudioVM, b: StringBuild) {
    val pos = StringCatalog.positions(b)
    Panel(Modifier.fillMaxWidth()) {
        SectionLabel("Controls")
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Knob(b.tone, { v -> vm.applyBuild(b.copy(tone = v), "tone") }, "Tone", "${(b.tone * 10).toInt()}", size = 50.dp, default = 0.6f)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { Text("PICKUP SELECTOR", style = Type.label, color = LocalPalette.current.textFaint)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) { pos.forEachIndexed { i, n -> ToolButton(n, active = b.pickupPos == i) { vm.applyBuild(b.copy(pickupPos = i)) } } } }
        }
    }
}

@Composable private fun RigHookup(vm: StudioVM, b: StringBuild, track: Track?) {
    val p = LocalPalette.current
    Panel(Modifier.fillMaxWidth()) {
        SectionLabel("Amp & pedals — live") { Text("stomps → amp → cab → rack", style = Type.label, color = p.textFaint) }
        val presets = (RigPresets.factory + vm.userPresets()).filter { it.family == null || it.family == (if (b.bass) Family.BASS else Family.GUITAR) || it.family == Family.GUITAR }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Dropdown("Rig", track?.rig?.presetName?.ifBlank { null } ?: b.rigPreset.ifEmpty { "Choose a rig" }, presets, { it.name }) { vm.setBuildRig(b, it.name) }
            PrimaryButton("Open full rig", icon = Icons.Default.Tune) { val t = vm.ensureStringTrack(b.bass); vm.openUpload("rig", t) }
        }
        Spacer(Modifier.height(8.dp))
        val slots = track?.rig?.slots ?: emptyList()
        if (slots.isEmpty()) Text("Dry DI — pick a rig to plug this ${if (b.bass) "bass" else "guitar"} into pedals, an amp and a cab.", style = Type.small, color = p.textFaint)
        else Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            Text("IN", style = Type.label, color = p.textFaint)
            slots.forEachIndexed { i, s -> Text(" → ", color = p.textFaint)
                Column(Modifier.clip(RoundedCornerShape(5.dp)).background(if (s.enabled) p.raised else p.inset).border(1.dp, if (s.enabled) p.accent.copy(alpha = 0.6f) else p.line, RoundedCornerShape(5.dp))
                    .clickable { track?.let { t -> vm.setTrack(t.id) { rig.slots.getOrNull(i)?.let { it.enabled = !it.enabled } } } }.padding(horizontal = 8.dp, vertical = 5.dp)) {
                    Text(s.gear?.name ?: s.gearId, style = Type.small, color = if (s.enabled) p.text else p.textFaint, maxLines = 1)
                    Text("${s.gear?.kind?.label ?: ""} · ${if (s.enabled) "on" else "bypassed"}", style = Type.label.copy(letterSpacing = 0.sp), color = p.textFaint) } }
            Text(" → OUT", style = Type.label, color = p.textFaint)
        }
        Text("Tap a block to bypass it. The same rig follows this instrument on its track and through Record; in the Jam Room it plugs into the cabled amp chain.", style = Type.small, color = p.textDim, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable private fun SoundPanel(b: StringBuild, s: StringCatalog.Sound) {
    val p = LocalPalette.current
    Panel(Modifier.fillMaxWidth()) {
        SectionLabel("Sound source")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pill(s.status.label, statusColor(s.status), Modifier)
            Spacer(Modifier.width(8.dp))
            Text(when (s.voice) { "nylon_like" -> "In-house modeled acoustic voice"; "bass" -> "Owned CC0 electric bass recordings (24 samples)"; else -> "Owned CC0 electric guitar recordings (36 samples)" } + " · tone filter ${(s.brightness * 100).toInt()}%", style = Type.small, color = p.textDim)
        }
        s.notes.forEach { Text("⚠ $it", style = Type.small, color = p.rec, modifier = Modifier.padding(top = 4.dp)) }
    }
}

@Composable fun statusColor(s: VoiceStatus): Color { val p = LocalPalette.current; return when (s) { VoiceStatus.SAMPLED -> p.play; VoiceStatus.VOICED, VoiceStatus.RETUNED, VoiceStatus.MODELED -> p.solo; VoiceStatus.VISUAL -> p.textDim; VoiceStatus.GAP -> p.rec } }

@Composable private fun SaveBuildButton(vm: StudioVM, b: StringBuild) {
    val p = LocalPalette.current; var open by remember { mutableStateOf(false) }; var name by remember(b.id) { mutableStateOf(b.name) }
    PrimaryButton(if (b.saved) "Save changes" else "Save ${if (b.bass) "bass" else "guitar"}", icon = Icons.Default.Save) { name = b.name; open = true }
    if (open) AlertDialog(onDismissRequest = { open = false }, containerColor = p.panel, title = { Text("Save to your instruments", style = Type.title, color = p.text) },
        text = { Column { Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(p.inset).padding(12.dp)) { BasicTextField(name, { name = it.take(40) }, textStyle = Type.body.copy(color = p.text), cursorBrush = SolidColor(p.accent), singleLine = true, modifier = Modifier.fillMaxWidth()) }
            Text("Saved instruments appear on every track picker, the Jam Room, Record, upload/place and the Instruments gallery.", style = Type.small, color = p.textFaint, modifier = Modifier.padding(top = 8.dp)) } },
        confirmButton = { PrimaryButton("Save") { vm.saveBuildNamed(b, name); open = false } }, dismissButton = { ToolButton("Cancel") { open = false } })
}

@Composable private fun PartCatalog(vm: StudioVM, b: StringBuild) {
    val p = LocalPalette.current
    val cats = PartCategory.values().toList()
    var cat by remember { mutableStateOf(PartCategory.BODY) }
    var group by remember(cat) { mutableStateOf<String?>(null) }
    Panel(Modifier.fillMaxWidth()) {
        SectionLabel("Catalog") { Text("Original designs · owned sounds", style = Type.label, color = p.textFaint) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Dropdown("Category", cat.label, cats, { "${it.label} (${if (it == PartCategory.STARTERS) StringCatalog.starters(b.bass).size + 1 else StringCatalog.forInstrument(it, b.bass).size})" }) { cat = it }
            if (cat != PartCategory.STARTERS) Dropdown("Type", group ?: "All", listOf<String?>(null) + StringCatalog.groups(cat, b.bass), { it ?: "All" }) { group = it }
        }
        Spacer(Modifier.height(10.dp))
        if (cat == PartCategory.STARTERS) (listOf(StringCatalog.blank(b.bass)) + StringCatalog.starters(b.bass)).forEach { s ->
            PartRow(s, if (s.starterId == "blank") "From scratch" else s.name, if (s.starterId == "blank") "Plain natural body, no pickups — add everything yourself" else "${StringCatalog.part(s.body)?.name} · ${StringCatalog.part(s.pickups)?.name} · ${s.rigPreset.ifEmpty { "dry" }}", StringCatalog.sound(s).status, false, "Start") { vm.startBuildFrom(s.id, b.bass) } }
        else StringCatalog.forInstrument(cat, b.bass).filter { group == null || it.group == group }.forEach { part ->
            val preview = StringCatalog.apply(b, part)
            val selected = when (cat) { PartCategory.BODY -> b.body == part.id; PartCategory.FINISH -> b.finish == part.id; PartCategory.PICKUPS -> b.pickups == part.id; PartCategory.NECK -> b.fretboard == part.id || b.inlay == part.id
                PartCategory.HARDWARE -> b.bridge == part.id || b.hardware == part.id || b.guard == part.id; PartCategory.STRINGS -> b.strings == part.name; PartCategory.TUNING -> b.tuning == part.name; else -> false }
            PartRow(preview, part.name, listOf(part.group, part.note).filter { it.isNotBlank() }.joinToString(" · "), part.status, selected, if (selected) "In use" else "Use") { vm.applyBuild(StringCatalog.apply(b, part)) }
        }
        if (StringCatalog.gaps().any { it.bass == null || it.bass == b.bass }) {
            Spacer(Modifier.height(8.dp)); Text("Sonic gaps: " + StringCatalog.gaps().filter { it.bass == null || it.bass == b.bass }.joinToString(" · ") { it.name }, style = Type.small, color = p.rec)
        }
    }
}

@Composable private fun PartRow(preview: StringBuild, title: String, sub: String, status: VoiceStatus, selected: Boolean, action: String, onUse: () -> Unit) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(6.dp)).background(if (selected) p.raised else p.inset).border(1.dp, if (selected) p.accent else Color.Transparent, RoundedCornerShape(6.dp)).clickable { onUse() }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.width(120.dp).height(46.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF1B1612))) { drawInstrument(preview, Offset.Zero, size.width, size.height) }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) { Text(title, style = Type.bodyStrong, color = p.text, maxLines = 1); Text(sub, style = Type.small, color = p.textFaint, maxLines = 2)
            Text(status.label.uppercase(), style = Type.label.copy(fontSize = 8.sp), color = statusColor(status)) }
        ToolButton(action, active = selected) { onUse() }
    }
}
