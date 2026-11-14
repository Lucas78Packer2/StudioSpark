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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.*
import androidx.compose.ui.layout.onSizeChanged
import com.studiospark.app.core.*
import com.studiospark.app.dsp.*
import kotlin.math.*

/** Studio = Pro Tools-style Edit window (pinned, always visible) + Mix window toggle + scrollable stack below (master board, selected track, rig, playable instrument). */
@Composable fun StudioScreen(vm: StudioVM, landscape: Boolean) {
    val p = LocalPalette.current
    // Scroll positions are hoisted above the orientation branch (and saveable), so rotating never loses them or leaves a dead scroller.
    val detailScroll = rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) }
    val laneScroll = rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) }
    val detail: @Composable ColumnScope.() -> Unit = {
        if (!vm.chromeHidden) MasterBoard(vm)
        vm.selectedTrack()?.let { TrackDetail(vm, it) } ?: if (vm.project.tracks.isEmpty())
            EmptyState("Basic studio — nothing loaded yet", "Add a guitar, bass, drum kit, keys, a mic track, or amps and pedals when you need them. Your saved kits, guitars and basses are in the list too.",
                action = { PrimaryButton("Add instrument or gear", icon = Icons.Default.Add) { vm.galleryOpen = true } })
        else EmptyState("No track selected", "Tap a track header to select it.")
        Spacer(Modifier.height(80.dp))
    }
    if (landscape) {
        // Landscape: the edit window takes the full remaining height on the left; the track detail scrolls on the right.
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.weight(0.6f).fillMaxHeight()) {
                EditToolbar(vm)
                Box(Modifier.fillMaxWidth().weight(1f)) { if (vm.mixWindow) MixWindow(vm) else EditWindow(vm, laneScroll) }
            }
            Box(Modifier.width(2.dp).fillMaxHeight().background(p.inset))
            Column(Modifier.weight(0.4f).fillMaxHeight().verticalScroll(detailScroll).padding(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = detail)
        }
    } else BoxWithConstraints(Modifier.fillMaxSize()) {
        // Portrait: size the edit window from the space actually left under the app chrome, never from the screen height.
        val editH = StudioLayout.editHeight(maxHeight)
        Column(Modifier.fillMaxSize()) {
            EditToolbar(vm)
            Box(Modifier.fillMaxWidth().height(editH)) { if (vm.mixWindow) MixWindow(vm) else EditWindow(vm, laneScroll) }
            Divider(color = p.inset, thickness = 2.dp)
            Column(Modifier.weight(1f).verticalScroll(detailScroll).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = detail)
        }
    }
}

/** Layout maths kept pure so it is unit-testable. */
object StudioLayout {
    val TOOLBAR = 45.dp
    /** Edit window height for [avail] (space under the app chrome, toolbar included): ~45%, at least 150 dp, and always leaving 120 dp for the detail panel. */
    fun editHeight(avail: Dp): Dp { val usable = avail - TOOLBAR; val want = (usable * 0.5f).coerceIn(150.dp, 420.dp); return minOf(want, (usable - 120.dp).coerceAtLeast(96.dp)) }
}

@Composable private fun EditToolbar(vm: StudioVM) {
    val p = LocalPalette.current; val t = vm.selectedTrack(); val c = t?.clips?.firstOrNull { it.id == vm.selectedClipId }
    Row(Modifier.fillMaxWidth().background(p.header).horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.clip(RoundedCornerShape(4.dp)).border(1.dp, p.line, RoundedCornerShape(4.dp))) {
            listOf(false to "Edit", true to "Mix").forEach { (m, l) -> Box(Modifier.background(if (vm.mixWindow == m) p.accent else p.raised).clickable { vm.mixWindow = m }.padding(horizontal = 12.dp, vertical = 7.dp)) { Text(l, style = Type.small, color = if (vm.mixWindow == m) Color.Black else p.text) } } }
        ToolButton("Upload", icon = Icons.Default.Upload) { vm.openUpload("import", null) }
        ToolButton("Track", icon = Icons.Default.Add) { vm.galleryOpen = true }
        if (!vm.mixWindow) {
            IconBtn(Icons.Default.ZoomOut, "Zoom out") { vm.zoom = (vm.zoom / 1.4f).coerceAtLeast(0.15f) }
            IconBtn(Icons.Default.ZoomIn, "Zoom in") { vm.zoom = (vm.zoom * 1.4f).coerceAtMost(8f) }
            if (vm.mixer.session.mode == RecordMode.PUNCH) { ToolButton("In ⟵") { vm.edit { punchIn = snap(vm.position, samplesPerBeat) } }; ToolButton("⟶ Out") { vm.edit { punchOut = snap(vm.position, samplesPerBeat) } } }
            ToolButton("Loop 4 bars here") { vm.edit { val s = snap(vm.position, samplesPerBar).let { if (it > vm.position) it - samplesPerBar else it }.coerceAtLeast(0); loopStart = s; loopEnd = s + samplesPerBar * 4 } }
            if (t != null && c != null) {
                Box(Modifier.width(1.dp).height(24.dp).background(p.line))
                ToolButton("Split", icon = Icons.Default.ContentCut) { vm.splitClip(t, c, vm.position) }
                ToolButton("Duplicate", icon = Icons.Default.ContentCopy) { vm.duplicateClip(t, c) }
                ToolButton("Silence trim", icon = Icons.Default.ContentCut) { vm.silenceTrimClip(t, c) }
                val fades = listOf(0L to "none", ENGINE_SR / 100L to "10 ms", vm.project.samplesPerBeat to "1 beat", vm.project.samplesPerBar to "1 bar")
                Dropdown("", "Fade in: ${fades.firstOrNull { it.first == c.fadeIn }?.second ?: "custom"}", fades, { it.second }) { vm.setClipFade(t, c, fadeIn = it.first) }
                Dropdown("", "Fade out: ${fades.firstOrNull { it.first == c.fadeOut }?.second ?: "custom"}", fades, { it.second }) { vm.setClipFade(t, c, fadeOut = it.first) }
                ToolButton("Trim −1 bar") { vm.trimClip(t, c, -1) }; ToolButton("Trim +1 bar") { vm.trimClip(t, c, 1) }
                ToolButton("Stretch ×2") { vm.stretchClip(t, c, 2f) }; ToolButton("Shrink ½") { vm.stretchClip(t, c, 0.5f) }
                ToolButton("Polish", icon = Icons.Default.AutoFixHigh) { vm.polishClip(t, c) }
                ToolButton("Tone", icon = Icons.Default.Tune) { vm.openUpload("rig", t) }
                IconBtn(Icons.Outlined.Delete, "Delete clip") { vm.deleteClip(t, c) }
            }
        }
        Box(Modifier.width(1.dp).height(24.dp).background(p.line))
        Dropdown("", "${vm.project.tracks.size} tracks", listOf(8, 16, 24, 32), { "$it-track session" }) { vm.applyTrackCount(it) }
        ToolButton("Bounce", icon = Icons.Default.SaveAlt) { vm.exportMix() }
        ToolButton("MIDI out", icon = Icons.Default.Piano) { vm.exportMidi() }
    }
    Divider(color = p.inset)
}

