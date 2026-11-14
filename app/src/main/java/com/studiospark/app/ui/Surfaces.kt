package com.studiospark.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.*
import androidx.compose.ui.unit.*
import com.studiospark.app.core.*
import kotlin.math.*

private val noteNames = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
fun midiName(m: Int) = noteNames[((m % 12) + 12) % 12] + (m / 12 - 1)

/**
 * Playable fretboard. High string on top (tab view). Tap = fretted note, slide vertically = strum across strings.
 * Pitches come from the selected tuning (E2–E4 standard at A4 = 440).
 */
@Composable fun GuitarStrings(tuning: List<Int>, frets: Int = 15, modifier: Modifier = Modifier, bass: Boolean = false, onNote: (Int, Int) -> Unit) {
    val strings = tuning.size
    val lit = remember { mutableStateMapOf<Int, Int>() } // string -> fret
    val measurer = rememberTextMeasurer()
    val fretX = remember(frets) { val scale = 1f; FloatArray(frets + 1) { f -> 1f - 2f.pow(-f / 12f) }.let { a -> val m = a[frets]; FloatArray(frets + 1) { a[it] / m * scale } } }
    Canvas(modifier.semantics { contentDescription = "Guitar strings" }.pointerInput(tuning) {
        awaitPointerEventScope {
            val lastString = HashMap<PointerId, Int>()
            while (true) {
                val ev = awaitPointerEvent()
                val nutW = size.width * 0.07f; val boardW = size.width - nutW
                val top = size.height * 0.1f; val gap = (size.height * 0.8f) / (strings - 1)
                for (c in ev.changes) {
                    val sIdx = ((c.position.y - top) / gap).roundToInt().coerceIn(0, strings - 1)  // 0 = top = highest string
                    val xr = (c.position.x - nutW) / boardW
                    val fret = if (xr < 0) 0 else (fretX.indexOfFirst { it >= xr }.takeIf { it > 0 } ?: frets).coerceIn(1, frets)
                    val stringNo = strings - 1 - sIdx
                    fun hit() { val vel = (78 + (c.pressure.coerceIn(0f, 1f) * 40)).toInt().coerceIn(40, 127); onNote(tuning[stringNo] + fret, vel); lit[sIdx] = fret }
                    if (c.changedToDown()) { hit(); lastString[c.id] = sIdx; c.consume() }
                    else if (c.pressed && lastString[c.id] != null && lastString[c.id] != sIdx) { hit(); lastString[c.id] = sIdx; c.consume() }
                    else if (c.changedToUp()) { lastString.remove(c.id); lit.remove(sIdx) }
                }
            }
        }
    }) {
        val nutW = size.width * 0.07f; val boardW = size.width - nutW
        val top = size.height * 0.1f; val gap = (size.height * 0.8f) / (strings - 1)
        // rosewood board with grain
        drawRect(Brush.verticalGradient(listOf(Color(0xFF2B1A12), Color(0xFF3A2418), Color(0xFF24150E))))
        for (i in 0 until 60) { val y = (i * 37 % 100) / 100f * size.height; drawLine(Color(0x22000000), Offset(0f, y), Offset(size.width, y + (i % 7 - 3) * 2f), 1f + (i % 3)) }
        // headstock / nut
        drawRect(Brush.horizontalGradient(listOf(Color(0xFF1A110B), Color(0xFF2A1B12))), size = Size(nutW, size.height))
        drawRect(Brush.horizontalGradient(listOf(Color(0xFFEDE6D3), Color(0xFFC9BFA5))), Offset(nutW - 6f, 0f), Size(6f, size.height))
        // inlays
        listOf(3, 5, 7, 9, 15).filter { it <= frets }.forEach { f -> val x = nutW + (fretX[f - 1] + fretX[f]) / 2 * boardW; drawCircle(Brush.radialGradient(listOf(Color(0xFFF4F1E8), Color(0xFFBDB6A6))), size.height * 0.035f, Offset(x, size.height / 2)) }
        if (frets >= 12) { val x = nutW + (fretX[11] + fretX[12]) / 2 * boardW; drawCircle(Color(0xFFE9E5DA), size.height * 0.03f, Offset(x, size.height * 0.3f)); drawCircle(Color(0xFFE9E5DA), size.height * 0.03f, Offset(x, size.height * 0.7f)) }
        // frets
        for (f in 1..frets) { val x = nutW + fretX[f] * boardW
            drawLine(Color(0xFF6E6A63), Offset(x + 1.5f, 0f), Offset(x + 1.5f, size.height), 4f); drawLine(Color(0xFFD9D6CF), Offset(x, 0f), Offset(x, size.height), 2.2f)
            if (f % 2 == 1 || f == 12) drawText(measurer, "$f", Offset(x - (fretX[f] - fretX[f - 1]) * boardW / 2 - 4f, size.height - 16f), TextStyle(fontSize = 9.sp, color = Color(0x88FFFFFF))) }
        // strings (wound low strings drawn thicker with texture)
        for (s in 0 until strings) { val y = top + s * gap; val stringNo = strings - 1 - s
            val w = if (bass) 3f + stringNo * 1.4f else 1.4f + (strings - 1 - s) * 0.75f
            val col = if (lit.containsKey(s)) Color(0xFFFFD27A) else if ((!bass && stringNo <= 2) || bass) Color(0xFFC2A46E) else Color(0xFFE3E3E3)
            drawLine(Color(0x99000000), Offset(0f, y + w), Offset(size.width, y + w), w)
            drawLine(col, Offset(0f, y), Offset(size.width, y), w)
            drawText(measurer, midiName(tuning[stringNo]), Offset(6f, y - 14f), TextStyle(fontSize = 10.sp, color = Color(0xCCFFFFFF)))
            lit[s]?.let { f -> val x = if (f == 0) nutW / 2 else nutW + (fretX[f - 1] + fretX[f]) / 2 * boardW; drawCircle(Color(0xCCFFC860), 11f, Offset(x, y)) } }
    }
}

