package com.studiospark.app.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
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
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.*
import com.studiospark.app.core.*
import com.studiospark.app.core.Shape
import com.studiospark.app.ui.KitRender.drawKit
import com.studiospark.app.ui.KitRender.stage
import com.studiospark.app.ui.KitRender.thumb
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * The playable, photoreal kit stage. Multi-touch: every finger is its own stroke (simultaneous hits, both kicks, hat + snare).
 * In arrange mode a drag repositions the touched piece (linked zones follow); taps still select.
 */
@Composable fun KitStage(kit: DrumKit, view: KitView, modifier: Modifier = Modifier, selected: String? = null, arrange: Boolean = false,
                         onHit: (KitPiece, Int) -> Unit, onSelect: (String?) -> Unit = {}, onMoved: (String, Float, Float) -> Unit = { _, _, _ -> }) {
    var sz by remember { mutableStateOf(IntSize(1, 1)) }
    val placed = remember(kit, view, sz) { KitRender.layout(kit, view, sz.width.toFloat(), sz.height.toFloat()) }
    var flash by remember { mutableStateOf<String?>(null) }; var flashTick by remember { mutableIntStateOf(0) }
    var drag by remember { mutableStateOf<Pair<String, Offset>?>(null) }
    LaunchedEffect(flashTick) { delay(120); flash = null }
    val cur by rememberUpdatedState(kit); val curPlaced by rememberUpdatedState(placed); val curArrange by rememberUpdatedState(arrange)
    Box(modifier.onSizeChanged { sz = it }.semantics { contentDescription = "Drum kit, ${view.label} view" }
        .pointerInput(view) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val hit = KitRender.hit(cur, curPlaced, down.position, view)
                if (curArrange) {
                    val host = hit?.first?.let { p -> if (p.link.isNotEmpty() && p.role != "left") p.link else p.id }
                        ?: curPlaced.asReversed().firstOrNull { pl -> val dx = (down.position.x - pl.c.x) / pl.rx; val dy = (down.position.y - pl.c.y) / pl.ry; pl.shape != Shape.RUG && dx * dx + dy * dy <= 1.1f }?.piece?.id
                    onSelect(host); if (host == null) return@awaitEachGesture
                    var total = Offset.Zero
                    while (true) { val ev = awaitPointerEvent(); val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) break; total += ch.positionChange(); ch.consume(); drag = host to total }
                    if (total.getDistance() > 4f) { val p = cur.pieces.first { it.id == host }
                        val nx = if (view == KitView.OVERHEAD) p.x + total.x / size.width else p.x + total.x / (size.width * (0.82f + 0.38f * p.y))
                        val ny = if (view == KitView.OVERHEAD) p.y + total.y / (size.height * 0.88f) else p.y + total.y / (size.height * 0.52f)
                        onMoved(host, nx, ny) }
                    drag = null
                } else {
                    // play every pointer of this gesture as an independent strike
                    hit?.let { (p, v) -> onHit(p, v); onSelect(p.link.ifEmpty { p.id }); flash = p.id; flashTick++ }
                    val seen = mutableSetOf(down.id)
                    while (true) { val ev = awaitPointerEvent(); if (ev.changes.none { it.pressed }) break
                        for (ch in ev.changes) if (ch.pressed && ch.id !in seen) { seen += ch.id
                            KitRender.hit(cur, curPlaced, ch.position, view)?.let { (p, v) -> onHit(p, v); flash = p.id; flashTick++ } } }
                }
            }
        }) {
        Canvas(Modifier.fillMaxSize()) {
            stage(view)
            val d = drag
            val shown = if (d == null) placed else placed.map { pl -> if (pl.piece.id == d.first || pl.piece.link == d.first) Placed(pl.piece, pl.shape, pl.c + d.second, pl.rx, pl.ry, pl.order, pl.finish, pl.elevated) else pl }
            drawKit(kit, shown, view, selected, flash)
        }
        if (kit.pieces.isEmpty()) Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Blank kit", style = Type.title, color = Color.White); Text("Add a bass drum, snare, toms, cymbals, a pedal and a throne from the catalog below.", style = Type.small, color = Color(0xFFB8BCC0)) }
    }
}