private val HEADER_W = 172.dp
private val LANE_H = 68.dp

@Composable private fun EditWindow(vm: StudioVM, vScroll: ScrollState) {
    val p = LocalPalette.current; val proj = vm.project; @Suppress("UNUSED_VARIABLE") val r = vm.rev
    val density = LocalDensity.current
    val pxPerBeat = with(density) { 56.dp.toPx() } * vm.zoom
    // horizontal timeline position lives in the VM so it survives rotation and layout swaps
    var scrollX by vm::timelineScrollX
    val spb = proj.samplesPerBeat.toFloat()
    // follow playhead
    val playX = vm.position / spb * pxPerBeat
    var viewW by remember { mutableFloatStateOf(1000f) }
    LaunchedEffect(vm.position / (proj.samplesPerBar * 2), vm.transport) { if (vm.transport != TransportState.STOPPED && (playX > scrollX + viewW * 0.9f || playX < scrollX)) scrollX = (playX - viewW * 0.1f).coerceAtLeast(0f) }
    Column(Modifier.fillMaxSize().background(p.timeline)) {
        // ruler row
        Row(Modifier.fillMaxWidth().height(30.dp)) {
            Box(Modifier.width(HEADER_W).fillMaxHeight().background(p.header).padding(horizontal = 10.dp), contentAlignment = Alignment.CenterStart) { Text("BARS | BEATS", style = Type.label, color = p.textFaint) }
            Ruler(vm, pxPerBeat, scrollX, { scrollX = it }, Modifier.weight(1f).fillMaxHeight())
        }
        Divider(color = p.inset)
        Row(Modifier.fillMaxSize().verticalScroll(vScroll)) {
            Column(Modifier.width(HEADER_W)) { proj.tracks.forEach { t -> TrackHeader(vm, t) } ; AddTrackRow(vm) }
            Box(Modifier.weight(1f).height(LANE_H * proj.tracks.size + 44.dp).onWidth { viewW = it }) {
                Lanes(vm, pxPerBeat, scrollX) { scrollX = it }
            }
        }
    }
}

private fun Modifier.onWidth(f: (Float) -> Unit) = this.then(Modifier.onSizeChanged { f(it.width.toFloat()) })

@Composable private fun Ruler(vm: StudioVM, pxPerBeat: Float, scrollX: Float, setScroll: (Float) -> Unit, modifier: Modifier) {
    val p = LocalPalette.current; val proj = vm.project; val tm = rememberTextMeasurer(); val sx by rememberUpdatedState(scrollX)
    Canvas(modifier.background(p.header).semantics { contentDescription = "Timeline ruler" }
        .pointerInput(pxPerBeat) { detectTapGestures { o -> vm.locate(((o.x + sx) / pxPerBeat * proj.samplesPerBeat).toLong().coerceAtLeast(0)) } }
        .pointerInput(pxPerBeat) { detectHorizontalDragGestures { ch, d -> ch.consume(); setScroll((sx - d).coerceAtLeast(0f)) } }) {
        val beatsPerBar = proj.sigNum * 4f / proj.sigDen
        val firstBeat = (scrollX / pxPerBeat).toInt(); val lastBeat = ((scrollX + size.width) / pxPerBeat).toInt() + 1
        // loop / punch ranges
        val spb = proj.samplesPerBeat.toFloat()
        if (proj.loopEnd > proj.loopStart) { val x0 = proj.loopStart / spb * pxPerBeat - scrollX; val x1 = proj.loopEnd / spb * pxPerBeat - scrollX
            drawRect(if (vm.mixer.session.loopPlayback) p.play.copy(alpha = 0.35f) else p.textFaint.copy(alpha = 0.2f), Offset(x0, 0f), Size(x1 - x0, 6f)) }
        if (proj.punchOut > proj.punchIn) { val x0 = proj.punchIn / spb * pxPerBeat - scrollX; val x1 = proj.punchOut / spb * pxPerBeat - scrollX; drawRect(p.rec.copy(alpha = if (vm.mixer.session.mode == RecordMode.PUNCH) 0.5f else 0.15f), Offset(x0, size.height - 6f), Size(x1 - x0, 6f)) }
        for (b in firstBeat..lastBeat) { val x = b * pxPerBeat - scrollX; val isBar = (b % beatsPerBar.toInt().coerceAtLeast(1)) == 0
            drawLine(if (isBar) p.textDim else p.textFaint.copy(alpha = 0.5f), Offset(x, if (isBar) 8f else size.height * 0.6f), Offset(x, size.height), 1f)
            if (isBar && x + 4f < size.width && (pxPerBeat * beatsPerBar > 40 || (b / beatsPerBar.toInt()) % 4 == 0)) drawText(tm, "${b / beatsPerBar.toInt() + 1}", Offset(x + 4f, 6f), Type.mono.copy(color = p.textDim)) }
        val ph = vm.position / spb * pxPerBeat - scrollX
        drawPath(Path().apply { moveTo(ph - 7f, 0f); lineTo(ph + 7f, 0f); lineTo(ph, 12f); close() }, p.accent)
    }
}

