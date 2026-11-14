package com.studiospark.app.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
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
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.studiospark.app.dsp.*
import kotlin.math.*

/**
 * The AmpliTube-style rig editor: signal chain strip, photoreal gear view with live knobs, gear browser, presets, tuner, I/O gain.
 * All stages are real DSP (see dsp/Effects.kt, dsp/AmpCab.kt). Used inside the upload / place-audio flow for every instrument.
 */
@Composable fun RigEditor(vm: StudioVM, rig: Rig, family: Family, onStructure: () -> Unit, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    var tick by remember { mutableIntStateOf(0) }
    var selected by remember(rig) { mutableStateOf(rig.slots.firstOrNull { it.gear.kind == GearKind.AMP }?.uid ?: rig.slots.firstOrNull()?.uid) }
    var browser by remember { mutableStateOf(false) }
    var tunerOpen by remember { mutableStateOf(false) }
    val changed: () -> Unit = { tick++ }
    @Suppress("UNUSED_EXPRESSION") tick
    Column(modifier.chrome(p)) {
        // top bar: presets, I/O, tuner, power
        Row(Modifier.fillMaxWidth().background(Color(0xFF0F1012)).padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconBtn(Icons.Default.PowerSettingsNew, if (rig.power) "Rig power on" else "Rig power off", tint = if (rig.power) p.accent else p.textFaint) { rig.power = !rig.power; changed() }
            Dropdown("Preset", rig.presetName, RigPresets.factory.sortedBy { if (it.family == family) 0 else 1 } + vm.userPresets(), { (if (it.family == family) "★ " else "") + it.name }, Modifier.weight(1f)) { pr ->
                val n = pr.build(); rig.slots.clear(); rig.slots.addAll(n.slots); rig.presetName = n.presetName; rig.inputGainDb = n.inputGainDb; rig.outputGainDb = n.outputGainDb; selected = rig.slots.firstOrNull { it.gear.kind == GearKind.AMP }?.uid ?: rig.slots.firstOrNull()?.uid; onStructure(); changed() }
            IconBtn(Icons.Outlined.Save, "Save preset") { vm.saveUserPreset(rig); changed() }
            IconBtn(Icons.Outlined.Tune, "Tuner", tint = if (tunerOpen) p.accent else null) { tunerOpen = !tunerOpen; if (tunerOpen) vm.withMic { vm.tunerOn = true; vm.publish() } else { vm.tunerOn = false; vm.publish() } }
        }
        if (tunerOpen) TunerPanel(vm)
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Knob(dbNorm(rig.inputGainDb), { rig.inputGainDb = normDb(it); changed() }, "Input", String.format("%+.1f dB", rig.inputGainDb), size = 40.dp, style = 1, default = 0.5f)
            Spacer(Modifier.weight(1f))
            Text("SIGNAL CHAIN", style = Type.label, color = p.textFaint)
            Spacer(Modifier.weight(1f))
            Knob(dbNorm(rig.outputGainDb), { rig.outputGainDb = normDb(it); changed() }, "Output", String.format("%+.1f dB", rig.outputGainDb), size = 40.dp, style = 1, default = 0.5f)
        }
        ChainStrip(rig, selected, onSelect = { selected = it }, onToggle = { s -> s.enabled = !s.enabled; changed() }, onAdd = { browser = true })
        Spacer(Modifier.height(8.dp))
        val slot = rig.slots.firstOrNull { it.uid == selected }
        if (slot == null) EmptyState("Empty chain", "Add stomps, an amp, a cab and rack processors from the gear browser.", action = { PrimaryButton("Open gear browser", icon = Icons.Default.Add) { browser = true } })
        else {
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(slot.gear.name, style = Type.title, color = Color.White, modifier = Modifier.weight(1f))
                IconBtn(Icons.Default.ChevronLeft, "Move earlier") { val i = rig.slots.indexOf(slot); if (i > 0) { rig.move(slot.uid, i - 1); onStructure(); changed() } }
                IconBtn(Icons.Default.ChevronRight, "Move later") { val i = rig.slots.indexOf(slot); if (i < rig.slots.size - 1) { rig.move(slot.uid, i + 1); onStructure(); changed() } }
                IconBtn(Icons.Outlined.Delete, "Remove from chain") { rig.remove(slot.uid); selected = rig.slots.firstOrNull()?.uid; onStructure(); changed() }
            }
            Box(Modifier.fillMaxWidth().padding(10.dp)) {
                when (slot.gear.kind) {
                    GearKind.STOMP -> PedalView(slot, changed)
                    GearKind.AMP -> AmpView(slot, changed)
                    GearKind.CAB -> CabView(slot, changed)
                    GearKind.RACK -> RackView(slot, changed)
                }
            }
        }
    }
    if (browser) GearBrowser(onDismiss = { browser = false }) { def -> val idx = rig.slots.indexOfFirst { it.uid == selected }.let { if (it < 0) rig.slots.size else it + 1 }
        val s = rig.add(def.id, idx); selected = s.uid; browser = false; onStructure(); changed() }
}
private fun dbNorm(db: Float) = ((db + 24f) / 48f).coerceIn(0f, 1f)
private fun normDb(v: Float) = v * 48f - 24f