/** Realistic piano keyboard; multi-touch, glissando, velocity from touch position (front of key = louder). */
@Composable fun PianoKeys(low: Int, octaves: Int, modifier: Modifier = Modifier, highlight: Set<Int> = emptySet(), onOn: (Int, Int) -> Unit, onOff: (Int) -> Unit) {
    val whites = remember(low, octaves) { (low until low + octaves * 12 + 1).filter { (it % 12) in listOf(0, 2, 4, 5, 7, 9, 11) } }
    val down = remember { mutableStateMapOf<Int, Boolean>() }
    val measurer = rememberTextMeasurer()
    fun keyAt(x: Float, y: Float, w: Float, h: Float): Pair<Int, Float> {
        val ww = w / whites.size; val bh = h * 0.62f
        if (y < bh) { val wi = (x / ww).toInt(); for (cand in listOf(wi, wi + 1)) { val white = whites.getOrNull(cand) ?: continue; val black = white - 1
            if (black % 12 in listOf(1, 3, 6, 8, 10) && black >= low) { val bx = cand * ww - ww * 0.3f; if (x >= bx && x <= bx + ww * 0.6f) return black to (y / bh) } } }
        return whites[(x / ww).toInt().coerceIn(0, whites.size - 1)] to (y / h)
    }
    Canvas(modifier.semantics { contentDescription = "Piano keys" }.pointerInput(low, octaves) {
        awaitPointerEventScope { val held = HashMap<PointerId, Int>()
            while (true) { val ev = awaitPointerEvent()
                for (c in ev.changes) {
                    if (c.pressed) { val (k, depth) = keyAt(c.position.x.coerceIn(0f, size.width - 1f), c.position.y.coerceIn(0f, size.height - 1f), size.width.toFloat(), size.height.toFloat())
                        if (held[c.id] != k) { held[c.id]?.let { onOff(it); down.remove(it) }; held[c.id] = k; down[k] = true; onOn(k, (50 + depth * 70 + c.pressure.coerceIn(0f, 1f) * 7).toInt().coerceIn(30, 127)) }
                        c.consume() }
                    else if (c.changedToUp() || !c.pressed) { held.remove(c.id)?.let { onOff(it); down.remove(it) } }
                } } }
    }) {
        val ww = size.width / whites.size; val bh = size.height * 0.62f
        drawRect(Color(0xFF0B0B0C))
        whites.forEachIndexed { i, k -> val x = i * ww; val pressed = down[k] == true
            drawRoundRect(Brush.verticalGradient(if (pressed) listOf(Color(0xFFD8D2C4), Color(0xFFEDE8DC)) else listOf(Color(0xFFF7F4EC), Color(0xFFFFFFFF), Color(0xFFE4DFD3))), Offset(x + 1f, 0f), Size(ww - 2f, size.height - if (pressed) 2f else 6f), CornerRadius(4f))
            if (!pressed) drawRect(Color(0xFFBDB7AA), Offset(x + 1f, size.height - 8f), Size(ww - 2f, 4f))
            if (k in highlight) drawCircle(Color(0xFFE8A33D), ww * 0.16f, Offset(x + ww / 2, size.height - ww * 0.5f))
            if (k % 12 == 0) drawText(measurer, midiName(k), Offset(x + 4f, size.height - 26f), TextStyle(fontSize = 9.sp, color = Color(0xFF8A8478))) }
        whites.forEachIndexed { i, white -> val black = white - 1
            if (i > 0 && black % 12 in listOf(1, 3, 6, 8, 10)) { val x = i * ww - ww * 0.3f; val pressed = down[black] == true
                drawRoundRect(Color(0x66000000), Offset(x + 2f, 2f), Size(ww * 0.6f, bh + 4f), CornerRadius(3f))
                drawRoundRect(Brush.verticalGradient(if (pressed) listOf(Color(0xFF1A1A1C), Color(0xFF2A2A2D)) else listOf(Color(0xFF2E2E31), Color(0xFF0C0C0D), Color(0xFF1B1B1D))), Offset(x, 0f), Size(ww * 0.6f, bh), CornerRadius(3f))
                drawRoundRect(Color(0x33FFFFFF), Offset(x + ww * 0.08f, bh * 0.05f), Size(ww * 0.44f, bh * (if (pressed) 0.7f else 0.8f)), CornerRadius(2f), style = Stroke(1f))
                if (black in highlight) drawCircle(Color(0xFFE8A33D), ww * 0.12f, Offset(x + ww * 0.3f, bh - ww * 0.4f)) } }
        drawRect(Brush.verticalGradient(listOf(Color(0xAA000000), Color.Transparent), 0f, 14f), size = Size(size.width, 14f))
    }
}
/** Real-looking drum kit: photographic heads and cymbals at each piece's position, multi-touch, velocity from strike position and pressure. */
/** Playable kit surface used in the Studio and the Jam Room: the same photoreal overhead renderer as the Drum Kit tab. */
@Composable fun DrumKitSurface(kit: DrumKit, modifier: Modifier = Modifier, editing: Boolean = false, onHit: (KitPiece, Int) -> Unit, onMove: ((KitPiece, Float, Float) -> Unit)? = null, armed: Set<String> = emptySet()) {
    var sel by remember { mutableStateOf<String?>(null) }
    KitStage(kit, KitView.OVERHEAD, modifier, if (editing) sel else null, editing && onMove != null, onHit = onHit, onSelect = { sel = it },
        onMoved = { id, x, y -> kit.pieces.firstOrNull { it.id == id }?.let { onMove?.invoke(it, x, y) } })
}