@Composable private fun TrackHeader(vm: StudioVM, t: Track) {
    val p = LocalPalette.current; val sel = vm.selectedTrackId == t.id; val inst = vm.project.instrument(t.instrumentId)
    Row(Modifier.width(HEADER_W).height(LANE_H).background(if (sel) p.raised else p.header).border(0.5.dp, p.inset).clickable { vm.selectedTrackId = t.id }.semantics { contentDescription = "Track ${t.name}" }) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(Color(t.color)))
        Column(Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 6.dp)) {
            Text(t.name, style = Type.bodyStrong, color = p.text, maxLines = 1)
            Text("${t.family.label} · ${inst.name}", style = Type.label.copy(letterSpacing = 0.sp), color = p.textFaint, maxLines = 1)
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                StateButton("R", t.recArm, p.rec, size = 24.dp, desc = "Record enable ${t.name}") { vm.toggleArm(t) }
                StateButton("I", t.inputMonitor, p.monitor, size = 24.dp, desc = "Input monitor ${t.name}") { vm.toggleMonitor(t) }
                StateButton("S", t.solo, p.solo, size = 24.dp, desc = "Solo ${t.name}") { vm.setTrack(t.id) { solo = !solo } }
                StateButton("M", t.mute, p.mute, size = 24.dp, desc = "Mute ${t.name}") { vm.setTrack(t.id) { mute = !mute } }
            }
        }
        Meter(vm.meters[t.id] ?: 0f, Modifier.padding(vertical = 6.dp).width(6.dp).fillMaxHeight())
        Spacer(Modifier.width(4.dp))
    }
}

@Composable private fun AddTrackRow(vm: StudioVM) {
    val p = LocalPalette.current
    Box(Modifier.width(HEADER_W).height(44.dp).background(p.panel).clickable { vm.galleryOpen = true }.padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) { Text("+  Add track", style = Type.small, color = p.accent) }
}