@Composable private fun ChainStrip(rig: Rig, selected: Long?, onSelect: (Long) -> Unit, onToggle: (RigSlot) -> Unit, onAdd: () -> Unit) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        JackChip("IN")
        rig.slots.forEach { s -> Cable(); val sel = s.uid == selected
            Column(Modifier.width(78.dp).clip(RoundedCornerShape(6.dp)).background(if (sel) p.accent.copy(alpha = 0.16f) else Color(0x22FFFFFF)).border(1.dp, if (sel) p.accent else Color(0x33FFFFFF), RoundedCornerShape(6.dp))
                .semantics { contentDescription = "Chain ${s.gear.name}" }.clickable { onSelect(s.uid) }.padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.height(40.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Photo(s.gear.art, Modifier.fillMaxHeight(), desc = null)
                    if (s.gear.kind == GearKind.STOMP) Box(Modifier.matchParentSize().background(Color(s.gear.accent).copy(alpha = 0.35f)))
                }
                Text(s.gear.name, style = Type.label.copy(fontSize = 8.sp, letterSpacing = 0.sp), color = Color.White, maxLines = 1, textAlign = TextAlign.Center)
                Box(Modifier.padding(top = 4.dp).size(12.dp).clip(CircleShape).background(if (s.enabled) Color(0xFF4CD964) else Color(0xFF3A3A3A)).clickable { onToggle(s) }.semantics { contentDescription = if (s.enabled) "Bypass ${s.gear.name}" else "Enable ${s.gear.name}" })
            } }
        Cable(); JackChip("OUT"); Spacer(Modifier.width(8.dp))
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)).background(p.accent.copy(alpha = 0.2f)).border(1.dp, p.accent, RoundedCornerShape(6.dp)).clickable(onClick = onAdd).semantics { contentDescription = "Add gear" }, contentAlignment = Alignment.Center) { Icon(Icons.Default.Add, null, tint = p.accent) }
    }
}
@Composable private fun JackChip(t: String) { Box(Modifier.size(38.dp).clip(CircleShape).background(Brush.radialGradient(listOf(Color(0xFF9AA0A6), Color(0xFF2A2C2F)))), contentAlignment = Alignment.Center) { Text(t, style = Type.label, color = Color.White) } }
@Composable private fun Cable() { Canvas(Modifier.width(18.dp).height(10.dp)) { drawLine(Color(0xFF111111), Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 6f); drawLine(Color(0xFF3A3A3A), Offset(0f, size.height / 2 - 1), Offset(size.width, size.height / 2 - 1), 2f) } }

