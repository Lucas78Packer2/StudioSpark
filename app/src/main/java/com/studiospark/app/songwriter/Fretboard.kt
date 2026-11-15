package com.studiospark.app.songwriter

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** StudioSpark fretboard palette. */
object FretboardColors {
    val Charcoal = Color(0xFF0E1214)
    val Signal = Color(0xFF3DDC97)
    val Mist = Color(0xFFE7EEE9)
    val Wire = Color(0xFF8A958F)
    val StringLine = Color(0xFFB4BFB9)
    val Inlay = Color(0xFF2A3236)
    /** Opacity for notes outside the locked key. */
    const val DIM = 0.22f
}

/** Pure geometry so taps and drawing agree (and so it can be unit-tested). */
class FretGeometry(val frets: Int, val width: Float, val height: Float, val leftPad: Float, val topPad: Float) {
    val openW get() = (width - leftPad) / (frets + 1)
    val stringGap get() = (height - topPad) / 6f
    /** Fret column (0 = open) at x, or null outside the board. */
    fun fretAt(x: Float): Int? = ((x - leftPad) / openW).toInt().takeIf { x >= leftPad && it in 0..frets }
    /** String index (0 = low E, drawn at the bottom) at y, or null. */
    fun stringAt(y: Float): Int? = ((y - topPad) / stringGap).toInt().takeIf { y >= topPad && it in 0..5 }?.let { 5 - it }
    fun stringY(s: Int) = topPad + (5 - s) * stringGap + stringGap / 2
    fun cellCenterX(fret: Int) = leftPad + fret * openW + openW / 2
    fun wireX(fret: Int) = leftPad + (fret + 1) * openW   // wire at the right edge of the cell; wireX(0) is the nut
    companion object { val SINGLE = setOf(3, 5, 7, 9, 15, 17, 19, 21); val DOUBLE = setOf(12, 24) }
}

/**
 * Canvas fretboard: 6 strings (high e on top), open column + up to 24 frets with inlays.
 * - [key] non-null: every position gets a dot; notes outside the key are drawn at reduced opacity, roots filled green.
 * - [fingering]: the suggested chord's voicing — X over muted strings, ring on open strings, green dots with finger numbers.
 * - [capo]: a capo bar just behind that fret.
 * - [taps]: melody notes the user entered. [onTap] (string, fret) feeds melody-to-chord.
 * Empty state (no key, no fingering, no taps): a blank board, ready for input.
 */
@Composable fun Fretboard(
    modifier: Modifier = Modifier,
    key: Key? = null,
    fingering: Fingering? = null,
    capo: Int = 0,
    taps: List<FretPos> = emptyList(),
    frets: Int = 24,
    onTap: ((string: Int, fret: Int) -> Unit)? = null,
) {
    val nFrets = frets.coerceIn(12, 24)
    val tm = rememberTextMeasurer()
    val blank = key == null && fingering == null && taps.isEmpty()
    val cellDp = 46; val heightDp = 214
    val widthDp = 26 + (nFrets + 1) * cellDp
    Canvas(modifier
        .horizontalScroll(rememberScrollState())
        .width(widthDp.dp).height(heightDp.dp)
        .semantics { contentDescription = "Fretboard"; stateDescription = if (blank) "blank" else "notes shown" }
        .then(if (onTap == null) Modifier else Modifier.pointerInput(nFrets) {
            detectTapGestures { o -> val g = FretGeometry(nFrets, size.width.toFloat(), size.height.toFloat(), 26.dp.toPx(), 22.dp.toPx())
                val f = g.fretAt(o.x); val s = g.stringAt(o.y); if (f != null && s != null) onTap(s, f) }
        })) {
        val g = FretGeometry(nFrets, size.width, size.height, 26.dp.toPx(), 22.dp.toPx())
        drawBoard(g, tm)
        if (capo in 1..nFrets) drawCapo(g, capo, tm)
        key?.let { drawScale(g, it, fingering) }
        fingering?.let { drawFingering(g, it, tm) }
        taps.forEach { p -> if (p.fret <= nFrets) drawCircle(FretboardColors.Mist, g.stringGap * 0.36f, Offset(g.cellCenterX(p.fret), g.stringY(p.string)), style = Stroke(3.dp.toPx())) }
    }
}