@Composable private fun Lanes(vm: StudioVM, pxPerBeat: Float, scrollX: Float, setScroll: (Float) -> Unit) {
    val p = LocalPalette.current; val proj = vm.project; val tm = rememberTextMeasurer(); val density = LocalDensity.current
    val laneH = with(density) { LANE_H.toPx() }; val sx by rememberUpdatedState(scrollX); @Suppress("UNUSED_VARIABLE") val r = vm.rev
    var dragClip by remember { mutableStateOf<Pair<Track, Clip>?>(null) }; var dragDx by remember { mutableFloatStateOf(0f) }
    /** 0 = move the clip, -1 = left trim handle, +1 = right trim handle (selected clip only). */
    var trimEdge by remember { mutableIntStateOf(0) }
    val handlePx = with(density) { 24.dp.toPx() }
    val spb = proj.samplesPerBeat.toFloat()
    /** Selected clip whose left/right handle is under [o] (handles sit just inside each edge). */
    fun handleHit(o: Offset): Triple<Track, Clip, Int>? { val ti = (o.y / laneH).toInt(); val t = proj.tracks.getOrNull(ti) ?: return null; val c = t.clips.firstOrNull { it.id == vm.selectedClipId } ?: return null
        val x0 = c.start / spb * pxPerBeat - sx; val x1 = c.end / spb * pxPerBeat - sx
        return when { o.x in (x0 - handlePx / 2)..(x0 + handlePx) -> Triple(t, c, -1); o.x in (x1 - handlePx)..(x1 + handlePx / 2) -> Triple(t, c, 1); else -> null } }
    fun hit(o: Offset): Pair<Track, Clip?>? { val ti = (o.y / laneH).toInt(); val t = proj.tracks.getOrNull(ti) ?: return null; val s = ((o.x + sx) / pxPerBeat * spb).toLong()
        return t to t.clips.lastOrNull { s in it.start until it.end } }
    Canvas(Modifier.fillMaxSize().semantics { contentDescription = "Edit window lanes" }
        .pointerInput(pxPerBeat, proj) { detectTapGestures(onTap = { o -> hit(o)?.let { (t, c) -> vm.selectedTrackId = t.id; vm.selectedClipId = c?.id } },
            onDoubleTap = { o -> vm.locate(((o.x + sx) / pxPerBeat * spb).toLong()) }) }
        // Horizontal-only: vertical swipes fall through to the lane list's vertical scroll (portrait and landscape).
        .pointerInput(pxPerBeat, proj) { detectHorizontalDragGestures(onDragStart = { o -> val th = handleHit(o)
                if (th != null) { dragClip = th.first to th.second; trimEdge = th.third; dragDx = 0f; return@detectHorizontalDragGestures }
                trimEdge = 0; val h = hit(o); if (h?.second != null) { dragClip = h.first to h.second!!; vm.selectedTrackId = h.first.id; vm.selectedClipId = h.second!!.id; dragDx = 0f } else dragClip = null },
            onDragEnd = { dragClip?.let { (t, c) -> val d = (dragDx / pxPerBeat * spb).toLong()
                    when (trimEdge) { -1 -> vm.trimClipEdge(t, c, true, c.start + d, true); 1 -> vm.trimClipEdge(t, c, false, c.end + d, true); else -> vm.moveClip(t, c, c.start + d, true) } }
                dragClip = null; dragDx = 0f; trimEdge = 0 }, onDragCancel = { dragClip = null; dragDx = 0f; trimEdge = 0 }) { ch, dx ->
            ch.consume(); if (dragClip != null) dragDx += dx else setScroll((sx - dx).coerceAtLeast(0f)) } }) {
        val beatsPerBar = (proj.sigNum * 4 / proj.sigDen).coerceAtLeast(1)
        val firstBeat = (scrollX / pxPerBeat).toInt(); val lastBeat = ((scrollX + size.width) / pxPerBeat).toInt() + 1
        for (b in firstBeat..lastBeat) { val x = b * pxPerBeat - scrollX; drawLine(if (b % beatsPerBar == 0) p.gridBar else p.gridBeat, Offset(x, 0f), Offset(x, size.height), 1f) }
        proj.tracks.forEachIndexed { i, t ->
            val y = i * laneH
            if (vm.selectedTrackId == t.id) drawRect(Color.White.copy(alpha = 0.025f), Offset(0f, y), Size(size.width, laneH))
            drawLine(p.inset, Offset(0f, y + laneH), Offset(size.width, y + laneH), 1f)
            val col = Color(t.color)
            for (c in t.clips) {
                val dragging = dragClip?.second === c
                val dx = if (dragging && trimEdge == 0) dragDx else 0f
                val x0 = c.start / spb * pxPerBeat - scrollX + dx + (if (dragging && trimEdge == -1) dragDx else 0f); val x1 = c.end / spb * pxPerBeat - scrollX + dx + (if (dragging && trimEdge == 1) dragDx else 0f)
                if (x1 < 0 || x0 > size.width) continue
                val sel = vm.selectedClipId == c.id
                drawRoundRect(col.copy(alpha = 0.32f), Offset(x0, y + 3f), Size(x1 - x0, laneH - 6f), CornerRadius(4f))
                drawRoundRect(col.copy(alpha = 0.9f), Offset(x0, y + 3f), Size(x1 - x0, 14f), CornerRadius(4f))
                drawText(tm, c.name.take(40), Offset(x0 + 5f, y + 3f), TextStyle(fontSize = 9.sp, color = Color.White))
                vm.pool.peaks(c.audioId)?.let { pk ->
                    val mid = y + 17f + (laneH - 22f) / 2; val amp = (laneH - 24f) / 2; val step = 2f
                    var x = max(x0, 0f); while (x < min(x1, size.width)) { val tl = ((x - (c.start / spb * pxPerBeat - scrollX + dx)) / pxPerBeat * spb); val src = (c.offset + tl * c.rate).toInt() / AudioPoolBlock
                        val v = pk.getOrElse(src) { 0f } * com.studiospark.app.dsp.Shapers.dbToLin(c.gainDb); drawLine(Color.White.copy(alpha = 0.78f), Offset(x, mid - v * amp), Offset(x, mid + v * amp), 1.2f); x += step } }
                if (c.fadeIn > 0) { val fx = c.fadeIn / spb * pxPerBeat; drawLine(Color.White.copy(alpha = 0.7f), Offset(x0, y + laneH - 4f), Offset(x0 + fx, y + 18f), 1.2f) }
                if (c.fadeOut > 0) { val fx = c.fadeOut / spb * pxPerBeat; drawLine(Color.White.copy(alpha = 0.7f), Offset(x1 - fx, y + 18f), Offset(x1, y + laneH - 4f), 1.2f) }
                if (sel) { drawRoundRect(p.select, Offset(x0, y + 3f), Size(x1 - x0, laneH - 6f), CornerRadius(4f), style = Stroke(2f))
                    // trim handles: drag either edge of the selected clip
                    val hw = 8f; val hh = laneH * 0.5f; val hy = y + (laneH - hh) / 2
                    drawRoundRect(p.select, Offset(x0, hy), Size(hw, hh), CornerRadius(3f)); drawRoundRect(p.select, Offset(x1 - hw, hy), Size(hw, hh), CornerRadius(3f)) }
            }
            if (t.notes.isNotEmpty()) { val lo = t.notes.minOf { it.pitch }; val hi = max(lo + 12, t.notes.maxOf { it.pitch }); val h = laneH - 22f
                run { val rs = t.notes.minOf { it.start }; val re = t.notes.maxOf { it.start + it.length }; val bar = proj.samplesPerBar
                    val x0 = (rs / bar) * bar / spb * pxPerBeat - scrollX; val x1 = ((re + bar - 1) / bar) * bar / spb * pxPerBeat - scrollX
                    drawRoundRect(col.copy(alpha = 0.16f), Offset(x0, y + 3f), Size(x1 - x0, laneH - 6f), CornerRadius(4f))
                    drawRoundRect(col.copy(alpha = 0.55f), Offset(x0, y + 3f), Size(x1 - x0, 14f), CornerRadius(4f))
                    drawText(tm, "MIDI · ${t.notes.size} notes", Offset(x0 + 5f, y + 3f), TextStyle(fontSize = 9.sp, color = Color.White)) }
                for (n in t.notes) { val x0 = n.start / spb * pxPerBeat - scrollX; val w = max(2f, n.length / spb * pxPerBeat); if (x0 + w < 0 || x0 > size.width) continue
                    val ny = y + 18f + h - (n.pitch - lo + 1).toFloat() / (hi - lo + 1) * h; drawRoundRect(col.copy(alpha = 0.95f), Offset(x0, ny), Size(w, max(3f, h / (hi - lo + 1))), CornerRadius(1.5f)) } }
            if (t.recArm && vm.transport == TransportState.RECORDING) drawRect(p.rec.copy(alpha = 0.08f), Offset(0f, y), Size(size.width, laneH))
            if (t.automationOn && t.automation.isNotEmpty()) { val pts = t.automation.sortedBy { it.at }; var prev: Offset? = null
                for (a in pts) { val o = Offset(a.at / spb * pxPerBeat - scrollX, y + laneH - 4f - ((a.db + 48f) / 54f).coerceIn(0f, 1f) * (laneH - 8f)); prev?.let { drawLine(p.solo, it, o, 1.5f) }; drawCircle(p.solo, 3.5f, o); prev = o } }
        }
        val ph = vm.position / spb * pxPerBeat - scrollX
        drawLine(if (vm.transport == TransportState.RECORDING) p.rec else p.accent, Offset(ph, 0f), Offset(ph, size.height), 1.5f)
    }
}
const val AudioPoolBlock = 256

