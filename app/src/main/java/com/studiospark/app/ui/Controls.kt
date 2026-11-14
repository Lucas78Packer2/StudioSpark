package com.studiospark.app.ui

import android.annotation.SuppressLint
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import kotlin.math.*

private val drawableCache = HashMap<String, Int>()
@SuppressLint("DiscouragedApi")
@Composable fun drawableId(name: String): Int { val ctx = LocalContext.current; return drawableCache.getOrPut(name) { ctx.resources.getIdentifier(name, "drawable", ctx.packageName) } }

/** Lets stateless art composables resolve user-built kits and guitars. */
object KitRegistry { @Volatile var project: com.studiospark.app.core.Project? = null }

/** Photographic gear image from the StudioSpark photo set. */
@Composable fun Photo(name: String, modifier: Modifier = Modifier, scale: ContentScale = ContentScale.Fit, desc: String? = null) {
    // user-built instruments render their own photoreal art everywhere a stock photo would appear
    if (name.startsWith("build:")) { val b = KitRegistry.project?.build(name.removePrefix("build:")); if (b != null) { androidx.compose.foundation.Canvas(modifier) { with(GuitarRender) { drawInstrument(b, androidx.compose.ui.geometry.Offset.Zero, size.width, size.height) } }; return } }
    if (name.startsWith("kit:")) { val k = KitRegistry.project?.let { p -> com.studiospark.app.core.DrumKits.byId(name.removePrefix("kit:"), p.customKits) }; if (k != null) { androidx.compose.foundation.Canvas(modifier) { with(KitRender) { stage(KitView.OVERHEAD); drawKit(k, layout(k, KitView.OVERHEAD, size.width, size.height), KitView.OVERHEAD, null, null) } }; return } }
    val id = drawableId(name); if (id != 0) Image(painterResource(id), desc, modifier, contentScale = scale) else Box(modifier.background(LocalPalette.current.raised))
}

@Composable fun Panel(modifier: Modifier = Modifier, pad: Dp = 12.dp, content: @Composable ColumnScope.() -> Unit) {
    val p = LocalPalette.current
    Column(modifier.clip(RoundedCornerShape(6.dp)).background(p.panel).border(1.dp, p.lineSoft, RoundedCornerShape(6.dp)).padding(pad), content = content)
}

@Composable fun SectionLabel(text: String, modifier: Modifier = Modifier, trailing: (@Composable RowScope.() -> Unit)? = null) {
    val p = LocalPalette.current
    Row(modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text.uppercase(), style = Type.label, color = p.textDim, modifier = Modifier.weight(1f)); trailing?.invoke(this)
    }
}

/** Compact square state button as used in Pro Tools track headers (R, I, S, M). */
@Composable fun StateButton(label: String, on: Boolean, onColor: Color, modifier: Modifier = Modifier, size: Dp = 26.dp, desc: String = label, onClick: () -> Unit) {
    val p = LocalPalette.current
    Box(modifier.size(size).clip(RoundedCornerShape(3.dp)).background(if (on) onColor else p.raised).border(1.dp, if (on) onColor else p.line, RoundedCornerShape(3.dp))
        .semantics { contentDescription = desc }.clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(label, style = Type.small, color = if (on) Color(0xFF111111) else p.textDim)
    }
}

@Composable fun ToolButton(label: String, modifier: Modifier = Modifier, icon: ImageVector? = null, active: Boolean = false, enabled: Boolean = true, accent: Color? = null, onClick: () -> Unit) {
    val p = LocalPalette.current; val c = accent ?: p.accent
    Row(modifier.height(32.dp).clip(RoundedCornerShape(4.dp)).background(if (active) c.copy(alpha = 0.18f) else p.raised).border(1.dp, if (active) c else p.line, RoundedCornerShape(4.dp))
        .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        if (icon != null) Icon(icon, label, tint = if (!enabled) p.textFaint else if (active) c else p.text, modifier = Modifier.size(16.dp))
        if (icon != null && label.isNotEmpty()) Spacer(Modifier.width(6.dp))
        if (label.isNotEmpty()) Text(label, style = Type.small, color = if (!enabled) p.textFaint else if (active) c else p.text, maxLines = 1)
    }
}