@Composable fun GearKnobs(slot: RigSlot, changed: () -> Unit, style: Int, size: androidx.compose.ui.unit.Dp, labelColor: Color = Color.White, keys: List<Int>? = null) {
    val defs = slot.gear.params
    FlowRowCompat {
        (keys ?: defs.indices.toList()).forEach { i -> val d = defs[i]
            if (d.steps != null) StepSwitch(d, slot.values[i], labelColor) { slot.values[i] = it; changed() }
            else Knob(d.norm(slot.values[i]), { slot.values[i] = d.denorm(it); changed() }, d.label, d.format(slot.values[i]), size = size, style = style, default = d.norm(d.default), labelColor = labelColor) }
    }
}
@Composable fun FlowRowCompat(content: @Composable () -> Unit) {
    androidx.compose.ui.layout.Layout(content) { measurables, c ->
        val gap = 6.dp.roundToPx(); val ps = measurables.map { it.measure(c.copy(minWidth = 0, minHeight = 0)) }
        var x = 0; var y = 0; var rowH = 0; val pos = ps.map { pl -> if (x + pl.width > c.maxWidth && x > 0) { x = 0; y += rowH + gap; rowH = 0 }; val o = x to y; x += pl.width + gap; rowH = max(rowH, pl.height); o }
        layout(c.maxWidth, y + rowH) { ps.forEachIndexed { i, pl -> pl.place(pos[i].first, pos[i].second) } }
    }
}
@Composable private fun StepSwitch(d: ParamDef, v: Float, labelColor: Color, onPick: (Float) -> Unit) {
    val p = LocalPalette.current
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 4.dp)) {
        Row(Modifier.clip(RoundedCornerShape(4.dp)).background(Color(0xFF111214))) { d.steps!!.forEachIndexed { i, s -> val sel = v.roundToInt() == i
            Box(Modifier.clickable { onPick(i.toFloat()) }.background(if (sel) p.accent else Color.Transparent).padding(horizontal = 8.dp, vertical = 6.dp)) { Text(s.substringBefore(' '), style = Type.label, color = if (sel) Color.Black else Color(0xFFBBBBBB)) } } }
        Spacer(Modifier.height(4.dp)); Text(d.label.uppercase(), style = Type.label, color = labelColor)
    }
}

/** Pedal on a wooden pedalboard: tinted photographic enclosure, silver knobs, footswitch bypass, LED. */
@Composable fun PedalView(slot: RigSlot, changed: () -> Unit) {
    val p = LocalPalette.current; val accent = Color(slot.gear.accent)
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Brush.verticalGradient(listOf(Color(0xFF6B4A2E), Color(0xFF4A321F), Color(0xFF5E4029)))).padding(16.dp), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 360.dp).fillMaxWidth().shadow(14.dp, RoundedCornerShape(10.dp)).clip(RoundedCornerShape(10.dp))
            .background(Brush.verticalGradient(listOf(accent.lighten(0.15f), accent, accent.darken(0.25f)))).border(2.dp, Color(0x44000000), RoundedCornerShape(10.dp)).padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val lc = if (accent.luminance() > 0.5f) Color(0xFF151515) else Color.White
            GearKnobs(slot, changed, 1, 52.dp, lc)
            Spacer(Modifier.height(10.dp))
            Box(Modifier.size(10.dp).clip(CircleShape).background(if (slot.enabled) Color(0xFFFF3B30) else Color(0xFF4A1A18)))
            Spacer(Modifier.height(8.dp))
            Text(slot.gear.name, style = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic), color = lc)
            Text(slot.gear.category.uppercase(), style = Type.label, color = lc.copy(alpha = 0.7f))
            Spacer(Modifier.height(12.dp))
            Box(Modifier.size(56.dp).shadow(6.dp, CircleShape).clip(CircleShape).background(Brush.radialGradient(listOf(Color(0xFFF2F4F6), Color(0xFF8A9096), Color(0xFF3E4246)))).clickable { slot.enabled = !slot.enabled; changed() }
                .semantics { contentDescription = "Footswitch ${slot.gear.name}" }, contentAlignment = Alignment.Center) { Box(Modifier.size(30.dp).clip(CircleShape).background(Brush.radialGradient(listOf(Color(0xFFD6DADF), Color(0xFF6E747A))))) }
        }
    }
}