@Composable private fun MixWindow(vm: StudioVM) {
    val p = LocalPalette.current; @Suppress("UNUSED_VARIABLE") val r = vm.rev
    Row(Modifier.fillMaxSize().background(p.timeline).horizontalScroll(rememberScrollState()).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Column(Modifier.fillMaxHeight().width(28.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(false to "C", true to "S").forEach { (c, l) -> Box(Modifier.size(28.dp).clip(RoundedCornerShape(4.dp)).background(if (vm.compactMixer == c) p.accent else p.raised).clickable { vm.chooseCompactMixer(c) }
                .semantics { contentDescription = if (c) "Small mixer strips" else "Console mixer strips" }, contentAlignment = Alignment.Center) { Text(l, style = Type.label, color = if (vm.compactMixer == c) Color.Black else p.textDim) } } }
        vm.project.tracks.forEach { t -> ChannelStrip(vm, t, vm.compactMixer) }
        MasterStrip(vm)
    }
}

@Composable private fun ChannelStrip(vm: StudioVM, t: Track, compact: Boolean = false) {
    val p = LocalPalette.current; val sel = vm.selectedTrackId == t.id
    Column(Modifier.width(if (compact) 58.dp else 84.dp).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(if (sel) p.raised else p.panel).border(1.dp, if (sel) p.accent.copy(alpha = 0.6f) else p.lineSoft, RoundedCornerShape(4.dp)).clickable { vm.selectedTrackId = t.id }.padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (!compact) Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(3.dp)).background(p.inset).clickable { vm.selectedTrackId = t.id; vm.openUpload("rig", t) }.padding(4.dp)) {
            Column { Text("RIG", style = Type.label, color = p.accent); t.rig.slots.take(3).forEach { s -> Text(s.gear.name, style = Type.label.copy(fontSize = 7.sp, letterSpacing = 0.sp), color = if (s.enabled) p.textDim else p.textFaint, maxLines = 1) } } }
        Spacer(Modifier.height(4.dp))
        Knob((t.pan + 1) / 2, { vm.setTrack(t.id, "pan") { pan = it * 2 - 1 } }, "Pan", if (abs(t.pan) < 0.02f) "C" else if (t.pan < 0) "L${(-t.pan * 100).toInt()}" else "R${(t.pan * 100).toInt()}", size = if (compact) 24.dp else 30.dp, style = 1)
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) { StateButton("S", t.solo, p.solo, size = 22.dp) { vm.setTrack(t.id) { solo = !solo } }; StateButton("M", t.mute, p.mute, size = 22.dp) { vm.setTrack(t.id) { mute = !mute } } }
        Spacer(Modifier.height(3.dp))
        Row(Modifier.weight(1f), verticalAlignment = Alignment.Bottom) {
            Fader(dbToFader(t.volumeDb), { vm.setTrack(t.id, "vol") { volumeDb = faderDb(it).coerceAtLeast(-96f) } }, Modifier.fillMaxHeight(), desc = "Fader ${t.name}")
            Meter(vm.meters[t.id] ?: 0f, Modifier.width(7.dp).fillMaxHeight().padding(vertical = 10.dp))
        }
        Text(if (t.volumeDb <= -95) "−∞" else String.format("%+.1f", t.volumeDb), style = Type.mono, color = p.text)
        StateButton("R", t.recArm, p.rec, size = 22.dp) { vm.toggleArm(t) }
        Box(Modifier.fillMaxWidth().padding(top = 3.dp).background(Color(t.color)).padding(3.dp)) { Text(t.name, style = Type.label.copy(letterSpacing = 0.sp), color = Color.White, maxLines = 1) }
    }
}

@Composable private fun MasterStrip(vm: StudioVM) {
    val p = LocalPalette.current
    Column(Modifier.width(90.dp).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(p.header).border(1.dp, p.accent.copy(alpha = 0.4f), RoundedCornerShape(4.dp)).padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("MASTER", style = Type.label, color = p.accent)
        Row(Modifier.weight(1f).padding(vertical = 8.dp), verticalAlignment = Alignment.Bottom) {
            var g by remember { mutableFloatStateOf(dbToFader(0f)) }
            Fader(g, { g = it; vm.mixer.masterGain = Shapers.dbToLin(faderDb(it)) }, Modifier.fillMaxHeight(), desc = "Master fader")
            Meter(vm.masterL, Modifier.width(7.dp).fillMaxHeight().padding(vertical = 10.dp)); Spacer(Modifier.width(2.dp)); Meter(vm.masterR, Modifier.width(7.dp).fillMaxHeight().padding(vertical = 10.dp))
        }
        Text("L  R", style = Type.mono, color = p.textFaint)
    }
}

/** Master board: compact strip of every track's level, rig and state — scroll target "apps, pedals, master board". */
@Composable private fun MasterBoard(vm: StudioVM) {
    val p = LocalPalette.current
    Panel(Modifier.fillMaxWidth()) {
        SectionLabel("Master board · ${vm.project.tracks.size}-track") { Text("Engine ${vm.engine.block} fr · out ${String.format("%.0f", vm.engine.outputLatencyMs)} ms", style = Type.mono, color = p.textFaint) }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            vm.project.tracks.forEach { t -> Column(Modifier.width(118.dp).clip(RoundedCornerShape(5.dp)).background(if (vm.selectedTrackId == t.id) p.raised else p.inset).clickable { vm.selectedTrackId = t.id }.padding(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(Color(t.color))); Spacer(Modifier.width(6.dp)); Text(t.name, style = Type.small, color = p.text, maxLines = 1) }
                Spacer(Modifier.height(6.dp)); Meter(vm.meters[t.id] ?: 0f, Modifier.fillMaxWidth().height(5.dp), vertical = false)
                Spacer(Modifier.height(6.dp)); Text(t.rig.slots.filter { it.enabled }.joinToString(" › ") { it.gear.name.substringBefore(' ') }.ifBlank { "Dry" }, style = Type.label.copy(fontSize = 8.sp, letterSpacing = 0.sp), color = p.textDim, maxLines = 2)
            } }
        }
    }
}