@Composable fun DrumKitScreen(vm: StudioVM, landscape: Boolean) {
    val p = LocalPalette.current; @Suppress("UNUSED_VARIABLE") val r = vm.rev
    val track = vm.drumTrack(); val kit = vm.kitOf(track)
    var view by remember { mutableStateOf(KitView.OVERHEAD) }
    var arrange by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<String?>(null) }
    val recording = vm.transport == TransportState.RECORDING || vm.transport == TransportState.COUNT_IN
    Column(Modifier.fillMaxSize()) {
        // ---- toolbar
        Row(Modifier.fillMaxWidth().background(p.header).horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Dropdown("", "Start from…", listOf(DrumCatalog.blank) + DrumCatalog.starters, { if (it.id == "blank") "Blank kit — build it yourself" else "${it.name} · ${it.pieces.count { p -> p.sounding && p.role.isEmpty() }} voices" }) { vm.startKitFrom(it.id); selected = null }
            Row(Modifier.clip(RoundedCornerShape(4.dp)).border(1.dp, p.line, RoundedCornerShape(4.dp))) {
                KitView.values().forEach { v -> Box(Modifier.background(if (view == v) p.accent else p.raised).clickable { view = v }.padding(horizontal = 10.dp, vertical = 7.dp)) { Text(v.label, style = Type.small, color = if (view == v) Color.Black else p.text) } } }
            ToolButton(if (arrange) "Arrange: on" else "Arrange", icon = Icons.Default.OpenWith, active = arrange) { arrange = !arrange }
            ToolButton(if (recording) "Stop" else "Record", icon = if (recording) Icons.Default.Stop else Icons.Default.FiberManualRecord, active = recording, accent = p.rec) { vm.recordOnDrums() }
            ToolButton("Click", icon = Icons.Outlined.Timer, active = vm.mixer.session.metronome || vm.metronomeFree) { val on = !(vm.mixer.session.metronome || vm.metronomeFree); vm.setMetronome(on); vm.setFreeMetronome(on) }
            Dropdown("", "Sticks: ${kit.stick.label}", Stick.values().toList(), { it.label }) { vm.applyKit(kit.copy(stick = it)) }
            Dropdown("", "Shells: ${Finishes.find(kit.shellFinish)?.name}", Finishes.shells, { it.name }) { f -> vm.applyKit(kit.copy(shellFinish = f.id, pieces = kit.pieces.map { pc -> if (DrumCatalog.get(pc.catalogId)?.category == DrumCategory.DRUMS && DrumCatalog.get(pc.catalogId)?.finish.isNullOrEmpty()) pc.copy(finish = f.id) else pc })) }
            Dropdown("", "Cymbals: ${Finishes.find(kit.cymbalFinish)?.name}", Finishes.cymbals, { it.name }) { f -> vm.applyKit(kit.copy(cymbalFinish = f.id, pieces = kit.pieces.map { pc -> if (DrumCatalog.get(pc.catalogId)?.category == DrumCategory.CYMBALS) pc.copy(finish = f.id) else pc })) }
        }
        Divider(color = p.inset)
        val stageMod = Modifier.fillMaxWidth().aspectRatio(if (landscape) 2.1f else 1.35f).clip(RoundedCornerShape(0.dp))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState(), enabled = true)) {
            KitStage(kit, view, stageMod, selected, arrange, onHit = { pc, v -> vm.kitHit(pc, v) }, onSelect = { selected = it },
                onMoved = { id, x, y -> vm.applyKit(DrumCatalog.move(kit, id, x, y), "move") })
            // ---- status strip
            Row(Modifier.fillMaxWidth().background(p.panel).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) { Text(kit.name, style = Type.title, color = p.text, maxLines = 1)
                    val voices = kit.pieces.count { it.sounding }; val hw = kit.pieces.count { !it.sounding }
                    Text("$voices playable zones · $hw hardware · ${kit.stick.label} · plays on ${track?.name ?: "a new Drums track"}", style = Type.small, color = p.textFaint, maxLines = 1) }
                Meter(track?.let { vm.meters[it.id] } ?: 0f, Modifier.width(6.dp).height(30.dp))
                SaveKitButton(vm, kit)
            }
            Divider(color = p.inset)
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                kit.pieces.firstOrNull { it.id == selected }?.let { Inspector(vm, kit, it) { selected = null } }
                KitPieceChips(kit, selected) { selected = it }
                CatalogPanel(vm, kit) { added -> selected = added }
                Spacer(Modifier.height(90.dp))
            }
        }
    }
}