/** Amp: photographic cabinet/head header and an original faceplate with black knurled knobs and channel switch. */
@Composable fun AmpView(slot: RigSlot, changed: () -> Unit) {
    val g = slot.gear; val accent = Color(g.accent)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(0xFF0D0D0E))) {
        Box(Modifier.fillMaxWidth().height(120.dp).background(Brush.verticalGradient(listOf(Color(0xFF26282B), Color(0xFF111214)))), contentAlignment = Alignment.Center) {
            Photo(g.art, Modifier.fillMaxHeight().padding(8.dp), desc = g.name) }
        // faceplate
        Column(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(accent.lighten(0.1f), accent.darken(0.2f)))).padding(4.dp)) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(Brush.verticalGradient(listOf(Color(0xFFF1EEE6), Color(0xFFD7D2C6)))).padding(horizontal = 12.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(g.name.uppercase(), style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp), color = Color(0xFF1A1A1A), modifier = Modifier.weight(1f))
                    Column(horizontalAlignment = Alignment.End) { Text(g.style?.label?.uppercase() ?: "", style = Type.label, color = Color(0xFF555555)); Text(g.era.uppercase(), style = Type.label, color = Color(0xFF777777)) }
                }
                Spacer(Modifier.height(8.dp)); Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF9C9686))); Spacer(Modifier.height(8.dp))
                GearKnobs(slot, changed, 0, 50.dp, Color(0xFF222222))
            }
        }
        Text(g.blurb, style = Type.small, color = Color(0xFF9AA0A6), modifier = Modifier.padding(10.dp))
    }
}

/** Speaker + movable microphone (drag the mic across the cone; vertical slider sets distance). Audibly changes the cab filter. */
@Composable fun CabView(slot: RigSlot, changed: () -> Unit) {
    val p = LocalPalette.current; val v = slot.values
    var t by remember { mutableIntStateOf(0) }; @Suppress("UNUSED_EXPRESSION") t
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(0xFF0B0B0C)).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.weight(1f).aspectRatio(1f).semantics { contentDescription = "Speaker with movable mic" }.pointerInput(slot.uid) {
                fun set(o: Offset) { val c = Offset(size.width / 2f, size.height / 2f); val r = size.width * 0.45f; v[0] = ((o - c).getDistance() / r).coerceIn(0f, 1f); changed(); t++ }
                detectDragGestures(onDragStart = { set(it) }) { ch, _ -> ch.consume(); set(ch.position) } }) {
                val c = center; val r = size.width * 0.45f
                drawCircle(Brush.radialGradient(listOf(Color(0xFF2A2A2C), Color(0xFF050505)), c, r * 1.1f), r * 1.08f, c)
                drawCircle(Brush.radialGradient(0f to Color(0xFF55575A), 0.18f to Color(0xFF2B2C2E), 0.2f to Color(0xFF151516), 0.85f to Color(0xFF2E2F31), 1f to Color(0xFF0E0E0F), center = c, radius = r), r, c)
                for (i in 1..6) drawCircle(Color(0x22FFFFFF), r * (0.25f + i * 0.11f), c, style = Stroke(1f))
                drawCircle(Brush.radialGradient(listOf(Color(0xFF8E9298), Color(0xFF2F3134)), c - Offset(r * 0.05f, r * 0.05f), r * 0.2f), r * 0.17f, c)
                // dashed guide and mic
                val ang = -PI / 4; val mx = c.x + cos(ang).toFloat() * r * v[0]; val my = c.y + sin(ang).toFloat() * r * v[0]
                drawLine(p.accent.copy(alpha = 0.6f), c, Offset(mx, my), 2f, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
                val mr = r * (0.12f - v[1] * 0.05f)
                drawCircle(Color(0x88000000), mr * 1.4f, Offset(mx + 4, my + 6)); drawCircle(Brush.radialGradient(listOf(Color(0xFFE0E3E6), Color(0xFF5B6066)), Offset(mx - mr * 0.3f, my - mr * 0.3f), mr * 1.6f), mr, Offset(mx, my))
                drawCircle(p.accent, mr * 1.2f, Offset(mx, my), style = Stroke(2f))
            }
            Spacer(Modifier.width(12.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("DISTANCE", style = Type.label, color = Color(0xFF9AA0A6))
                Slider(v[1], { v[1] = it; changed(); t++ }, Modifier.width(140.dp).semantics { contentDescription = "Mic distance" }, colors = SliderDefaults.colors(thumbColor = p.accent, activeTrackColor = p.accent))
                Text(if (v[1] < 0.2f) "On grille" else if (v[1] < 0.6f) "${(v[1] * 30).toInt()} cm" else "Room ${(v[1] * 2).let { String.format("%.1f", it) }} m", style = Type.mono, color = Color.White)
            }
        }
        Spacer(Modifier.height(10.dp))
        GearKnobs(slot, changed, 0, 44.dp, Color(0xFFDDDDDD), keys = listOf(2, 3, 4))
        Text("Drag the mic: centre = bright and direct, edge = darker, off-axis. Distance trades proximity bass for room.", style = Type.small, color = Color(0xFF8A9096))
    }
}

