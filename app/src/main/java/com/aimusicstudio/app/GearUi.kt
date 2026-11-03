package com.aimusicstudio.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/** Realistic, turnable chicken-head / skirted knob. Drag up/down or around to turn; 0..1. */
@Composable
fun GearKnob(label: String, v: Float, size: Dp = 40.dp, cap: Color = Color(0xFF1A1A1A), labelColor: Color = Color(0xFFE8E0C8), onChange: (Float) -> Unit) {
    val cur by rememberUpdatedState(v); val cb by rememberUpdatedState(onChange)
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 3.dp)) {
        Canvas(Modifier.size(size).pointerInput(Unit) { detectDragGestures { ch, d -> ch.consume(); cb((cur + (d.x - d.y) / 220f).coerceIn(0f, 1f)) } }
            .semantics { contentDescription = label; stateDescription = "${(v * 10).roundToInt()} of 10"
                setProgress { x -> onChange(x.coerceIn(0f, 1f)); true }; progressBarRangeInfo = ProgressBarRangeInfo(v, 0f..1f) }) {
            val r = this.size.minDimension / 2; val c = center
            for (k in 0..10) { val ang = Math.toRadians(135.0 + 27.0 * k); drawLine(labelColor.copy(alpha = 0.6f), Offset(c.x + (r * 0.86f * Math.cos(ang)).toFloat(), c.y + (r * 0.86f * Math.sin(ang)).toFloat()),
                Offset(c.x + (r * Math.cos(ang)).toFloat(), c.y + (r * Math.sin(ang)).toFloat()), 1.5f) }
            drawCircle(Brush.radialGradient(listOf(Color(0xFF606060), Color(0xFF101010)), c, r * 0.8f), r * 0.78f, c)
            drawCircle(Brush.radialGradient(listOf(cap.copy(alpha = 1f).let { Color(it.red * 1.4f.coerceAtMost(1f), it.green, it.blue) }, cap), Offset(c.x - r * 0.2f, c.y - r * 0.2f), r * 0.6f), r * 0.58f, c)
            rotate(-135f + 270f * v, c) { drawLine(Color.White, c, Offset(c.x, c.y - r * 0.72f), 3.5f) }
        }
        Text(label.uppercase(), color = labelColor, fontSize = 8.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

data class AmpLook(val tolex: Color, val panel: Color, val grill: Color, val logo: String, val knobCap: Color)
fun ampLook(a: AmpModel): AmpLook { val n = a.label
    return when {
        n.contains("Fender") || a == AmpModel.TWEED || a == AmpModel.CLEAN -> AmpLook(if (a == AmpModel.TWEED) Color(0xFFC8A86A) else Color(0xFF1C1C1C), Color(0xFFD8D4C8), Color(0xFF8C8A84), "Fender-style", Color(0xFF111111))
        n.contains("Marshall") || a == AmpModel.PLEXI || a == AmpModel.BRIT -> AmpLook(Color(0xFF121212), Color(0xFFC9A33A), Color(0xFF2B2B2B), "Marshall-style", Color(0xFF151515))
        n.contains("Mesa") || a == AmpModel.RECTO -> AmpLook(Color(0xFF151515), Color(0xFF8C8C8C), Color(0xFF3A3A3A), "Mesa-style", Color(0xFF222222))
        n.contains("Orange") -> AmpLook(Color(0xFFE56A12), Color(0xFFEFEFEF), Color(0xFF3C2A1C), "Orange-style", Color(0xFF111111))
        n.contains("Ampeg") || a == AmpModel.BASS -> AmpLook(Color(0xFF151515), Color(0xFF6E7BA0), Color(0xFF2A2A2A), "Ampeg-style", Color(0xFF111111))
        n.contains("Roland") || a == AmpModel.JAZZ -> AmpLook(Color(0xFF1A1A1A), Color(0xFF2C2C2C), Color(0xFF555555), "Roland-style", Color(0xFF333333))
        a == AmpModel.CHIME -> AmpLook(Color(0xFF181818), Color(0xFF7A2A20), Color(0xFFB59A6A), "Vox-style", Color(0xFFDDDDDD))
        else -> AmpLook(Color(0xFF181818), Color(0xFF3A3A3A), Color(0xFF2A2A2A), n.substringBefore("-style").substringBefore(" ").ifBlank { "Spark" }, Color(0xFF222222))
    } }

/** Canvas-drawn amp head on a cab with six turnable knobs. */
@Composable
fun VisualAmp(t: Track, onKnob: (String, Float) -> Unit, onPick: () -> Unit) {
    val look = ampLook(t.amp)
    Column(Modifier.widthIn(min = 320.dp).clip(RoundedCornerShape(10.dp)).background(look.tolex).border(2.dp, Color.Black, RoundedCornerShape(10.dp)).padding(6.dp)) {
        // head: control panel
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(Brush.verticalGradient(listOf(look.panel, look.panel.copy(alpha = 0.8f)))).padding(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(look.logo, color = Color.Black, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text("${t.amp.label} ▾", color = Color.Black, fontSize = 10.sp, modifier = Modifier.clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = 0.5f)).clickable(onClickLabel = "Change amp model") { onPick() }.padding(horizontal = 6.dp, vertical = 4.dp))
                Box(Modifier.padding(start = 6.dp).size(10.dp).clip(RoundedCornerShape(5.dp)).background(if (t.amp != AmpModel.NONE) Color(0xFFFF3B30) else Color(0xFF552222)))
            }
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                listOf("Gain" to t.ampGain, "Bass" to t.ampBass, "Mid" to t.ampMid, "Treble" to t.ampTreble, "Presence" to t.ampPresence, "Master" to t.ampMaster).forEach { (l, v) ->
                    GearKnob(l, v, 42.dp, look.knobCap, Color.Black) { onKnob(l, it) } }
            }
        }
        // cab grill cloth
        Canvas(Modifier.fillMaxWidth().height(70.dp).padding(top = 6.dp)) {
            drawRoundRect(look.grill, cornerRadius = CornerRadius(6f))
            var y = 0f; while (y < size.height) { drawLine(Color.Black.copy(alpha = 0.25f), Offset(0f, y), Offset(size.width, y), 1f); y += 4f }
            var x = 0f; while (x < size.width) { drawLine(Color.White.copy(alpha = 0.06f), Offset(x, 0f), Offset(x, size.height), 1f); x += 4f }
            val sp = if (t.amp.label.contains("4x12") || t.amp.label.contains("8x10")) 4 else 2
            for (k in 0 until sp) drawCircle(Color.Black.copy(alpha = 0.18f), size.height * 0.38f, Offset(size.width * (k + 0.5f) / sp, size.height / 2), style = Stroke(3f))
        }
    }
}