@Composable private fun SaveKitButton(vm: StudioVM, kit: DrumKit) {
    val p = LocalPalette.current; var open by remember { mutableStateOf(false) }; var name by remember(kit.id) { mutableStateOf(kit.name) }
    PrimaryButton("Save kit", icon = Icons.Default.Save) { name = kit.name; open = true }
    if (open) AlertDialog(onDismissRequest = { open = false }, containerColor = p.panel, title = { Text("Save kit", style = Type.title, color = p.text) },
        text = { Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(p.inset).padding(12.dp)) { BasicTextField(name, { name = it.take(40) }, textStyle = Type.body.copy(color = p.text), cursorBrush = SolidColor(p.accent), singleLine = true, modifier = Modifier.fillMaxWidth()) } },
        confirmButton = { PrimaryButton("Save") { vm.saveKitNamed(kit, name); open = false } }, dismissButton = { ToolButton("Cancel") { open = false } })
}

@Composable private fun KitPieceChips(kit: DrumKit, selected: String?, onSelect: (String) -> Unit) {
    val p = LocalPalette.current
    if (kit.pieces.isEmpty()) return
    Panel(Modifier.fillMaxWidth()) {
        SectionLabel("In this kit")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            kit.pieces.filter { it.role.isEmpty() || it.role == "left" }.forEach { pc -> val sel = pc.id == selected || kit.pieces.any { it.id == selected && it.link == pc.id }
                Column(Modifier.width(92.dp).clip(RoundedCornerShape(6.dp)).background(if (sel) p.raised else p.inset).border(1.dp, if (sel) p.accent else Color.Transparent, RoundedCornerShape(6.dp)).clickable { onSelect(pc.id) }.padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    val item = DrumCatalog.get(pc.catalogId)
                    Canvas(Modifier.size(54.dp)) { item?.let { thumb(it, pc.finish.ifEmpty { kit.shellFinish }) } }
                    Text(pc.name, style = Type.label.copy(letterSpacing = 0.sp), color = p.text, maxLines = 2) } }
        }
    }
}