/** Selected-track section: instrument, rig entry (inside the upload/tone flow), playable surface, piano roll, automation and AI tools. */
@Composable private fun TrackDetail(vm: StudioVM, t: Track) {
    val p = LocalPalette.current; val inst = vm.project.instrument(t.instrumentId); @Suppress("UNUSED_VARIABLE") val r = vm.rev
    Panel(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Photo(inst.art, Modifier.size(56.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) { Text(t.name, style = Type.title, color = p.text); Text("${inst.name} · ${inst.source.label}", style = Type.small, color = p.textFaint) }
            IconBtn(Icons.Outlined.Delete, "Delete track") { vm.deleteTrack(t.id) }
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Dropdown("Instrument", inst.name, vm.project.allInstruments(), { "${it.category} · ${it.name}" }) { vm.setInstrument(t, it) }
            if (t.family == Family.GUITAR || t.family == Family.BASS) Dropdown("Source", if (t.useMic) "Mic" else "Instrument", listOf("Instrument", "Mic"), { it }) { s -> val mic = s == "Mic"; if (mic != t.useMic) { if (mic) vm.withMic { vm.setTrackSource(t, true) } else vm.setTrackSource(t, false) } }
            if (t.family == Family.GUITAR || t.family == Family.BASS) Dropdown("Tuning", t.tuning ?: inst.tuning ?: "Standard", if (t.family == Family.BASS) Tunings.bass else Tunings.guitar, { it.name }) { tu -> vm.setTrack(t.id) { tuning = tu.name } }
            if (t.family == Family.DRUMS) Dropdown("Kit", DrumKits.byId(t.kitId ?: "", vm.project.customKits)?.name ?: "Kit", DrumKits.factory + vm.project.customKits, { it.name }) { k -> vm.setTrack(t.id) { kitId = k.id } }
            Dropdown("Input", vm.inputDevices.firstOrNull { it.id == vm.inputDeviceId }?.name ?: "Phone microphone", vm.inputDevices, { it.name }, onOpen = { vm.ensureInputs() }) { vm.pickInput(it.id) }
        }
        if ((t.family == Family.GUITAR || t.family == Family.BASS) && t.useMic) MicSourcePanel(vm, t)
        Spacer(Modifier.height(12.dp))
        // rig summary → opens the tone step of the upload flow
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).chrome(p).clickable { vm.openUpload("rig", t) }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("AMP & EFFECTS RIG", style = Type.label, color = p.accent); Text(t.rig.presetName, style = Type.bodyStrong, color = Color.White)
                Text(t.rig.slots.joinToString("  →  ") { (if (it.enabled) "" else "◌ ") + it.gear.name }, style = Type.small, color = Color(0xFFB0B5BA), maxLines = 2) }
            Row { t.rig.slots.filter { it.gear.kind != GearKind.RACK }.take(3).forEach { s -> Photo(s.gear.art, Modifier.size(40.dp).padding(2.dp)) } }
            Icon(Icons.Default.ChevronRight, null, tint = Color.White)
        }
    }
    Panel(Modifier.fillMaxWidth()) { PlaySurfaceFor(vm, t) }
    if (t.family == Family.KEYS || t.family == Family.SYNTH || t.family == Family.ORCHESTRAL || t.family == Family.BASS || t.family == Family.VOCAL || t.notes.isNotEmpty()) PianoRoll(vm, t)
    if (inst.voice == "voice" || t.lyrics.isNotEmpty()) LyricSheet(vm, t)
    if (!vm.chromeHidden) AutomationPanel(vm, t)
}

/** Guitar/Bass on Mic: monitor through the rig, record dry or printed (GitHub issue 1), mic rig preset. */
@Composable private fun MicSourcePanel(vm: StudioVM, t: Track) {
    val p = LocalPalette.current
    Spacer(Modifier.height(10.dp))
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(p.inset).padding(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("Print processed tone", style = Type.bodyStrong, color = p.text)
                Text(if (t.printTone) "Records what you hear: amp, pedals and effects are printed, and playback skips the rig so nothing is applied twice." else "Records the dry mic; the rig is applied on playback so you can change the tone later.", style = Type.small, color = p.textFaint) }
            Switch(t.printTone, { on -> vm.setTrack(t.id) { printTone = on } }, colors = SwitchDefaults.colors(checkedTrackColor = p.accent), modifier = Modifier.semantics { contentDescription = "Print processed tone ${t.name}" })
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            ToolButton(if (t.inputMonitor) "Monitoring through rig" else "Monitor through rig", icon = Icons.Default.Headphones, active = t.inputMonitor, accent = p.monitor) { vm.toggleMonitor(t) }
            RigPresets.micFor(t.family)?.let { pr -> ToolButton("Mic rig preset", icon = Icons.Default.Tune, active = t.rig.presetName == pr.name) { vm.setTrack(t.id) { rig = pr.build() } } }
        }
        Text("Use wired or USB headphones: Bluetooth adds 100 ms or more, and the speaker feeds back into the mic.", style = Type.label.copy(letterSpacing = 0.sp), color = p.textFaint, modifier = Modifier.padding(top = 6.dp))
    }
}