/** Stompbox: painted enclosure, LED, 2 knobs (Level/Amount + Tone), metal footswitch. */
@Composable
fun VisualPedal(p: PedalSlot, onAmount: (Float) -> Unit, onTone: (Float) -> Unit, onToggle: () -> Unit, extra: @Composable () -> Unit = {}) {
    val c = Color(p.type.color); val ink = if (c.red * 0.3f + c.green * 0.59f + c.blue * 0.11f > 0.55f) Color.Black else Color.White
    Column(Modifier.padding(4.dp).width(108.dp).clip(RoundedCornerShape(10.dp)).background(Brush.verticalGradient(listOf(c, c.copy(alpha = if (p.on) 0.85f else 0.45f))))
        .border(2.dp, Color(0xFF888888), RoundedCornerShape(10.dp)).padding(5.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(9.dp).clip(RoundedCornerShape(5.dp)).background(if (p.on) Color(0xFFFF2020) else Color(0xFF401010)))
        Row { GearKnob("Level", p.amount, 34.dp, Color(0xFF111111), ink, onAmount); GearKnob("Tone", p.tone, 34.dp, Color(0xFF111111), ink, onTone) }
        Text(p.type.label.substringBefore("-style").substringBefore(" ").uppercase(), color = ink, fontWeight = FontWeight.ExtraBold, fontSize = 11.sp, maxLines = 1)
        Text(p.type.label, color = ink.copy(alpha = 0.7f), fontSize = 7.sp, maxLines = 1)
        Canvas(Modifier.padding(top = 4.dp).size(38.dp).clickable(onClickLabel = "Stomp footswitch") { onToggle() }
            .semantics { contentDescription = "${p.type.label} footswitch"; stateDescription = if (p.on) "on" else "bypassed"; role = Role.Switch }) {
            drawCircle(Brush.radialGradient(listOf(Color(0xFFF0F0F0), Color(0xFF707070)), center, size.minDimension / 2), size.minDimension / 2)
            drawCircle(Color(0xFF505050), size.minDimension / 3, style = Stroke(2f))
        }
        extra()
    }
}