/** 500-series rack chassis (StudioSpark branding only). Graphic EQs get vertical sliders; others get knobs and a VU. */
@Composable fun RackView(slot: RigSlot, changed: () -> Unit) {
    val p = LocalPalette.current; val g = slot.gear; val face = Color(g.accent)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(Brush.horizontalGradient(listOf(Color(0xFF3A3D41), Color(0xFF6B6F74), Color(0xFF3A3D41)))).padding(horizontal = 18.dp, vertical = 10.dp)) {
        Row { Text("STUDIOSPARK", style = Type.label, color = Color(0xFF1A1A1A)); Spacer(Modifier.weight(1f)); Text("500 SERIES", style = Type.label, color = Color(0xFF1A1A1A)) }
        Spacer(Modifier.height(6.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(3.dp)).background(Brush.verticalGradient(listOf(face.lighten(0.08f), face.darken(0.15f)))).border(1.dp, Color(0x55000000), RoundedCornerShape(3.dp)).padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val lc = if (face.luminance() > 0.45f) Color(0xFF1A1A1A) else Color.White
            Text(g.name.removePrefix("SparkRack ").uppercase(), style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.SansSerif, letterSpacing = 2.sp), color = lc)
            Spacer(Modifier.height(8.dp))
            if (g.category == "Dynamics") { VuMeter(slot); Spacer(Modifier.height(8.dp)) }
            if (g.id.contains("geq")) Row(Modifier.fillMaxWidth().height(170.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                g.params.forEachIndexed { i, d -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                    VSlider(d.norm(slot.values[i]), { slot.values[i] = d.denorm(it); changed() }, Modifier.weight(1f).semantics { contentDescription = "${d.label} band" }, if (i == g.params.size - 1) p.accent else Color(0xFF6FB3FF))
                    Text(d.label, style = Type.label.copy(fontSize = 8.sp), color = lc) } } }
            else GearKnobs(slot, changed, 1, 46.dp, lc)
        }
    }
}
@Composable private fun VuMeter(slot: RigSlot) {
    Canvas(Modifier.width(150.dp).height(70.dp)) {
        drawRoundRect(Brush.verticalGradient(listOf(Color(0xFFF6E7B8), Color(0xFFE2CC86))), cornerRadius = CornerRadius(6f))
        val c = Offset(size.width / 2, size.height * 1.15f); val r = size.height * 0.95f
        for (i in 0..10) { val a = Math.toRadians((-140 + i * 10).toDouble()); val col = if (i > 7) Color(0xFFB3261E) else Color(0xFF222222); drawLine(col, Offset(c.x + cos(a).toFloat() * r * 0.85f, c.y + sin(a).toFloat() * r * 0.85f), Offset(c.x + cos(a).toFloat() * r, c.y + sin(a).toFloat() * r), 2f) }
        val a = Math.toRadians(-130.0 + (slot.values[0] + 40f) / 40f * 60.0); drawLine(Color(0xFF111111), c, Offset(c.x + cos(a).toFloat() * r, c.y + sin(a).toFloat() * r), 2f)
    }
}
@Composable fun VSlider(v: Float, onChange: (Float) -> Unit, modifier: Modifier, color: Color) {
    val cur by rememberUpdatedState(v)
    Canvas(modifier.width(26.dp).pointerInput(Unit) { detectDragGestures { ch, d -> ch.consume(); onChange((cur - d.y / size.height).coerceIn(0f, 1f)) } }.pointerInput(Unit) { detectTapGestures(onDoubleTap = { onChange(0.5f) }) }) {
        val x = size.width / 2; drawRoundRect(Color(0xFF0B0C0E), Offset(x - 3f, 6f), Size(6f, size.height - 12f), CornerRadius(3f))
        drawLine(Color(0x55FFFFFF), Offset(x - 9f, size.height / 2), Offset(x + 9f, size.height / 2), 1f)
        val y = 6f + (1 - v) * (size.height - 12f)
        drawRoundRect(Brush.verticalGradient(listOf(Color(0xFFE6E8EA), Color(0xFF8A9096)), y - 7f, y + 7f), Offset(x - 11f, y - 7f), Size(22f, 14f), CornerRadius(3f))
        drawLine(color, Offset(x - 9f, y), Offset(x + 9f, y), 2f)
    }
}

