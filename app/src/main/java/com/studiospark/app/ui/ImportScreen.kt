package com.studiospark.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.studiospark.app.importer.*
import kotlin.math.roundToInt

/** StudioSpark Import palette: charcoal #0E1214, signal green #3DDC97, mist text #E7EEE9 (scoped to this tab). */
val ImportPalette = Graphite.copy(name = "Import", window = Color(0xFF0E1214), panel = Color(0xFF151B1E), header = Color(0xFF1A2124), inset = Color(0xFF0A0D0F),
    raised = Color(0xFF222B2F), line = Color(0xFF2C373C), lineSoft = Color(0xFF1F282C),
    text = Color(0xFFE7EEE9), textDim = Color(0xFFB4BFB9), textFaint = Color(0xFF7C8983), accent = Color(0xFF3DDC97), accentDim = Color(0xFF1F6E4C), select = Color(0xFF3DDC97), play = Color(0xFF3DDC97))

private val stemColors = mapOf("vocals" to Color(0xFFAD5C82), "drums" to Color(0xFFC09A3E), "bass" to Color(0xFF4F7CAC), "other" to Color(0xFF3DDC97))

/**
 * Import tab. Composing this screen creates nothing heavy: the controller is a light state holder and the models
 * load only after the user picks files and a split is actually needed.
 */
@Composable fun ImportScreen(vm: StudioVM) {
    val c = remember { vm.importer() }
    LaunchedEffect(c) { c.refreshLibrarySize() }   // off-main, cached
    CompositionLocalProvider(LocalPalette provides ImportPalette) {
        val p = LocalPalette.current
        val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> -> if (uris.isNotEmpty()) c.enqueueFiles(uris) }
        val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? -> uri?.let { c.enqueueFolder(it) } }
        Column(Modifier.fillMaxSize().background(p.window).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Panel(Modifier.fillMaxWidth(), pad = 16.dp) {
                SectionLabel("Import · split to tracks") { Text(c.activeLabel + " · " + (c.provider ?: "loads on first split"), style = Type.label, color = p.textFaint) }
                Text("A separate, blank import session. Nothing reaches your Studio project until you tap Add to project.", style = Type.small, color = p.textDim)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                    Checkbox(c.rightsConfirmed, { c.rightsConfirmed = it }, colors = CheckboxDefaults.colors(checkedColor = p.accent))
                    Text(com.studiospark.app.core.ImportPolicy.permissionText, style = Type.small, color = p.textDim) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                    PrimaryButton("Import files", icon = Icons.Default.FileOpen, enabled = c.rightsConfirmed) { files.launch(arrayOf("audio/mpeg", "audio/wav", "audio/x-wav", "audio/flac", "audio/x-flac", "audio/aac", "audio/mp4", "audio/x-m4a", "audio/*")) }
                    ToolButton("Import folder", icon = Icons.Default.FolderOpen, enabled = c.rightsConfirmed) { folder.launch(null) }
                    if (c.running) ToolButton("Cancel", accent = p.rec, active = true) { c.cancelAll() }
                }
                c.message?.let { Text(it, style = Type.small, color = p.rec, modifier = Modifier.padding(top = 8.dp)) }
            }
            ModelPanel(c)
            if (c.jobs.isEmpty()) EmptyState("Drop in a song, get separate tracks", "MP3, WAV, FLAC or AAC. Each file is split on this phone into vocals, drums, bass and other (plus guitar and piano with six-stem), tagged, and routed to matching tracks. Files you've split before come straight from your stem library.")
            else Panel(Modifier.fillMaxWidth()) {
                SectionLabel("Queue (${c.jobs.size})") { Text(if (c.librarySizeBytes < 0) "Library …" else "Library ${"%.0f".format(c.librarySizeBytes / 1e6)} MB", style = Type.label, color = p.textFaint) }
                c.jobs.forEach { j -> QueueRow(j, j == c.selected) { c.select(j) } }
            }
            c.selected?.session?.let { s -> key(s, c.rev) { SessionPanel(vm, s) } }
        }
    }
}

@Composable private fun QueueRow(j: ImportJob, sel: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(4.dp)).background(if (sel) p.raised else p.inset).clickable(onClick = onClick).padding(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Text(j.name, style = Type.bodyStrong, color = p.text, maxLines = 1, modifier = Modifier.weight(1f)); Text(j.state, style = Type.small, color = if (j.error != null) p.rec else p.textDim, maxLines = 1) }
        if (j.progress in 0.001f..0.999f) LinearProgressIndicator(progress = { j.progress }, Modifier.fillMaxWidth().padding(top = 6.dp).height(3.dp), color = p.accent, trackColor = p.window)
        j.error?.let { Text(it, style = Type.small, color = p.rec) }
    }
}