private fun DrawScope.label(tm: TextMeasurer, text: String, center: Offset, color: Color, sizeSp: Int = 11, bold: Boolean = false) {
    val r = tm.measure(text, TextStyle(color = color, fontSize = sizeSp.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal))
    drawText(r, topLeft = Offset(center.x - r.size.width / 2f, center.y - r.size.height / 2f))
}

private fun DrawScope.drawBoard(g: FretGeometry, tm: TextMeasurer) {
    drawRect(FretboardColors.Charcoal)
    val top = g.topPad; val bottom = size.height
    // inlays
    for (f in 1..g.frets) {
        val cx = g.cellCenterX(f); val r = g.stringGap * 0.18f
        if (f in FretGeometry.SINGLE) drawCircle(FretboardColors.Inlay, r, Offset(cx, top + (bottom - top) / 2))
        if (f in FretGeometry.DOUBLE) { drawCircle(FretboardColors.Inlay, r, Offset(cx, top + (bottom - top) * 0.3f)); drawCircle(FretboardColors.Inlay, r, Offset(cx, top + (bottom - top) * 0.7f)) }
        if (f in FretGeometry.SINGLE || f in FretGeometry.DOUBLE) label(tm, "$f", Offset(cx, top / 2), FretboardColors.Mist.copy(alpha = 0.6f), 10)
    }
    // nut + fret wires
    drawLine(FretboardColors.Mist, Offset(g.wireX(0), top), Offset(g.wireX(0), bottom), 5.dp.toPx())
    for (f in 1..g.frets) drawLine(FretboardColors.Wire, Offset(g.wireX(f), top), Offset(g.wireX(f), bottom), 1.5.dp.toPx())
    // strings, thicker toward low E, with names in the left margin
    for (s in 0..5) { val y = g.stringY(s)
        drawLine(FretboardColors.StringLine, Offset(g.leftPad, y), Offset(size.width, y), (0.8f + (5 - s) * 0.35f).dp.toPx())
        label(tm, "EADGBe"[s].toString(), Offset(g.leftPad / 2, y), FretboardColors.Mist.copy(alpha = 0.7f), 10) }
}

private fun DrawScope.drawCapo(g: FretGeometry, capo: Int, tm: TextMeasurer) {
    val x = g.wireX(capo - 1) + g.openW * 0.25f
    drawRoundRect(FretboardColors.Mist, Offset(x - 5.dp.toPx(), g.topPad), Size(10.dp.toPx(), size.height - g.topPad), CornerRadius(4.dp.toPx()))
    label(tm, "CAPO $capo", Offset(x, g.topPad / 2), FretboardColors.Signal, 10, bold = true)
}

private fun DrawScope.drawScale(g: FretGeometry, k: Key, f: Fingering?) {
    for (s in 0..5) for (fr in 0..g.frets) {
        if (f != null && f.frets[s] == fr) continue       // the chord dot is drawn on top instead
        val n = Notes.STANDARD[s] + fr; val pc = ((n % 12) + 12) % 12
        val c = Offset(g.cellCenterX(fr), g.stringY(s)); val r = g.stringGap * 0.2f
        when { pc == k.root -> drawCircle(FretboardColors.Signal.copy(alpha = 0.55f), r, c)
            k.inScale(n) -> drawCircle(FretboardColors.Mist.copy(alpha = 0.55f), r, c)
            else -> drawCircle(FretboardColors.Mist.copy(alpha = FretboardColors.DIM * 0.6f), r * 0.7f, c) }
    }
}

private fun DrawScope.drawFingering(g: FretGeometry, f: Fingering, tm: TextMeasurer) {
    for (s in 0..5) { val y = g.stringY(s); val fr = f.frets[s]
        when {
            fr < 0 -> label(tm, "X", Offset(g.cellCenterX(0), y), FretboardColors.Mist, 14, bold = true)
            fr == 0 -> drawCircle(FretboardColors.Signal, g.stringGap * 0.32f, Offset(g.cellCenterX(0), y), style = Stroke(2.5.dp.toPx()))
            fr <= g.frets -> { val c = Offset(g.cellCenterX(fr), y)
                drawCircle(FretboardColors.Signal, g.stringGap * 0.38f, c)
                if (f.fingers[s] > 0) label(tm, "${f.fingers[s]}", c, FretboardColors.Charcoal, 12, bold = true) }
        } }
}
