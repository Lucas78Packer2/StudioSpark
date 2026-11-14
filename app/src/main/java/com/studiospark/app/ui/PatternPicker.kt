package com.studiospark.app.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.studiospark.app.core.*

/**
 * Compact genre-pack picker: genre → part (or full backing set) → one of five patterns, plus Shuffle, Vary and an offline prompt.
 * Shared by the Studio edit toolbar (places MIDI notes at the playhead bar) and the Jam room (backing band).
 */
@Composable fun PatternPicker(vm: StudioVM, actionLabel: String, parts: List<PatternPart> = PatternPart.values().toList(), onAction: (PatternPick) -> Unit) {
    val p = LocalPalette.current; val lib = remember { vm.patternLibrary() }
    if (lib.isEmpty()) { Text("Pattern packs are unavailable.", style = Type.small, color = p.rec); return }
    var pick = vm.patternPick.let { if (it.part !in parts) it.copy(part = parts.first()) else it }
    val g = lib[pick.genre.coerceIn(0, lib.size - 1)]
    var prompt by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.weight(1f).clip(RoundedCornerShape(4.dp)).background(p.inset).padding(9.dp)) {
                if (prompt.isEmpty()) Text("Describe it: \"rock beat 120\", \"country band in G\"", style = Type.small, color = p.textFaint)
                BasicTextField(prompt, { prompt = it.take(80) }, singleLine = true, textStyle = Type.body.copy(color = p.text), cursorBrush = SolidColor(p.accent), modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Pattern prompt" }) }
            ToolButton("Go", icon = Icons.Default.AutoAwesome) { vm.applyPrompt(prompt)?.let { pk -> val fixed = if (pk.part !in parts) pk.copy(part = parts.first()) else pk; vm.patternPick = fixed; onAction(fixed) } }
        }
        vm.patternPromptInfo?.let { Text("Understood: $it (offline keyword match)", style = Type.label, color = p.textDim) }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            lib.forEachIndexed { i, ge -> ToolButton(ge.name, active = i == pick.genre) { vm.patternKey = null; vm.patternPick = pick.copy(genre = i) } } }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            parts.forEach { pt -> ToolButton(pt.label, active = pt == pick.part) { vm.patternPick = pick.copy(part = pt) } } }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(p.inset)) {
            (0 until 5).forEach { i -> val sel = i == pick.index
                Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).background(if (sel) p.accent.copy(alpha = 0.16f) else p.inset).clickable { vm.patternPick = pick.copy(index = i) }.padding(horizontal = 10.dp)
                    .semantics { contentDescription = "Pattern ${i + 1} ${vm.patternName(g, pick.part, i)}" }, verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}", style = Type.mono, color = if (sel) p.accent else p.textFaint, modifier = Modifier.width(20.dp))
                    Text(vm.patternName(g, pick.part, i), style = Type.body, color = if (sel) p.text else p.textDim, maxLines = 1, modifier = Modifier.weight(1f))
                    Text("${PatternGen.bars(g, if (pick.part == PatternPart.BACKING) PatternPart.DRUMS else pick.part, i)} bars", style = Type.label, color = p.textFaint) } } }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Vary", style = Type.small, color = p.textDim)
            Switch(pick.vary, { vm.patternPick = pick.copy(vary = it) }, colors = SwitchDefaults.colors(checkedTrackColor = p.accent))
            ToolButton("Shuffle", icon = Icons.Default.Shuffle) { val s = pick.shuffled(); vm.patternPick = s; onAction(s) }
            Spacer(Modifier.weight(1f))
            PrimaryButton(actionLabel, icon = Icons.Default.PlaylistAdd) { onAction(pick) }
        }
        Text("${g.name} · ${vm.patternKey ?: g.key} · suggested ${g.tempo.toInt()} BPM · plays at the project tempo (${vm.project.tempo.toInt()} BPM)", style = Type.label, color = p.textFaint)
    }
}

@Composable fun PatternDialog(vm: StudioVM, onDismiss: () -> Unit) {
    val p = LocalPalette.current
    Dialog(onDismiss) { Column(Modifier.clip(RoundedCornerShape(10.dp)).background(p.panel).padding(14.dp).widthIn(max = 420.dp).verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) { Text("Genre patterns", style = Type.title, color = p.text, modifier = Modifier.weight(1f)); IconBtn(Icons.Default.Close, "Close patterns") { onDismiss() } }
        Spacer(Modifier.height(8.dp))
        val bar = vm.position / vm.project.samplesPerBar + 1
        PatternPicker(vm, "Place at bar $bar") { pick -> vm.placePattern(pick) }
    } }
}