@Composable fun PrimaryButton(label: String, modifier: Modifier = Modifier, color: Color? = null, enabled: Boolean = true, icon: ImageVector? = null, onClick: () -> Unit) {
    val p = LocalPalette.current; val c = color ?: p.accent
    Row(modifier.height(40.dp).clip(RoundedCornerShape(5.dp)).background(if (enabled) c else p.raised).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        if (icon != null) { Icon(icon, null, tint = if (enabled) Color(0xFF15120C) else p.textFaint, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)) }
        Text(label, style = Type.bodyStrong, color = if (enabled) Color(0xFF15120C) else p.textFaint)
    }
}

@Composable fun <T> Dropdown(label: String, value: String, options: List<T>, display: (T) -> String, modifier: Modifier = Modifier, onOpen: (() -> Unit)? = null, onPick: (T) -> Unit) {
    val p = LocalPalette.current; var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Column(Modifier.clip(RoundedCornerShape(4.dp)).background(p.inset).border(1.dp, p.line, RoundedCornerShape(4.dp)).clickable { onOpen?.invoke(); open = true }.padding(horizontal = 10.dp, vertical = 6.dp)) {
            if (label.isNotEmpty()) Text(label.uppercase(), style = Type.label, color = p.textFaint)
            Row(verticalAlignment = Alignment.CenterVertically) { Text(value, style = Type.body, color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 240.dp)); Icon(Icons.Default.ArrowDropDown, null, tint = p.textDim) }
        }
        DropdownMenu(open, { open = false }, Modifier.background(p.raised)) { options.forEach { o -> DropdownMenuItem(text = { Text(display(o), style = Type.body, color = p.text) }, onClick = { open = false; onPick(o) }) } }
    }
}

/**
 * Skeuomorphic rotary knob. Vertical drag changes value; a value bubble pops up while turning (AmpliTube-style);
 * double-tap resets. [style]: 0 black chicken-head amp knob, 1 silver pedal knob, 2 cream vintage.
 */