@Composable private fun TunerPanel(vm: StudioVM) {
    val p = LocalPalette.current; val r = vm.tuner
    Row(Modifier.fillMaxWidth().background(Color(0xFF08090A)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(r?.note ?: "—", style = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Light), color = if (r != null && abs(r.cents) < 5) p.play else Color.White, modifier = Modifier.width(80.dp))
        Canvas(Modifier.weight(1f).height(36.dp)) {
            for (i in -5..5) { val x = size.width / 2 + i * size.width / 11; drawLine(if (i == 0) p.play else Color(0xFF555555), Offset(x, if (i == 0) 0f else 10f), Offset(x, size.height - if (i == 0) 0f else 10f), if (i == 0) 3f else 1.5f) }
            r?.let { val x = size.width / 2 + (it.cents / 50f).coerceIn(-1f, 1f) * size.width / 2.2f; drawRoundRect(if (abs(it.cents) < 5) p.play else p.accent, Offset(x - 4f, 0f), Size(8f, size.height), CornerRadius(4f)) }
        }
        Text(r?.let { String.format("%+.0f¢\n%.1f Hz", it.cents, it.hz) } ?: "Play a note", style = Type.mono, color = Color(0xFFAAAAAA), modifier = Modifier.width(80.dp), textAlign = TextAlign.End)
    }
}