@Composable private fun Inspector(vm: StudioVM, kit: DrumKit, piece: KitPiece, onClose: () -> Unit) {
    val p = LocalPalette.current
    val host = if (piece.link.isNotEmpty() && piece.role != "left") kit.pieces.firstOrNull { it.id == piece.link } ?: piece else piece
    val zones = listOf(host) + kit.pieces.filter { it.link == host.id && it.role != "left" }
    var zoneId by remember(host.id) { mutableStateOf(host.id) }
    val pc = kit.pieces.firstOrNull { it.id == zoneId } ?: host
    val item = DrumCatalog.get(host.catalogId); val status = DrumCatalog.statusOf(pc)
    fun upd(f: (KitPiece) -> KitPiece) = vm.applyKit(kit.copy(pieces = kit.pieces.map { if (it.id == pc.id) f(it) else it }), "insp${pc.id}")
    Panel(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(64.dp)) { item?.let { thumb(it, host.finish.ifEmpty { kit.shellFinish }) } }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) { Text(host.name, style = Type.title, color = p.text); Text(item?.let { "${it.category.label} · ${it.group}" } ?: "Kit piece", style = Type.small, color = p.textFaint)
                Pill(status.label, when (status) { SoundStatus.OWN -> p.play; SoundStatus.RETUNED, SoundStatus.TRIGGER -> p.solo; SoundStatus.SILENT -> p.textDim; SoundStatus.GAP -> p.rec }, Modifier.padding(top = 4.dp)) }
            IconBtn(Icons.Outlined.Delete, "Remove from kit") { vm.applyKit(DrumCatalog.remove(kit, host.id)); onClose() }
            IconBtn(Icons.Default.Close, "Close inspector") { onClose() }
        }
        if (zones.size > 1) { Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { zones.forEach { z -> ToolButton(when (z.role) { "edge" -> "Open edge"; "bell" -> "Bell"; "rim" -> "Rim"; "foot" -> "Foot"; else -> "Main" }, active = z.id == pc.id) { zoneId = z.id } } } }
        item?.note?.takeIf { it.isNotBlank() }?.let { Text(it, style = Type.small, color = p.textDim, modifier = Modifier.padding(top = 6.dp)) }
        if (pc.sounding) {
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Knob((pc.tune + 12) / 24, { v -> upd { it.copy(tune = v * 24 - 12) } }, "Tune", String.format("%+.1f st", pc.tune), size = 46.dp, default = 0.5f)
                Knob((pc.gainDb + 24) / 30, { v -> upd { it.copy(gainDb = v * 30 - 24) } }, "Level", String.format("%+.1f dB", pc.gainDb), size = 46.dp, default = 0.8f)
                Knob((pc.pan + 1) / 2, { v -> upd { it.copy(pan = (v * 2 - 1).let { x -> if (abs(x) < 0.03f) 0f else x }) } }, "Pan", if (abs(pc.pan) < 0.03f) "C" else if (pc.pan < 0) "L${(-pc.pan * 100).toInt()}" else "R${(pc.pan * 100).toInt()}", size = 46.dp, default = 0.5f)
                if (pc.role.isEmpty()) Knob(((host.size - 0.04f) / 0.26f).coerceIn(0f, 1f), { v -> vm.applyKit(kit.copy(pieces = kit.pieces.map { if (it.id == host.id || it.link == host.id) it.copy(size = 0.04f + v * 0.26f) else it }), "size${host.id}") }, "Size", "${(host.size / 0.0085f).toInt()}″", size = 46.dp)
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Dropdown("Sound", DrumSounds.types[pc.sample] ?: pc.sample, DrumSounds.types.entries.toList(), { it.value }) { e -> upd { it.copy(sample = e.key) } }
                Dropdown("Choke", if (pc.chokeGroup == 0) "None" else "Group ${pc.chokeGroup}", (0..12).toList(), { if (it == 0) "None" else "Group $it" }) { g -> upd { it.copy(chokeGroup = g) } }
                Text("MIDI ${midiName(pc.midi)} (${pc.midi})", style = Type.mono, color = p.textFaint)
                ToolButton("Audition", icon = Icons.Default.VolumeUp) { vm.auditionSample(pc.sample, pc.tune, pc.gainDb) }
            }
        }
        val fins = if (item?.category == DrumCategory.CYMBALS) Finishes.cymbals else if (item?.category == DrumCategory.DRUMS || item == null && DrumCatalog.shapeOf(host) != Shape.CRASH) Finishes.shells else emptyList()
        if (fins.isNotEmpty()) { Spacer(Modifier.height(8.dp)); Dropdown("Finish", Finishes.find(host.finish)?.name ?: "Kit default", fins, { it.name }) { f -> vm.applyKit(kit.copy(pieces = kit.pieces.map { if (it.id == host.id) it.copy(finish = f.id) else it }), "fin${host.id}") } }
    }
}