@Composable fun Knob(value: Float, onChange: (Float) -> Unit, label: String, display: String, modifier: Modifier = Modifier, size: Dp = 56.dp, style: Int = 0, default: Float = 0.5f, labelColor: Color? = null) {
    val p = LocalPalette.current
    var dragging by remember { mutableStateOf(false) }
    val cur by rememberUpdatedState(value)
    Column(modifier.width(size + 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(size).semantics { contentDescription = "$label knob $display" }
                .pointerInput(Unit) { detectTapGestures(onDoubleTap = { onChange(default) }) }
                .pointerInput(Unit) { detectVerticalDragGestures(onDragStart = { dragging = true }, onDragEnd = { dragging = false }, onDragCancel = { dragging = false }) { ch, dy -> ch.consume(); onChange((cur - dy / 220f).coerceIn(0f, 1f)) } }) {
                drawKnob(value, style, p)
            }
            if (dragging) Popup(alignment = Alignment.TopCenter, offset = IntOffset(0, -70)) {
                Box(Modifier.shadow(6.dp, RoundedCornerShape(4.dp)).background(Color(0xF0101112), RoundedCornerShape(4.dp)).border(1.dp, p.accent.copy(alpha = 0.6f), RoundedCornerShape(4.dp)).padding(horizontal = 10.dp, vertical = 5.dp)) {
                    Text(display, style = Type.mono.copy(fontSize = 13.sp), color = Color.White)
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(label.uppercase(), style = Type.label, color = labelColor ?: p.textDim, maxLines = 1, textAlign = TextAlign.Center)
    }
}

fun DrawScope.drawKnob(v: Float, style: Int, p: Palette) {
    val r = size.minDimension / 2f; val c = center
    val start = 135f; val sweep = 270f
    // scale ticks
    for (i in 0..10) { val a = Math.toRadians((start + sweep * i / 10f).toDouble()); val r1 = r * 0.92f; val r2 = r * 1.0f
        drawLine(if (style == 2) Color(0xFF3A2E1E) else Color(0xFF8C9096), Offset(c.x + cos(a).toFloat() * r1, c.y + sin(a).toFloat() * r1), Offset(c.x + cos(a).toFloat() * r2, c.y + sin(a).toFloat() * r2), 1.2f) }
    // value arc
    drawArc(p.accent.copy(alpha = 0.85f), start, sweep * v, false, topLeft = Offset(c.x - r * 0.86f, c.y - r * 0.86f), size = Size(r * 1.72f, r * 1.72f), style = Stroke(2.2f, cap = StrokeCap.Round))
    val kr = r * 0.74f
    drawCircle(Color.Black.copy(alpha = 0.55f), kr * 1.04f, c + Offset(0f, kr * 0.12f))
    when (style) {
        1 -> { drawCircle(Brush.sweepGradient(listOf(Color(0xFFE9ECEF), Color(0xFF8D949B), Color(0xFFF5F7F8), Color(0xFF7C838A), Color(0xFFE9ECEF)), c), kr, c)
            drawCircle(Brush.radialGradient(listOf(Color(0xFFF7F8F9), Color(0xFFB5BBC1)), c - Offset(kr * 0.2f, kr * 0.2f), kr), kr * 0.72f, c) }
        2 -> { drawCircle(Brush.radialGradient(listOf(Color(0xFFF3EAD4), Color(0xFFBFAF8A)), c - Offset(kr * 0.3f, kr * 0.3f), kr * 1.4f), kr, c)
            drawCircle(Color(0xFF7A6A4A), kr, c, style = Stroke(1.5f)) }
        else -> { // knurled black amp knob
            drawCircle(Brush.radialGradient(listOf(Color(0xFF3B3E42), Color(0xFF0E0F10)), c - Offset(kr * 0.25f, kr * 0.35f), kr * 1.3f), kr, c)
            for (i in 0 until 36) { val a = i * 10.0 * PI / 180; drawLine(Color(0xFF050505), Offset(c.x + cos(a).toFloat() * kr * 0.88f, c.y + sin(a).toFloat() * kr * 0.88f), Offset(c.x + cos(a).toFloat() * kr, c.y + sin(a).toFloat() * kr), 1.4f) }
            drawCircle(Brush.sweepGradient(listOf(Color(0xFF2A2C2F), Color(0xFF5A5E63), Color(0xFF1C1D1F), Color(0xFF4C5055), Color(0xFF2A2C2F)), c), kr * 0.62f, c) }
    }
    val a = Math.toRadians((start + sweep * v).toDouble())
    val pc = if (style == 0) Color(0xFFF2F2F2) else Color(0xFF1A1A1A)
    drawLine(pc, Offset(c.x + cos(a).toFloat() * kr * 0.2f, c.y + sin(a).toFloat() * kr * 0.2f), Offset(c.x + cos(a).toFloat() * kr * 0.9f, c.y + sin(a).toFloat() * kr * 0.9f), 3f, cap = StrokeCap.Round)
}

/** Console-style fader. value 0..1 maps to −∞…+6 dB through [faderDb]. */
@Composable fun Fader(value: Float, onChange: (Float) -> Unit, modifier: Modifier = Modifier, desc: String = "fader") {
    val p = LocalPalette.current; val cur by rememberUpdatedState(value)
    Canvas(modifier.width(34.dp).semantics { contentDescription = desc }
        .pointerInput(Unit) { detectTapGestures(onDoubleTap = { onChange(dbToFader(0f)) }) }
        .pointerInput(Unit) { detectVerticalDragGestures { ch, dy -> ch.consume(); onChange((cur - dy / size.height).coerceIn(0f, 1f)) } }) {
        val x = size.width / 2; val top = 10f; val bot = size.height - 10f
        drawRoundRect(p.inset, Offset(x - 3f, top), Size(6f, bot - top), CornerRadius(3f))
        for (db in listOf(6f, 0f, -6f, -12f, -24f, -48f)) { val y = bot - dbToFader(db) * (bot - top); drawLine(if (db == 0f) p.textDim else p.textFaint, Offset(x - 14f, y), Offset(x - 7f, y), 1f); drawLine(p.textFaint, Offset(x + 7f, y), Offset(x + 14f, y), 1f) }
        val y = bot - value * (bot - top)
        drawRoundRect(Color.Black.copy(alpha = 0.5f), Offset(x - 15f, y - 9f + 3f), Size(30f, 20f), CornerRadius(3f))
        drawRoundRect(Brush.verticalGradient(listOf(Color(0xFFD9DCDF), Color(0xFF7D838A), Color(0xFFBFC4C9)), y - 10f, y + 10f), Offset(x - 15f, y - 10f), Size(30f, 20f), CornerRadius(3f))
        drawLine(Color(0xFF222222), Offset(x - 13f, y), Offset(x + 13f, y), 2f)
    }
}
fun faderDb(v: Float): Float = if (v <= 0.001f) -96f else if (v >= 0.75f) (v - 0.75f) / 0.25f * 6f else 40f * log10(v / 0.75f) * 1.5f
fun dbToFader(db: Float): Float = if (db <= -95f) 0f else if (db >= 0f) 0.75f + db / 6f * 0.25f else (0.75f * 10f.pow(db / 60f)).coerceIn(0f, 0.75f)

@Composable fun Meter(level: Float, modifier: Modifier = Modifier, vertical: Boolean = true) {
    val p = LocalPalette.current
    Canvas(modifier) {
        drawRect(p.inset)
        val db = 20 * log10(level.coerceAtLeast(1e-5f)); val t = ((db + 60f) / 66f).coerceIn(0f, 1f)
        val brush = if (vertical) Brush.verticalGradient(0f to Color(0xFFE2463F), 0.12f to Color(0xFFE6C84A), 0.3f to Color(0xFF4DBA5E), 1f to Color(0xFF2E8B45), startY = 0f, endY = size.height)
        else Brush.horizontalGradient(0f to Color(0xFF2E8B45), 0.7f to Color(0xFF4DBA5E), 0.88f to Color(0xFFE6C84A), 1f to Color(0xFFE2463F), startX = 0f, endX = size.width)
        if (vertical) drawRect(brush, Offset(0f, size.height * (1 - t)), Size(size.width, size.height * t)) else drawRect(brush, Offset.Zero, Size(size.width * t, size.height))
        if (vertical) for (i in 1 until 12) drawLine(p.inset, Offset(0f, size.height * i / 12f), Offset(size.width, size.height * i / 12f), 1f)
    }
}

@Composable fun Pill(text: String, color: Color, modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(3.dp)).background(color.copy(alpha = 0.16f)).border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(3.dp)).padding(horizontal = 6.dp, vertical = 2.dp)) { Text(text, style = Type.label, color = color) }
}

@Composable fun EmptyState(title: String, body: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    val p = LocalPalette.current
    Column(modifier.fillMaxWidth().padding(vertical = 28.dp, horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = Type.title, color = p.text, textAlign = TextAlign.Center); Spacer(Modifier.height(6.dp))
        Text(body, style = Type.body, color = p.textDim, textAlign = TextAlign.Center); if (action != null) { Spacer(Modifier.height(16.dp)); action() }
    }
}

/** Brushed dark chrome backdrop for the gear rig. */
fun Modifier.chrome(p: Palette) = this.background(Brush.verticalGradient(listOf(p.chromeTop, p.chromeBottom)))