@Composable private fun SessionPanel(vm: StudioVM, s: ImportSession) {
    val p = LocalPalette.current; var tick by remember { mutableIntStateOf(0) }
    var dragging by remember { mutableStateOf<String?>(null) }; var dragY by remember { mutableFloatStateOf(0f) }
    val stripPx = with(LocalDensity.current) { 76.dp.toPx() }
    Panel(Modifier.fillMaxWidth()) {
        SectionLabel(s.sourceName) { Text(listOfNotNull(s.meta?.tempoBpm?.let { "≈${it.roundToInt()} BPM" }, s.meta?.key?.let { "key ≈ $it" }).joinToString(" · ").ifBlank { "tempo/key not detected" }, style = Type.label, color = p.accent) }
        Text("Long-press a strip and drag it onto another to swap where they go. Estimates; some bleed between stems is normal.", style = Type.small, color = p.textFaint)
        Spacer(Modifier.height(8.dp))
        @Suppress("UNUSED_EXPRESSION") tick
        s.strips.forEachIndexed { idx, st ->
            val isDrag = dragging == st.stem
            Row(Modifier.fillMaxWidth().height(72.dp).padding(vertical = 2.dp).graphicsLayer { translationY = if (isDrag) dragY else 0f; shadowElevation = if (isDrag) 12f else 0f }
                .clip(RoundedCornerShape(4.dp)).background(if (isDrag) p.raised else p.header).border(0.5.dp, if (isDrag) p.accent else p.inset, RoundedCornerShape(4.dp))
                .semantics { contentDescription = "Import strip ${st.stem}" }
                .pointerInput(st.stem) { detectDragGesturesAfterLongPress(onDragStart = { dragging = st.stem; dragY = 0f },
                    onDragEnd = { val to = (idx + (dragY / stripPx).roundToInt()).coerceIn(0, s.strips.lastIndex); if (to != idx) s.dragOnto(st.stem, s.strips[to].stem); dragging = null; dragY = 0f; tick++ },
                    onDragCancel = { dragging = null; dragY = 0f }) { ch, d -> ch.consume(); dragY += d.y } },
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(4.dp).fillMaxHeight().background(stemColors[st.stem] ?: p.accent))
                Column(Modifier.width(132.dp).padding(horizontal = 8.dp)) {
                    Text(st.stem.replaceFirstChar { it.uppercase() }, style = Type.bodyStrong, color = p.text, maxLines = 1)
                    Pill(st.tag, p.accentDim)
                    Dropdown("", "→ ${st.destination.label}", Destination.values().toList(), { it.label }) { d -> s.reassign(st.stem, d); tick++ }
                }
                Box(Modifier.weight(1f).fillMaxHeight().padding(6.dp).background((stemColors[st.stem] ?: p.accent).copy(alpha = 0.12f)).clickable { vm.importPreview(st.audio) }) { Waveform(st.audio, Modifier.fillMaxSize()) }
                Fader(dbToFader(st.volumeDb), { st.volumeDb = faderDb(it).coerceAtLeast(-96f); tick++ }, Modifier.fillMaxHeight(), desc = "Import fader ${st.stem}")
                Text(if (st.volumeDb <= -95) "−∞" else String.format("%+.1f", st.volumeDb), style = Type.mono, color = p.text, modifier = Modifier.width(40.dp))
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            val n = s.strips.count { it.destination != Destination.SKIP }
            PrimaryButton("Add $n tracks to project", icon = Icons.Default.Check, enabled = n > 0) { vm.importCommit(s) }
            ToolButton("Stop preview", icon = Icons.Default.Stop) { vm.importPreview(null) }
        }
    }
}

/** Model choice. HT-Demucs is not in the APK: it downloads once (pinned + SHA-256 checked) on the first split. */
@Composable private fun ModelPanel(c: ImportController) {
    val p = LocalPalette.current
    Panel(Modifier.fillMaxWidth()) {
        SectionLabel("Separation model") {}
        val installed = remember(c.variant, c.download, c.running) { c.demucsInstalled() }
        Text(if (c.useFallback) "Using the bundled Open-Unmix (MIT) model: works offline, lower quality." else if (installed) "${c.variant.label} installed." else "${c.variant.label}: %.0f MB one-time download on your first split (Wi-Fi recommended). Nothing downloads until you import.".format(c.variant.bytes / 1e6),
            style = Type.small, color = p.textDim)
        if (c.download >= 0f) LinearProgressIndicator(progress = { c.download }, Modifier.fillMaxWidth().padding(top = 6.dp).height(3.dp), color = p.accent, trackColor = p.window)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(c.useFallback, { if (!c.running) c.useFallback = it }, colors = CheckboxDefaults.colors(checkedColor = p.accent))
            Text("Offline: use bundled Open-Unmix instead", style = Type.small, color = p.textDim) }
        if (!c.useFallback) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DemucsVariant.values().filter { it != DemucsVariant.SIX || c.sixAllowed }.forEach { v ->
                    ToolButton(when (v) { DemucsVariant.FT -> "FT (best)"; DemucsVariant.FOUR -> "4-stem"; DemucsVariant.SIX -> "6-stem" }, active = c.variant == v) { if (!c.running) c.demucsChoice = v } } }
            val calls = com.studiospark.app.importer.demucs.DemucsHop.callsPerSecond(c.hopFrames, if (c.variant == DemucsVariant.FT) 4 else 1)
            Text("Hop %.2f s · ≈%.0f model calls per second of audio. Shorter hop = smoother seams but slower splits.".format(c.hopFrames / 44100.0, calls), style = Type.small, color = p.textFaint)
        }
        if (!c.sixAllowed) Text("Six-stem needs 4 GB+ RAM; this device reports %.1f GB.".format(c.totalRam / 1073741824.0), style = Type.small, color = p.textFaint)
    }
}