/** Choose a play surface (strings, keys, kit, pads) for any track; universal Record on every surface. */
@Composable fun PlaySurfaceFor(vm: StudioVM, t: Track, compact: Boolean = false) {
    val p = LocalPalette.current; val inst = vm.project.instrument(t.instrumentId)
    val default = when (t.family) { Family.GUITAR, Family.BASS -> Surface.STRINGS; Family.DRUMS -> if (inst.surface == Surface.PADS) Surface.PADS else Surface.DRUM_KIT; else -> Surface.KEYS }
    val surface = vm.surfaceOverride ?: default
    val kit = DrumKits.byId(t.kitId ?: inst.voice.removePrefix("kit:"), vm.project.customKits) ?: DrumKits.studio
    val recording = vm.transport == TransportState.RECORDING || vm.transport == TransportState.COUNT_IN
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("PLAY", style = Type.label, color = p.textDim)
            Dropdown("", surface.label, Surface.values().toList(), { it.label }) { vm.surfaceOverride = it }
            Spacer(Modifier.weight(1f))
            ToolButton(if (recording) "Stop" else "Record", icon = if (recording) Icons.Default.Stop else Icons.Default.FiberManualRecord, active = recording || t.recArm, accent = p.rec) {
                if (!t.recArm) vm.setTrack(t.id) { recArm = true }; vm.record() }
            if (!compact && (t.family == Family.GUITAR || t.family == Family.BASS)) ToolButton(if (t.family == Family.BASS) "Open Bass tab" else "Open Guitar tab", icon = Icons.Default.Build) { vm.screen = if (t.family == Family.BASS) Screen.BASS else Screen.GUITAR }
            if (!compact && t.family == Family.DRUMS) ToolButton("Open Drum Kit tab", icon = Icons.Default.Build) { vm.screen = Screen.DRUMS }
        }
        Spacer(Modifier.height(10.dp))
        when (surface) {
            Surface.STRINGS -> { val tuning = Tunings.find(t.tuning ?: inst.tuning ?: "")?.midi ?: if (t.family == Family.BASS) Tunings.bass[0].midi else Tunings.guitar[0].midi
                vm.project.build(inst.baseId)?.let { b -> InstrumentStage(b, Modifier.fillMaxWidth().aspectRatio(if (compact) 3.2f else 2.8f).clip(RoundedCornerShape(6.dp))) { n, v, _ -> vm.noteOn(n, v, t.id) }; Spacer(Modifier.height(6.dp)) }
                Row { if (vm.project.build(inst.baseId) == null) Photo(inst.art, Modifier.width(54.dp).height(if (compact) 150.dp else 190.dp)); if (vm.project.build(inst.baseId) == null) Spacer(Modifier.width(8.dp))
                    GuitarStrings(tuning, if (t.family == Family.BASS) 12 else 15, Modifier.weight(1f).height(if (compact) 150.dp else 190.dp).clip(RoundedCornerShape(4.dp)), bass = t.family == Family.BASS) { n, v -> vm.noteOn(n, v, t.id) } } }
            Surface.KEYS -> { var oct by remember { mutableIntStateOf(if (t.family == Family.BASS) 2 else 4) }
                Row(verticalAlignment = Alignment.CenterVertically) { ToolButton("Oct −") { oct = (oct - 1).coerceAtLeast(1) }; Text("  C$oct  ", style = Type.mono, color = p.textDim); ToolButton("Oct +") { oct = (oct + 1).coerceAtMost(6) } }
                Spacer(Modifier.height(8.dp))
                val hints = remember(t.notes.size) { Assistant.nextNotes(t.notes.takeLast(8).map { it.pitch }).toSet() }
                PianoKeys((oct + 1) * 12, 2, Modifier.fillMaxWidth().height(if (compact) 130.dp else 170.dp), highlight = hints, onOn = { n, v -> vm.noteOn(n, v, t.id) }, onOff = { vm.noteOff(it) }) }
            Surface.DRUM_KIT -> DrumKitSurface(kit, Modifier.fillMaxWidth().aspectRatio(1.5f).clip(RoundedCornerShape(6.dp)), onHit = { pc, v -> vm.noteOn(pc.midi, v, t.id) })
            Surface.PADS -> PadSurface(vm, t, kit)
        }
    }
}

/** Pads with per-pad Play (audition), Pause and Record-arm. Armed pads are captured when the transport records. */
@Composable private fun PadSurface(vm: StudioVM, t: Track, kit: DrumKit) {
    val p = LocalPalette.current; var armed by remember { mutableStateOf(setOf<String>()) }; var mode by remember { mutableStateOf("Play") }; var pausedPads by remember { mutableStateOf(setOf<String>()) }
    val pads = if (kit.pieces.size >= 16) kit else DrumKits.pads
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("Play", "Pause", "Record").forEach { m -> ToolButton(m, active = mode == m, accent = if (m == "Record") p.rec else null) { mode = m } }
            Spacer(Modifier.weight(1f)); Text(if (armed.isEmpty()) "Tap a pad in Record mode to arm it" else "${armed.size} pad(s) armed", style = Type.small, color = if (armed.isEmpty()) p.textFaint else p.rec) }
        Spacer(Modifier.height(8.dp))
        PadGrid(pads, Modifier.fillMaxWidth().widthIn(max = 520.dp), armed) { pc, v ->
            when (mode) { "Record" -> { armed = if (pc.id in armed) armed - pc.id else armed + pc.id; if (armed.isNotEmpty() && !t.recArm) vm.setTrack(t.id) { recArm = true } }
                "Pause" -> pausedPads = if (pc.id in pausedPads) pausedPads - pc.id else pausedPads + pc.id
                else -> if (pc.id !in pausedPads) { val target = if (armed.isEmpty() || pc.id in armed) t.id else null; if (target != null) vm.noteOn(pc.midi, v, target) else vm.noteOn(pc.midi, v, t.id) } } }
    }
}