/** Searchable gear browser: Type / Collection views; stomps by category, amps, cabs, rack. */
@Composable fun GearBrowser(onDismiss: () -> Unit, onPick: (GearDef) -> Unit) {
    val p = LocalPalette.current
    var q by remember { mutableStateOf("") }; var byCollection by remember { mutableStateOf(false) }; var kind by remember { mutableStateOf<GearKind?>(null) }; var cat by remember { mutableStateOf<String?>(null) }
    androidx.compose.ui.window.Dialog(onDismiss, androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxWidth(0.96f).fillMaxHeight(0.88f).clip(RoundedCornerShape(10.dp)).background(Color(0xFF16171A)).padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Text("Gear browser", style = Type.title, color = Color.White, modifier = Modifier.weight(1f)); IconBtn(Icons.Default.Close, "Close") { onDismiss() } }
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Color(0xFF0C0D0F)).padding(horizontal = 10.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Search, null, tint = p.textFaint); Spacer(Modifier.width(8.dp))
                BasicTextField(q, { q = it }, Modifier.weight(1f).semantics { contentDescription = "Search gear" }, textStyle = Type.body.copy(color = Color.White), singleLine = true, cursorBrush = SolidColor(p.accent), decorationBox = { inner -> if (q.isEmpty()) Text("Search gear", style = Type.body, color = p.textFaint); inner() })
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                ToolButton("All", active = kind == null) { kind = null; cat = null }
                GearKind.values().forEach { k -> ToolButton(k.label + "s", active = kind == k) { kind = k; cat = null } }
                Spacer(Modifier.width(10.dp))
                ToolButton("Type", icon = Icons.Outlined.Category, active = !byCollection) { byCollection = false; cat = null }
                ToolButton("Collection", icon = Icons.Outlined.Folder, active = byCollection) { byCollection = true; cat = null }
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.weight(1f)) {
                val groups = if (byCollection) GearCatalog.collections else (if (kind == null || kind == GearKind.STOMP || kind == GearKind.RACK) GearCatalog.stompCategories else emptyList()) + listOf("Amp", "Cab").filter { kind == null || (kind == GearKind.AMP && it == "Amp") || (kind == GearKind.CAB && it == "Cab") }
                Column(Modifier.width(130.dp).verticalScroll(rememberScrollState())) { groups.forEach { gname -> val sel = cat == gname
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(if (sel) p.accent.copy(alpha = 0.18f) else Color.Transparent).clickable { cat = if (sel) null else gname }.padding(horizontal = 10.dp, vertical = 9.dp)) {
                        Text(gname, style = Type.body, color = if (sel) p.accent else Color(0xFFCCCCCC), modifier = Modifier.weight(1f)); Text("›", color = p.textFaint) } } }
                Spacer(Modifier.width(10.dp))
                val items = GearCatalog.search(q, kind, if (!byCollection && cat != null && cat != "Amp" && cat != "Cab") cat else null, if (byCollection) cat else null).filter { cat == null || byCollection || (cat == "Amp" && it.kind == GearKind.AMP) || (cat == "Cab" && it.kind == GearKind.CAB) || (cat != "Amp" && cat != "Cab") }
                LazyVerticalGrid(GridCells.Adaptive(96.dp), Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(items.size) { i -> val g = items[i]
                        Column(Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xFF202226)).clickable { onPick(g) }.padding(8.dp).semantics { contentDescription = "Add ${g.name}" }, horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.height(56.dp).fillMaxWidth(), contentAlignment = Alignment.Center) { Photo(g.art, Modifier.fillMaxHeight()); if (g.kind == GearKind.STOMP) Box(Modifier.matchParentSize().padding(horizontal = 22.dp).background(Color(g.accent).copy(alpha = 0.35f))) }
                            Text(g.name, style = Type.small, color = Color.White, maxLines = 2, textAlign = TextAlign.Center)
                            Text(if (g.kind == GearKind.AMP) "${g.style?.label} · ${g.era}" else g.category, style = Type.label.copy(fontSize = 8.sp), color = p.textFaint, maxLines = 1)
                        } }
                }
            }
        }
    }
}

fun Color.lighten(f: Float) = Color(red + (1 - red) * f, green + (1 - green) * f, blue + (1 - blue) * f, alpha)
fun Color.darken(f: Float) = Color(red * (1 - f), green * (1 - f), blue * (1 - f), alpha)