/** Instrument silhouette (guitar/bass body or synth keyboard). Tap to pick brand/model. */
@Composable
fun VisualInstrument(t: Track, selected: Boolean, guitar: GuitarModel?, label: String, onClick: () -> Unit) {
    val bass = guitar?.label?.contains("Bass") == true
    val finish = when (guitar) { null -> Color(0xFF2A2A2A); GuitarModel.LESPAUL, GuitarModel.PRS -> Color(0xFFC77A1E); GuitarModel.STRAT, GuitarModel.JAZZMASTER -> Color(0xFFB0281E)
        GuitarModel.TELE -> Color(0xFFE7C77A); GuitarModel.SG -> Color(0xFF7A1A12); GuitarModel.ACOUSTIC, GuitarModel.TAYLOR, GuitarModel.NYLON -> Color(0xFFD9A55E)
        GuitarModel.SUPERSTRAT, GuitarModel.JACKSON, GuitarModel.ESP, GuitarModel.SEVEN -> Color(0xFF2747A8); else -> if (bass) Color(0xFF3A6E4A) else Color(0xFF8E3A9E) }
    Column(Modifier.padding(3.dp).width(84.dp).clip(RoundedCornerShape(8.dp)).background(if (selected) Color(0xFF3A2A12) else Color(0xFF202126))
        .border(2.dp, if (selected) Orange else Color.Transparent, RoundedCornerShape(8.dp)).clickable(onClickLabel = "Select $label") { onClick() }.padding(4.dp)
        .semantics(mergeDescendants = true) { role = Role.RadioButton; this.selected = selected }, horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.size(76.dp, 54.dp)) {
            val w = size.width; val h = size.height
            if (guitar == null) { // synth keyboard
                drawRoundRect(finish, Offset(0f, h * 0.15f), Size(w, h * 0.75f), CornerRadius(6f))
                for (k in 0 until 3) drawCircle(Orange, 3.5f, Offset(w * (0.15f + 0.12f * k), h * 0.28f))
                val kw = w / 10; for (k in 0 until 10) drawRect(Color.White, Offset(k * kw + 1, h * 0.45f), Size(kw - 2, h * 0.42f))
                listOf(0, 1, 3, 4, 5, 7, 8).forEach { k -> drawRect(Color.Black, Offset((k + 0.65f) * kw, h * 0.45f), Size(kw * 0.6f, h * 0.25f)) }
            } else {
                val body = Path().apply { moveTo(w * 0.05f, h * 0.5f); cubicTo(w * 0.0f, h * 0.05f, w * 0.35f, h * 0.0f, w * 0.38f, h * 0.25f); cubicTo(w * 0.42f, h * 0.38f, w * 0.48f, h * 0.36f, w * 0.52f, h * 0.38f)
                    lineTo(w * 0.52f, h * 0.62f); cubicTo(w * 0.48f, h * 0.64f, w * 0.42f, h * 0.62f, w * 0.38f, h * 0.75f); cubicTo(w * 0.35f, h * 1.0f, w * 0.0f, h * 0.95f, w * 0.05f, h * 0.5f); close() }
                drawPath(body, finish); drawPath(body, Color.Black, style = Stroke(2f))
                drawRect(Color(0xFF5A3A1A), Offset(w * 0.45f, h * 0.45f), Size(w * 0.45f, h * 0.1f)) // neck
                drawRoundRect(Color(0xFF2A2A2A), Offset(w * 0.88f, h * 0.38f), Size(w * 0.11f, h * 0.24f), CornerRadius(3f)) // headstock
                if (guitar.body > 0.8f) drawCircle(Color.Black, h * 0.12f, Offset(w * 0.3f, h * 0.5f)) else for (k in 0 until 2) drawRect(Color(0xFFEEEEEE), Offset(w * (0.18f + 0.1f * k), h * 0.4f), Size(w * 0.05f, h * 0.2f))
                val strings = if (bass) 4 else 6; for (k in 0 until strings) { val y = h * (0.46f + 0.08f / strings * k); drawLine(Color(0xFFDDDDDD), Offset(w * 0.15f, y), Offset(w * 0.95f, y), 0.8f) }
            }
        }
        Text(label, color = Color.White, fontSize = 9.sp, maxLines = 2, lineHeight = 10.sp)
    }
}