/** Minimal piano roll: tap to add on the grid, tap a note to delete, quantize, AI melody and next-note suggestions. */
@Composable private fun PianoRoll(vm: StudioVM, t: Track) {
    val p = LocalPalette.current; val proj = vm.project; val tm = rememberTextMeasurer(); @Suppress("UNUSED_VARIABLE") val r = vm.rev
    val lo = 36; val hi = 84; val beats = 16; val spb = proj.samplesPerBeat
    Panel(Modifier.fillMaxWidth()) {
        SectionLabel("Piano roll · MIDI") {
            ToolButton("Quantize") { vm.setTrack(t.id) { val q = spb / 4; val n = notes.map { it.copy(start = ((it.start + q / 2) / q) * q) }; notes.clear(); notes.addAll(n) } }
            Spacer(Modifier.width(6.dp)); ToolButton("AI melody", icon = Icons.Default.AutoAwesome) { vm.aiMelody(t) }
            Spacer(Modifier.width(6.dp)); IconBtn(Icons.Outlined.DeleteSweep, "Clear notes") { vm.setTrack(t.id) { notes.clear() } }
        }
        val key = Assistant.detectKey(t.notes.map { it.pitch }); val chords = Assistant.chordProgression(key, "default")
        Text("Key ${key} · suggested chords ${chords.joinToString("  ")} · next notes ${Assistant.nextNotes(t.notes.takeLast(8).map { it.pitch }).joinToString(" ") { midiName(it) }}", style = Type.small, color = p.textDim)
        Spacer(Modifier.height(8.dp))
        Canvas(Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(4.dp)).background(p.inset).pointerInput(t.id, proj.tempo) { detectTapGestures { o ->
            val beatW = size.width / beats; val rowH = size.height / (hi - lo); val pitch = hi - 1 - (o.y / rowH).toInt(); val at = ((o.x / beatW) * 4).toInt() * spb / 4
            val existing = t.notes.firstOrNull { it.pitch == pitch && at in it.start until it.start + it.length }
            vm.setTrack(t.id) { if (existing != null) notes.remove(existing) else notes.add(MidiNote(pitch, at, spb / 2, 100)) }
            if (existing == null) { vm.noteOn(pitch, 100, t.id); vm.noteOff(pitch) } } }) {
            val beatW = size.width / beats; val rowH = size.height / (hi - lo)
            for (k in lo until hi) { val y = (hi - 1 - k) * rowH; if (k % 12 in listOf(1, 3, 6, 8, 10)) drawRect(Color.Black.copy(alpha = 0.25f), Offset(0f, y), Size(size.width, rowH)); if (k % 12 == 0) drawText(tm, midiName(k), Offset(2f, y - 2f), Type.mono.copy(color = p.textFaint, fontSize = 8.sp)) }
            for (b in 0..beats * 4) drawLine(if (b % 16 == 0) p.gridBar else if (b % 4 == 0) p.lineSoft else p.gridBeat, Offset(b * beatW / 4, 0f), Offset(b * beatW / 4, size.height), 1f)
            t.notes.forEach { n -> val x = n.start.toFloat() / spb * beatW; if (x > size.width) return@forEach; val y = (hi - 1 - n.pitch) * rowH
                drawRoundRect(Color(t.color).copy(alpha = 0.5f + n.velocity / 254f), Offset(x, y), Size(max(4f, n.length.toFloat() / spb * beatW), rowH), CornerRadius(2f)) }
            val ph = vm.position.toFloat() / spb * beatW; if (ph < size.width) drawLine(p.accent, Offset(ph, 0f), Offset(ph, size.height), 1.5f)
        }
    }
}

/** AI songwriting / vocal: per-line editable lyric sheet under the keyboard, vocal strip (tempo, range, current words), promptable style. */
@Composable private fun LyricSheet(vm: StudioVM, t: Track) {
    val p = LocalPalette.current; var prompt by remember { mutableStateOf("night drive") }; var genre by remember { mutableStateOf("Pop") }
    Panel(Modifier.fillMaxWidth()) {
        SectionLabel("Lyric sheet · ${t.name}") { Text("On-device writer & voice", style = Type.label, color = p.textFaint) }
        // vocal strip
        val range = if (t.notes.isEmpty()) "—" else "${midiName(t.notes.minOf { it.pitch })}–${midiName(t.notes.maxOf { it.pitch })}"
        val lines = t.lyrics.filter { !it.startsWith("[") }; val lineIdx = if (lines.isEmpty()) -1 else ((vm.position / (vm.project.samplesPerBar * 2)).toInt()).coerceIn(0, lines.size - 1)
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(p.inset).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("♩ ${vm.project.tempo.toInt()}", style = Type.mono, color = p.text); Text("Range $range", style = Type.mono, color = p.text)
            Text(if (lineIdx >= 0) "“${lines[lineIdx]}”" else "No words yet", style = Type.small, color = p.accent, maxLines = 1) }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f).clip(RoundedCornerShape(4.dp)).background(p.inset).padding(10.dp)) { BasicTextField(prompt, { prompt = it }, textStyle = Type.body.copy(color = p.text), cursorBrush = SolidColor(p.accent), singleLine = true, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Song prompt" }) }
            Dropdown("", genre, listOf("Pop", "Rock", "Metal", "R&B", "Country", "Folk"), { it }) { genre = it }
            ToolButton("Write", icon = Icons.Default.AutoAwesome) { vm.aiLyrics(t, prompt, genre) }
        }
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("Pop · warm", "Rock · Bright", "Ballad · Breathy").forEach { s -> ToolButton(s, active = t.voiceStyle == s) { vm.setTrack(t.id) { voiceStyle = s } } } }
        Spacer(Modifier.height(8.dp))
        t.lyrics.forEachIndexed { i, line -> if (line.startsWith("[")) Text(line, style = Type.label, color = p.accent, modifier = Modifier.padding(top = 8.dp))
            else Box(Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(3.dp)).background(if (lines.getOrNull(lineIdx) == line) p.accent.copy(alpha = 0.12f) else p.inset).padding(8.dp)) {
                BasicTextField(line, { v -> vm.setTrack(t.id, "lyric$i") { lyrics[i] = v } }, textStyle = Type.body.copy(color = p.text), cursorBrush = SolidColor(p.accent), modifier = Modifier.fillMaxWidth()) } }
        if (t.lyrics.isEmpty()) Text("Write a draft, then edit any line. The AI Vocal track sings the lyric syllables on its MIDI notes with a synthetic on-device voice.", style = Type.small, color = p.textFaint)
    }
}

@Composable private fun AutomationPanel(vm: StudioVM, t: Track) {
    val p = LocalPalette.current
    Panel(Modifier.fillMaxWidth()) {
        SectionLabel("Volume automation") { Switch(t.automationOn, { on -> vm.setTrack(t.id) { automationOn = on } }, colors = SwitchDefaults.colors(checkedTrackColor = p.accent)) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ToolButton("Add point at playhead (${String.format("%+.1f dB", t.volumeDb)})") { vm.setTrack(t.id) { automation.removeAll { it.at == vm.position }; automation.add(AutoPoint(vm.position, volumeDb)); automationOn = true } }
            ToolButton("Clear") { vm.setTrack(t.id) { automation.clear() } }
        }
        Text("${t.automation.size} point(s). Set the fader, move the playhead, add a point; playback follows the curve.", style = Type.small, color = p.textFaint, modifier = Modifier.padding(top = 6.dp))
    }
}