@Composable fun PadGrid(kit: DrumKit, modifier: Modifier = Modifier, armed: Set<String> = emptySet(), onHit: (KitPiece, Int) -> Unit) {
    val lit = remember { mutableStateMapOf<Int, Boolean>() }; val measurer = rememberTextMeasurer()
    Box(modifier.aspectRatio(1f)) {
        Image(painterResource(drawableId("gear_rubber_pad_board")), "Pad board", Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
        Canvas(Modifier.fillMaxSize().semantics { contentDescription = "Drum pads" }.pointerInput(kit.id) {
            awaitPointerEventScope { while (true) { val ev = awaitPointerEvent()
                for (c in ev.changes) { val inset = size.width * 0.085f; val cell = (size.width - 2 * inset) / 4f
                    val col = ((c.position.x - inset) / cell).toInt(); val row = ((c.position.y - inset) / cell).toInt()
                    if (col in 0..3 && row in 0..3) { val idx = row * 4 + col
                        if (c.changedToDown()) { kit.pieces.getOrNull(idx)?.let { pc -> val cx = inset + (col + 0.5f) * cell; val cy = inset + (row + 0.5f) * cell; val d = ((c.position - Offset(cx, cy)).getDistance() / (cell / 2)).coerceIn(0f, 1f)
                            onHit(pc, (127 * (0.5f + 0.5f * (1 - d))).toInt().coerceIn(25, 127)); lit[idx] = true }; c.consume() }
                        if (c.changedToUp()) lit.remove(idx) } } } }
        }) {
            val inset = size.width * 0.085f; val cell = (size.width - 2 * inset) / 4f
            for (i in 0 until 16) { val col = i % 4; val row = i / 4; val x = inset + col * cell; val y = inset + row * cell
                if (lit[i] == true) drawRoundRect(Color(0x66E8A33D), Offset(x + 4, y + 4), Size(cell - 8, cell - 8), CornerRadius(10f))
                kit.pieces.getOrNull(i)?.let { pc -> if (pc.id in armed) drawRoundRect(Color(0xFFE2463F), Offset(x + 4, y + 4), Size(cell - 8, cell - 8), CornerRadius(10f), style = Stroke(3f))
                    drawText(measurer, pc.name, Offset(x + 10f, y + cell - 26f), TextStyle(fontSize = 9.sp, color = Color(0xCCFFFFFF))) } }
        }
    }
}