@Composable private fun CatalogPanel(vm: StudioVM, kit: DrumKit, onAdded: (String) -> Unit) {
    val p = LocalPalette.current
    var cat by remember { mutableStateOf(DrumCategory.CYMBALS) }
    var group by remember(cat) { mutableStateOf<String?>(null) }
    Panel(Modifier.fillMaxWidth()) {
        SectionLabel("Catalog") { Text("Original designs · in-house sounds", style = Type.label, color = p.textFaint) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Dropdown("Category", cat.label, DrumCategory.values().toList(), { "${it.label} (${if (it == DrumCategory.STARTERS) DrumCatalog.starters.size + 1 else DrumCatalog.inCategory(it).size})" }) { cat = it }
            if (cat != DrumCategory.STARTERS) Dropdown("Type", group ?: "All", listOf<String?>(null) + DrumCatalog.groups(cat), { it ?: "All" }) { group = it }
        }
        Spacer(Modifier.height(10.dp))
        if (cat == DrumCategory.STARTERS) {
            (listOf(DrumCatalog.blank) + DrumCatalog.starters).forEach { s -> CatalogRow(title = s.name, sub = if (s.id == "blank") "Empty stage — add every piece yourself" else "${s.pieces.count { it.role.isEmpty() && it.sounding }} drums & cymbals · ${Finishes.find(s.shellFinish)?.name} · ${s.stick.label}",
                badge = null, thumb = { Canvas(Modifier.size(56.dp).clip(RoundedCornerShape(4.dp))) { stage(KitView.OVERHEAD); drawKit(s, KitRender.layout(s, KitView.OVERHEAD, size.width, size.height), KitView.OVERHEAD, null, null) } },
                audition = null, addLabel = "Start", onAdd = { vm.startKitFrom(s.id) }) }
        } else DrumCatalog.inCategory(cat).filter { group == null || it.group == group }.forEach { item ->
            CatalogRow(title = item.name, sub = listOfNotNull(item.group, Finishes.find(item.finish)?.name, item.note.takeIf { it.isNotBlank() }).joinToString(" · "), badge = item.status,
                thumb = { Canvas(Modifier.size(56.dp)) { thumb(item, kit.shellFinish) } },
                audition = if (item.sounding) ({ vm.auditionSample(item.sample, item.tune, item.gainDb) }) else if (item.category == DrumCategory.PEDALS) ({ kit.pieces.firstOrNull { DrumCatalog.shapeOf(it) == Shape.KICK }?.let { vm.auditionSample(it.sample, it.tune, it.gainDb) } ?: vm.auditionSample("kick-0", 0f, 0f) }) else null,
                addLabel = "Add", onAdd = { val before = kit.pieces.map { it.id }.toSet(); val k = vm.applyKit(DrumCatalog.addTo(kit, item)); k.pieces.firstOrNull { it.id !in before && it.role.isEmpty() }?.let { onAdded(it.id) }
                    vm.message = "${item.name} added" + if (item.status == SoundStatus.GAP) " — visual only, no sample yet" else "" })
        }
    }
}

@Composable private fun CatalogRow(title: String, sub: String, badge: SoundStatus?, thumb: @Composable () -> Unit, audition: (() -> Unit)?, addLabel: String, onAdd: () -> Unit) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(6.dp)).background(p.inset).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF1B140E)), contentAlignment = Alignment.Center) { thumb() }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) { Text(title, style = Type.bodyStrong, color = p.text, maxLines = 1); Text(sub, style = Type.small, color = p.textFaint, maxLines = 2)
            badge?.let { Text(it.label.uppercase(), style = Type.label.copy(fontSize = 8.sp), color = when (it) { SoundStatus.OWN -> p.play; SoundStatus.RETUNED, SoundStatus.TRIGGER -> p.solo; SoundStatus.SILENT -> p.textDim; SoundStatus.GAP -> p.rec }) } }
        audition?.let { IconBtn(Icons.Default.PlayArrow, "Audition $title") { it() } }
        Spacer(Modifier.width(4.dp)); ToolButton(addLabel, icon = Icons.Default.Add) { onAdd() }
    }
}