/** Selectable drum kit card picker (one kit at a time). */
@Composable
fun KitSelector(current: DrumKit, onPick: (DrumKit) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
        Text("KIT", color = Orange, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 6.dp))
        DrumKit.values().forEach { k -> val on = k == current
            Column(Modifier.padding(2.dp).width(66.dp).clip(RoundedCornerShape(8.dp)).background(if (on) Color(0xFF3A2A12) else Color(0xFF202126))
                .border(2.dp, if (on) Orange else Color.Transparent, RoundedCornerShape(8.dp)).clickable(onClickLabel = "Select ${k.label} kit") { onPick(k) }.padding(3.dp)
                .semantics(mergeDescendants = true) { role = Role.RadioButton; selected = on }, horizontalAlignment = Alignment.CenterHorizontally) {
                Canvas(Modifier.size(54.dp, 30.dp)) { val w = size.width; val h = size.height
                    val shell = when (k) { DrumKit.TR808, DrumKit.VINTAGE909, DrumKit.ELECTRONIC, DrumKit.HOUSE, DrumKit.TRAP, DrumKit.DNB -> Color(0xFF444C5C); DrumKit.JAZZ_BRUSH, DrumKit.ACOUSTIC -> Color(0xFFB0782E); DrumKit.METAL -> Color(0xFF1A1A1A); else -> Color(0xFF9E1E1E) }
                    drawCircle(Color(0xFFC9A93A), h * 0.18f, Offset(w * 0.12f, h * 0.2f)); drawCircle(Color(0xFFC9A93A), h * 0.2f, Offset(w * 0.88f, h * 0.18f))
                    drawCircle(shell, h * 0.42f, Offset(w * 0.5f, h * 0.55f)); drawCircle(Color(0xFFEEEEEE), h * 0.3f, Offset(w * 0.5f, h * 0.55f))
                    drawCircle(shell, h * 0.2f, Offset(w * 0.25f, h * 0.55f)); drawCircle(shell, h * 0.2f, Offset(w * 0.75f, h * 0.55f)) }
                Text(k.label, color = if (on) Orange else Color.White, fontSize = 9.sp, maxLines = 1)
            } }
    }
}

/** Live vocal strip: tempo, vocal range, current lyric line synced to the playhead. */
fun vocalRange(t: Track): String { val base = if (t.voiceGender == VoiceGender.MALE) 0 else if (t.voiceGender == VoiceGender.FEMALE) 9 else 5
    val shift = ((t.vocalStyle.pitch - 1f) * 12).roundToInt(); val lo = 45 + base + shift; val hi = lo + 19
    fun nm(p: Int) = Tuner.names[((p % 12) + 12) % 12] + (p / 12 - 1)
    return "${t.voiceGender.label} · ${t.vocalStyle.label} · ${nm(lo)}–${nm(hi)}" }
fun currentLyric(t: Track, playhead: Double, barSec: Double): Pair<Int, String> {
    val lines = t.lyrics.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("[") }
    if (lines.isEmpty()) return -1 to "—"
    val clips = t.clips.sortedBy { it.startTime }
    val idx = if (clips.isNotEmpty()) clips.indexOfLast { it.startTime <= playhead + 0.05 } else (playhead / (barSec * 2)).toInt()
    return if (idx < 0) -1 to lines.first() else idx.coerceAtMost(lines.lastIndex) to lines[idx.coerceAtMost(lines.lastIndex)]
}
@Composable
fun VocalLiveStrip(vm: StudioViewModel, t: Track, compact: Boolean = false) {
    val (i, line) = currentLyric(t, vm.playhead, vm.session.barSeconds)
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Color(0xFF101114)).border(1.dp, Teal.copy(alpha = 0.5f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 3.dp)
        .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.CenterVertically) {
        Text("♩${vm.session.tempoBPM}", color = Orange, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        if (!compact) Text("  ${vocalRange(t)}", color = Teal, fontSize = 10.sp, maxLines = 1)
        Text("  ${if (vm.isPlaying && i >= 0) "▶ " else ""}$line", color = if (vm.isPlaying) Color.White else Color(0xFF8A8F98), fontSize = if (compact) 10.sp else 12.sp, maxLines = 1, modifier = Modifier.weight(1f))
    }
}
