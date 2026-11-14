package com.studiospark.app.ui

import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import com.studiospark.app.core.*
import kotlin.math.*

/** Geometry of a drawn instrument, shared by the renderer and touch handling (so the drawn neck is playable). */
class StringGeo(val left: Float, val cy: Float, val bodyLen: Float, val bodyH: Float, val nutX: Float, val bridgeX: Float, val neckEndX: Float,
                val halfNut: Float, val halfBody: Float, val strings: Int, val frets: Int, val headLen: Float) {
    val scale get() = nutX - bridgeX
    fun fretX(n: Int) = nutX - scale * (1f - 2f.pow(-n / 12f))
    fun halfAt(x: Float) = halfBody + (halfNut - halfBody) * ((x - neckEndX) / (nutX - neckEndX)).coerceIn(0f, 1.2f)
    /** y of string i (0 = lowest pitch, drawn at the top like a player looking down) at x. */
    fun stringY(i: Int, x: Float): Float { val spreadAt = if (x >= bridgeX) halfAt(x) * 0.82f else halfBody * 0.95f; val t = if (strings == 1) 0.5f else i / (strings - 1f); return cy - spreadAt + 2 * spreadAt * t }
    fun bodyPt(ox: Float, oy: Float) = Offset(left + ox * bodyLen, cy + oy * bodyH / 2)
}

object GuitarRender {
    fun geo(b: StringBuild, origin: Offset, w: Float, h: Float): StringGeo {
        val body = StringCatalog.part(b.body); val kind = body?.kind ?: "solid"
        val headless = kind == "headless"
        var len = w * 0.96f
        val bodyFrac = when { kind == "acoustic" -> 0.43f; b.bass -> 0.34f; else -> 0.39f }
        val hRatio = when { kind == "acoustic" -> 0.42f; kind == "hollow" -> 0.40f; b.bass -> 0.30f; else -> 0.35f }
        if (len * bodyFrac * hRatio / bodyFrac * bodyFrac > h * 0.92f) len = h * 0.92f / (hRatio)
        val bl = len * bodyFrac; val bh = bl * hRatio / bodyFrac * bodyFrac / bodyFrac * bodyFrac
        val bodyH = min(len * hRatio, h * 0.92f)
        val left = origin.x + (w - len) / 2; val cy = origin.y + h / 2
        val headLen = if (headless) len * 0.02f else len * (if (b.bass) 0.13f else 0.12f)
        val nutX = left + len - headLen
        val bridgeX = left + bl * (if (kind == "acoustic") 0.27f else 0.22f)
        val n = b.stringCount.coerceIn(4, 7)
        val halfNut = bodyH * (if (b.bass) 0.085f else 0.075f) * (0.85f + n / 6f * 0.15f)
        val halfBody = halfNut * 1.28f
        val frets = when { b.body == "super_dc" || b.body == "super_dc7" || b.body == "dc24" -> 24; b.bass -> 21; kind == "acoustic" -> 20; else -> 22 }
        val scaleLen = nutX - bridgeX
        val neckEndX = nutX - scaleLen * (1f - 2f.pow(-(frets + 0.6f) / 12f))
        @Suppress("UNUSED_VARIABLE") val unused = bh
        return StringGeo(left, cy, bl, bodyH, nutX, bridgeX, neckEndX, halfNut, halfBody, n, frets, headLen)
    }

    private fun smooth(pts: List<Offset>): Path { val p = Path(); val m = pts.size
        for (i in 0 until m) { val p0 = pts[(i - 1 + m) % m]; val p1 = pts[i]; val p2 = pts[(i + 1) % m]; val p3 = pts[(i + 2) % m]
            if (i == 0) p.moveTo(p1.x, p1.y)
            val c1 = p1 + (p2 - p0) / 6f; val c2 = p2 - (p3 - p1) / 6f; p.cubicTo(c1.x, c1.y, c2.x, c2.y, p2.x, p2.y) }
        p.close(); return p }
    private fun bodyPath(b: StringBuild, g: StringGeo): Path { val o = StringCatalog.outline(b.body); return smooth((0 until o.size / 2).map { g.bodyPt(o[it * 2], o[it * 2 + 1]) }) }
    private fun c(l: Long) = Color(l)
    private fun rnd(seed: Int): Float { var x = seed * 1103515245 + 12345; x = x xor (x ushr 13); x *= 0x5bd1e995; x = x xor (x ushr 15); return ((x and 0xffff) / 65535f) }

    fun DrawScope.drawInstrument(b: StringBuild, origin: Offset, w: Float, h: Float, ringing: Map<Int, Long> = emptyMap(), now: Long = 0L) {
        val g = geo(b, origin, w, h); val body = StringCatalog.part(b.body); val kind = body?.kind ?: "solid"
        val fin = StringCatalog.part(b.finish); val fc = fin?.colors?.map { c(it) } ?: listOf(Color(0xFFB0602A), Color(0xFF4A200C))
        val hw = StringCatalog.part(b.hardware)?.colors?.map { c(it) } ?: listOf(Color(0xFFE6EAEE), Color(0xFF8A9096))
        val metal = Brush.linearGradient(listOf(hw[0].lighten(0.3f), hw[0], hw[1], hw[0].lighten(0.15f)))
        val path = bodyPath(b, g)
        val bc = g.bodyPt(0.45f, 0f)
        // ---- drop shadow
        translate(g.bodyH * 0.03f, g.bodyH * 0.05f) { drawPath(path, Color(0x66000000)) }
        drawRect(Color(0x55000000), Offset(g.neckEndX, g.cy - g.halfBody + g.bodyH * 0.05f), Size(g.nutX - g.neckEndX, g.halfBody * 2))
        // ---- body finish
        clipPath(path) {
            val tl = g.bodyPt(0f, -1f); val sz = Size(g.bodyLen * 1.1f, g.bodyH)
            when (fin?.style) {
                "burst" -> drawRect(Brush.radialGradient(0f to fc[0], 0.55f to fc[0].darken(0.05f), 0.78f to fc[1], 1f to fc[2], center = bc, radius = g.bodyLen * 0.62f), tl, sz)
                "metal" -> { drawRect(Brush.linearGradient(listOf(fc[0].lighten(0.25f), fc[0], fc[1], fc[0]), tl, tl + Offset(sz.width * 0.6f, sz.height)), tl, sz)
                    for (i in 0 until 900) { val x = tl.x + rnd(i) * sz.width; val y = tl.y + rnd(i + 7777) * sz.height; drawCircle(if (i % 3 == 0) Color.White.copy(alpha = 0.55f) else fc[0].lighten(0.5f).copy(alpha = 0.4f), g.bodyH * 0.0045f, Offset(x, y)) } }
                "wood" -> { drawRect(Brush.verticalGradient(listOf(fc[0], fc[1], fc[0].darken(0.05f)), tl.y, tl.y + sz.height), tl, sz)
                    for (i in 0 until 46) { val y0 = tl.y + i / 46f * sz.height; val wv = g.bodyH * 0.02f * (rnd(i) - 0.5f) * 4
                        val pp = Path().apply { moveTo(tl.x, y0); cubicTo(tl.x + sz.width * 0.3f, y0 + wv, tl.x + sz.width * 0.6f, y0 - wv, tl.x + sz.width, y0 + wv * 0.5f) }
                        drawPath(pp, fc[1].darken(0.25f).copy(alpha = 0.18f + rnd(i + 3) * 0.2f), style = Stroke(1f + rnd(i + 9) * 2.5f)) } }
                "quilt" -> { drawRect(Brush.radialGradient(listOf(fc[0].lighten(0.15f), fc[0], fc[1]), bc, g.bodyLen * 0.6f), tl, sz)
                    for (i in 0 until 140) { val x = tl.x + rnd(i) * sz.width; val y = tl.y + rnd(i + 99) * sz.height; val r = g.bodyH * (0.03f + rnd(i + 5) * 0.05f)
                        drawOval(Brush.radialGradient(listOf(fc[0].lighten(0.45f).copy(alpha = 0.35f), Color.Transparent), Offset(x, y), r), Offset(x - r * 1.4f, y - r * 0.8f), Size(r * 2.8f, r * 1.6f)) } }
                "flame" -> { drawRect(Brush.radialGradient(listOf(fc[0].lighten(0.1f), fc[0], fc[1].darken(0.3f)), bc, g.bodyLen * 0.6f), tl, sz)
                    for (i in 0 until 70) { val x = tl.x + i / 70f * sz.width; val wob = g.bodyH * 0.04f * sin(i * 0.9f)
                        drawLine(if (i % 2 == 0) fc[0].lighten(0.35f).copy(alpha = 0.28f) else fc[1].copy(alpha = 0.25f), Offset(x + wob, tl.y), Offset(x - g.bodyH * 0.25f - wob, tl.y + sz.height), sz.width / 70f * 0.9f) } }
                "relic" -> { drawRect(Brush.verticalGradient(listOf(fc[0].lighten(0.08f), fc[0], fc[0].darken(0.2f)), tl.y, tl.y + sz.height), tl, sz)
                    val wear = listOf(g.bodyPt(0.62f, -0.78f) to 0.16f, g.bodyPt(0.2f, 0.84f) to 0.12f, g.bodyPt(0.06f, 0.1f) to 0.08f, g.bodyPt(0.35f, -0.9f) to 0.07f)
                    wear.forEachIndexed { i, (pt, r) -> val rr = g.bodyH * r; drawOval(Brush.radialGradient(listOf(fc[1], fc[1].darken(0.15f), Color.Transparent), pt, rr), pt - Offset(rr * 1.3f, rr), Size(rr * 2.6f, rr * 2f))
                        for (k in 0 until 20) { val a = rnd(i * 31 + k) * 6.28f; drawCircle(fc[1].copy(alpha = 0.6f), rr * 0.08f, pt + Offset(cos(a), sin(a)) * rr * (1f + rnd(k + i) * 0.4f)) } } }
                else -> drawRect(Brush.linearGradient(listOf(fc[0].lighten(0.12f), fc[0], fc.getOrElse(1) { fc[0].darken(0.3f) }), tl, tl + Offset(sz.width * 0.4f, sz.height)), tl, sz)
            }
            // carved-top / arch shading and clear-coat sheen
            if (kind == "carved" || kind == "hollow" || kind == "semi") drawRect(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.10f), Color.Transparent, Color.Black.copy(alpha = 0.28f)), bc, g.bodyLen * 0.58f), tl, sz)
            drawOval(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.28f), Color.Transparent), g.bodyPt(0.3f, -0.55f), g.bodyH * 0.35f), g.bodyPt(0.05f, -0.95f), Size(g.bodyLen * 0.55f, g.bodyH * 0.4f))
            // pickguard
            val guard = StringCatalog.part(b.guard)
            if (guard != null && guard.style != "none" && kind != "headless") {
                val gp = if (kind == "acoustic") smooth(listOf(g.bodyPt(0.7f, 0.26f), g.bodyPt(0.86f, 0.3f), g.bodyPt(0.92f, 0.52f), g.bodyPt(0.8f, 0.7f), g.bodyPt(0.62f, 0.6f)))
                    else if (kind == "carved" || kind == "hollow" || kind == "semi") smooth(listOf(g.bodyPt(0.66f, 0.12f), g.bodyPt(0.98f, 0.18f), g.bodyPt(0.9f, 0.52f), g.bodyPt(0.62f, 0.62f), g.bodyPt(0.58f, 0.36f)))
                    else smooth(listOf(g.bodyPt(0.26f, 0.05f), g.bodyPt(0.36f, -0.42f), g.bodyPt(0.62f, -0.44f), g.bodyPt(0.88f, -0.3f), g.bodyPt(1.02f, -0.2f), g.bodyPt(1.02f, 0.22f), g.bodyPt(0.88f, 0.5f), g.bodyPt(0.62f, 0.66f), g.bodyPt(0.38f, 0.64f), g.bodyPt(0.27f, 0.42f)))
                translate(1.5f, 2.5f) { drawPath(gp, Color(0x55000000)) }
                val gc = guard.colors.map { c(it) }
                when (guard.style) {
                    "tort" -> { drawPath(gp, gc[1]); clipPath(gp) {
                        // tortoiseshell: soft, elongated amber clouds layered over near-black, then a deep lacquer tint
                        for (i in 0 until 90) { val pt = g.bodyPt(0.22f + rnd(i) * 0.85f, -0.55f + rnd(i + 50) * 1.3f); val r = g.bodyH * (0.03f + rnd(i + 3) * 0.07f); val col = gc[0].lighten(rnd(i + 8) * 0.35f)
                            drawOval(Brush.radialGradient(listOf(col.copy(alpha = 0.55f), col.copy(alpha = 0.18f), Color.Transparent), pt, r), pt - Offset(r * 1.6f, r * 0.7f), Size(r * 3.2f, r * 1.4f)) }
                        for (i in 0 until 40) { val pt = g.bodyPt(0.22f + rnd(i + 300) * 0.85f, -0.55f + rnd(i + 350) * 1.3f); val r = g.bodyH * (0.02f + rnd(i + 303) * 0.04f)
                            drawOval(Brush.radialGradient(listOf(Color(0xCC120604), Color.Transparent), pt, r), pt - Offset(r * 1.4f, r * 0.8f), Size(r * 2.8f, r * 1.6f)) }
                        drawRect(Color(0x33200800), g.bodyPt(0f, -1f), Size(g.bodyLen * 1.1f, g.bodyH)) } }
                    "pearl" -> drawPath(gp, Brush.linearGradient(listOf(gc[0], gc[1], Color(0xFFE8F0F2), gc[0]), g.bodyPt(0.3f, -0.4f), g.bodyPt(0.9f, 0.6f)))
                    else -> drawPath(gp, Brush.linearGradient(listOf(gc[0].lighten(0.06f), gc[0], gc[0].darken(0.08f)), g.bodyPt(0.3f, -0.4f), g.bodyPt(0.9f, 0.6f)))
                }
                drawPath(gp, Color.White.copy(alpha = 0.18f), style = Stroke(1.2f))
                // guard screws
                for (i in 0 until 8) { val a = i / 8f; drawCircle(hw[0], g.bodyH * 0.008f, g.bodyPt(0.34f + a * 0.6f, if (i % 2 == 0) -0.34f else 0.52f)) }
            }
        }
        // binding / edge
        val bindCol = if (kind == "carved" || kind == "hollow" || kind == "semi" || kind == "acoustic") Color(0xFFEFE6CF) else fc[0].darken(0.45f)
        drawPath(path, bindCol, style = Stroke(g.bodyH * (if (bindCol == Color(0xFFEFE6CF)) 0.014f else 0.008f)))
        drawPath(path, Color.Black.copy(alpha = 0.5f), style = Stroke(1f))

        // ---- sound holes
        if (kind == "acoustic") { val sh = g.bodyPt(0.74f, 0f); val r = g.bodyH * 0.17f
            for (k in 0 until 4) drawCircle(if (k % 2 == 0) Color(0xFF2A160A) else Color(0xFFE8D8B0), r * (1.32f - k * 0.06f), sh, style = Stroke(r * 0.04f))
            drawCircle(Brush.radialGradient(listOf(Color(0xFF060302), Color(0xFF1A0E06)), sh, r), r, sh) }
        if (kind == "hollow" || kind == "semi") for (sgn in listOf(-1f, 1f)) { val cx = g.bodyPt(0.5f, sgn * 0.56f)
            val fp = Path().apply { moveTo(cx.x - g.bodyLen * 0.16f, cx.y - sgn * g.bodyH * 0.02f); cubicTo(cx.x - g.bodyLen * 0.05f, cx.y + sgn * g.bodyH * 0.06f, cx.x + g.bodyLen * 0.05f, cx.y - sgn * g.bodyH * 0.08f, cx.x + g.bodyLen * 0.16f, cx.y) }
            drawPath(fp, Color(0xFF0A0604), style = Stroke(g.bodyH * 0.022f, cap = StrokeCap.Round))
            drawCircle(Color(0xFF0A0604), g.bodyH * 0.02f, Offset(cx.x - g.bodyLen * 0.16f, cx.y - sgn * g.bodyH * 0.02f)); drawCircle(Color(0xFF0A0604), g.bodyH * 0.02f, Offset(cx.x + g.bodyLen * 0.16f, cx.y)) }

        // ---- neck + headstock
        val fb = StringCatalog.part(b.fretboard)?.colors?.map { c(it) } ?: listOf(Color(0xFF3A2216), Color(0xFF24140C))
        val neckWood = if (b.fretboard == "fb_maple") fb[0] else Color(0xFFD8B47A)
        val neck = Path().apply { moveTo(g.neckEndX, g.cy - g.halfBody); lineTo(g.nutX, g.cy - g.halfNut); lineTo(g.nutX, g.cy + g.halfNut); lineTo(g.neckEndX, g.cy + g.halfBody); close() }
        // heel
        drawRoundRect(neckWood.darken(0.1f), Offset(g.neckEndX - g.bodyLen * 0.08f, g.cy - g.halfBody * 1.02f), Size(g.bodyLen * 0.1f, g.halfBody * 2.04f), CornerRadius(g.halfBody * 0.4f))
        drawPath(neck, Brush.verticalGradient(listOf(fb[0].lighten(0.08f), fb[0], fb[1], fb[0]), g.cy - g.halfBody, g.cy + g.halfBody))
        // board grain
        clipPath(neck) { for (i in 0 until 26) { val y = g.cy - g.halfBody + i / 26f * g.halfBody * 2; drawLine(fb[1].darken(0.2f).copy(alpha = 0.25f), Offset(g.neckEndX, y), Offset(g.nutX, y + (rnd(i) - 0.5f) * 4f), 1f + rnd(i + 4)) } }
        drawPath(neck, Color(0xFFEFE6CF).copy(alpha = if (kind == "carved" || kind == "hollow" || kind == "semi") 0.9f else 0f), style = Stroke(1.6f))
        // frets + inlays
        val fretless = b.body == "fretless"
        val inlay = StringCatalog.part(b.inlay); val inCol = inlay?.colors?.firstOrNull()?.let { c(it) } ?: Color(0xFFEDE6D6)
        for (n in 1..g.frets) { val x = g.fretX(n); if (x < g.neckEndX) break; val hh = g.halfAt(x)
            if (fretless) drawLine(Color(0xFFDDD6C6).copy(alpha = 0.35f), Offset(x, g.cy - hh), Offset(x, g.cy + hh), 1f)
            else { drawLine(Color(0xFF7A7E84), Offset(x + 1.2f, g.cy - hh), Offset(x + 1.2f, g.cy + hh), max(2f, g.halfNut * 0.09f)); drawLine(Color(0xFFF2F4F6), Offset(x, g.cy - hh), Offset(x, g.cy + hh), max(1f, g.halfNut * 0.04f)) }
            if (n in setOf(3, 5, 7, 9, 12, 15, 17, 19, 21, 24)) { val mx = (x + g.fretX(n - 1)) / 2; val fw = g.fretX(n - 1) - x; val hm = g.halfAt(mx)
                when (inlay?.style) {
                    "blocks" -> drawRect(Brush.linearGradient(listOf(inCol, inCol.darken(0.15f), Color(0xFFF6F2FF))), Offset(mx - fw * 0.3f, g.cy - hm * 0.7f), Size(fw * 0.6f, hm * 1.4f))
                    "trap" -> drawPath(Path().apply { moveTo(mx - fw * 0.22f, g.cy - hm * 0.72f); lineTo(mx + fw * 0.22f, g.cy - hm * 0.6f); lineTo(mx + fw * 0.22f, g.cy + hm * 0.6f); lineTo(mx - fw * 0.22f, g.cy + hm * 0.72f); close() }, inCol)
                    "shark" -> drawPath(Path().apply { moveTo(mx - fw * 0.3f, g.cy + hm * 0.75f); lineTo(mx + fw * 0.3f, g.cy + hm * 0.75f); lineTo(mx + fw * 0.3f, g.cy - hm * 0.75f); close() }, inCol)
                    "birds" -> { val bp = Path().apply { moveTo(mx - fw * 0.28f, g.cy); quadraticBezierTo(mx, g.cy - hm * 0.9f, mx + fw * 0.25f, g.cy - hm * 0.5f); quadraticBezierTo(mx + fw * 0.05f, g.cy - hm * 0.2f, mx + fw * 0.2f, g.cy + hm * 0.5f); quadraticBezierTo(mx, g.cy + hm * 0.2f, mx - fw * 0.28f, g.cy); close() }; drawPath(bp, inCol) }
                    "none", "lumi" -> {}
                    else -> if (n == 12 || n == 24) { drawCircle(inCol, hm * 0.13f, Offset(mx, g.cy - hm * 0.45f)); drawCircle(inCol, hm * 0.13f, Offset(mx, g.cy + hm * 0.45f)) } else drawCircle(Brush.radialGradient(listOf(Color.White, inCol, inCol.darken(0.2f)), Offset(mx, g.cy), hm * 0.14f), hm * 0.14f, Offset(mx, g.cy))
                }
                drawCircle(if (inlay?.style == "lumi") inCol else Color(0xFFEDE6D6), max(1.5f, hm * 0.05f), Offset(mx, g.cy - hm * 0.97f))
            } }
        // nut
        drawRect(Brush.horizontalGradient(listOf(Color(0xFFF4EEDC), Color(0xFFD6CCB4))), Offset(g.nutX, g.cy - g.halfNut * 1.02f), Size(max(3f, g.halfNut * 0.14f), g.halfNut * 2.04f))
        // headstock
        val headless = kind == "headless"
        val hx0 = g.nutX + g.halfNut * 0.14f
        val tunerPts = mutableListOf<Offset>()
        if (!headless) {
            val L = g.headLen * 0.98f; val inline = !b.bass && (b.body in setOf("contour_dc", "slab_sc", "offset", "super_dc", "super_dc7", "wedge", "baritone"))
            val twoSided = !inline && !(b.bass && b.body != "violin_bass")
            val hp = if (inline) smooth(listOf(Offset(hx0, g.cy - g.halfNut), Offset(hx0 + L * 0.3f, g.cy - g.halfNut * 1.25f), Offset(hx0 + L * 0.75f, g.cy - g.halfNut * 1.9f), Offset(hx0 + L, g.cy - g.halfNut * 1.6f), Offset(hx0 + L * 0.98f, g.cy - g.halfNut * 0.6f), Offset(hx0 + L * 0.7f, g.cy + g.halfNut * 0.5f), Offset(hx0 + L * 0.3f, g.cy + g.halfNut * 1.15f), Offset(hx0, g.cy + g.halfNut)))
                else if (twoSided) smooth(listOf(Offset(hx0, g.cy - g.halfNut), Offset(hx0 + L * 0.5f, g.cy - g.halfNut * 1.55f), Offset(hx0 + L, g.cy - g.halfNut * 1.75f), Offset(hx0 + L * 1.02f, g.cy), Offset(hx0 + L, g.cy + g.halfNut * 1.75f), Offset(hx0 + L * 0.5f, g.cy + g.halfNut * 1.55f), Offset(hx0, g.cy + g.halfNut)))
                else smooth(listOf(Offset(hx0, g.cy - g.halfNut), Offset(hx0 + L * 0.35f, g.cy - g.halfNut * 1.3f), Offset(hx0 + L * 0.85f, g.cy - g.halfNut * 1.9f), Offset(hx0 + L, g.cy - g.halfNut * 1.4f), Offset(hx0 + L * 0.9f, g.cy + g.halfNut * 0.3f), Offset(hx0 + L * 0.45f, g.cy + g.halfNut * 1.2f), Offset(hx0, g.cy + g.halfNut)))
            translate(2f, 3f) { drawPath(hp, Color(0x55000000)) }
            val headFace = if (fin?.style == "wood" || b.fretboard == "fb_maple") neckWood else if (kind == "acoustic" || kind == "hollow") Color(0xFF14100C) else fc[0]
            drawPath(hp, Brush.linearGradient(listOf(headFace.lighten(0.1f), headFace, headFace.darken(0.2f)), Offset(hx0, g.cy - g.halfNut), Offset(hx0 + L, g.cy + g.halfNut)))
            drawPath(hp, Color.Black.copy(alpha = 0.4f), style = Stroke(1f))
            val n = g.strings
            if (inline) for (i in 0 until n) tunerPts += Offset(hx0 + L * (0.18f + 0.78f * i / max(1, n - 1)), g.cy - g.halfNut * (1.05f + 0.45f * i / max(1, n - 1)))
            else if (twoSided) { val top = (n + 1) / 2; for (i in 0 until n) { val side = if (i < top) -1f else 1f; val k = if (i < top) i else i - top; val cnt = if (i < top) top else n - top; tunerPts += Offset(hx0 + L * (0.25f + 0.65f * k / max(1, cnt - 1)), g.cy + side * g.halfNut * 1.25f) } }
            else for (i in 0 until n) tunerPts += Offset(hx0 + L * (0.2f + 0.7f * i / max(1, n - 1)), g.cy - g.halfNut * 1.15f)
            tunerPts.forEachIndexed { i, pt -> val r = g.halfNut * (if (b.bass) 0.2f else 0.14f)
                // tuner key outside the edge
                val keyOff = if (pt.y < g.cy) -1f else 1f
                drawRoundRect(metal, Offset(pt.x - r * 0.9f, pt.y + keyOff * r * 1.6f - r * 0.8f), Size(r * 1.8f, r * 1.6f), CornerRadius(r * 0.6f))
                drawCircle(Brush.radialGradient(listOf(Color.White, hw[0], hw[1]), pt, r), r, pt); drawCircle(Color.Black.copy(alpha = 0.4f), r * 0.35f, pt) }
        } else { // headless nut block
            drawRect(metal, Offset(hx0, g.cy - g.halfNut * 1.1f), Size(g.halfNut * 0.5f, g.halfNut * 2.2f)) }

        // ---- pickups
        val layout = StringCatalog.part(b.pickups)?.layout ?: emptyList()
        val coverDark = b.guard != "guard_white" && b.guard != "guard_mint" && b.guard != "guard_pearl"
        val puStart = g.bridgeX + g.bodyLen * 0.06f; val puEnd = min(g.neckEndX - g.bodyLen * 0.05f, g.bodyPt(0.86f, 0f).x)
        layout.forEachIndexed { i, kindPu ->
            val x = if (layout.size == 1) (if (kindPu == "SH") g.bodyPt(0.74f, 0f).x else if (kindPu == "FH") puEnd else puStart + (puEnd - puStart) * 0.12f) else puStart + (puEnd - puStart) * i / (layout.size - 1)
            val hh = g.halfBody * 1.15f; val n = g.strings
            fun poles(x0: Float, count: Int, col: Color, span: Float = hh * 0.82f) { for (k in 0 until count) drawCircle(Brush.radialGradient(listOf(Color.White, col), Offset(x0, g.cy - span + 2 * span * k / max(1, count - 1)), g.halfNut * 0.08f), g.halfNut * 0.07f, Offset(x0, g.cy - span + 2 * span * k / max(1, count - 1))) }
            // seat shadow + outline so covers read on any guard colour
            val sw = when (kindPu) { "S", "J", "SH" -> g.halfNut * 0.6f; "P90", "SB", "P" -> g.halfNut * 1.0f; "H", "FH", "AH", "MM" -> g.halfNut * 1.4f; "MH" -> g.halfNut * 1.05f; else -> 0f }
            if (sw > 0f) { drawRoundRect(Color(0x66000000), Offset(x - sw / 2 + 2f, g.cy - hh * 1.12f + 3f), Size(sw, hh * 2.24f), CornerRadius(sw * 0.3f)) }
            when (kindPu) {
                "S" -> { val wdt = g.halfNut * 0.55f; val col = if (coverDark) Color(0xFF151515) else Color(0xFFF7F3E6)
                    drawRoundRect(Brush.horizontalGradient(listOf(col.lighten(0.08f), col, col.darken(0.12f)), x - wdt / 2, x + wdt / 2), Offset(x - wdt / 2, g.cy - hh), Size(wdt, hh * 2), CornerRadius(wdt / 2))
                    drawRoundRect(if (coverDark) Color(0xFF3A3A3A) else Color(0xFF8E897C), Offset(x - wdt / 2, g.cy - hh), Size(wdt, hh * 2), CornerRadius(wdt / 2), style = Stroke(1.4f)); poles(x, n, Color(0xFF7E848A)) }
                "P90", "SB" -> { val wdt = g.halfNut * 0.9f; drawRoundRect(if (kindPu == "SB" || coverDark) Color(0xFF121212) else Color(0xFFEDE4CC), Offset(x - wdt / 2, g.cy - hh * 1.05f), Size(wdt, hh * 2.1f), CornerRadius(wdt * 0.25f)); if (kindPu == "P90") poles(x, n, Color(0xFF9AA0A6)) }
                "H", "MH", "FH" -> { val sc = if (kindPu == "MH") 0.75f else 1f; val wdt = g.halfNut * 1.25f * sc
                    drawRoundRect(metal, Offset(x - wdt / 2 - 3, g.cy - hh * 1.1f), Size(wdt + 6, hh * 2.2f), CornerRadius(4f))
                    if (b.hardware == "hw_gold" || kindPu == "FH") drawRoundRect(Brush.linearGradient(listOf(hw[0].lighten(0.3f), hw[0], hw[1])), Offset(x - wdt / 2, g.cy - hh), Size(wdt, hh * 2), CornerRadius(3f))
                    else { drawRect(Color(0xFF141414), Offset(x - wdt / 2, g.cy - hh), Size(wdt / 2 - 1, hh * 2)); drawRect(if (b.finish == "gold_top") Color(0xFFEDE4CC) else Color(0xFF141414), Offset(x + 1, g.cy - hh), Size(wdt / 2 - 1, hh * 2)); poles(x - wdt / 4, n, Color(0xFFB8BEC4)); poles(x + wdt / 4, n, Color(0xFF8A9096)) } }
                "AH" -> { val wdt = g.halfNut * 1.25f; drawRoundRect(Brush.verticalGradient(listOf(Color(0xFF2A2A2A), Color(0xFF0A0A0A))), Offset(x - wdt / 2, g.cy - hh), Size(wdt, hh * 2), CornerRadius(3f)) }
                "P" -> { val wdt = g.halfNut * 0.6f; drawRoundRect(Color(0xFF121212), Offset(x - wdt, g.cy - hh * 0.95f), Size(wdt, hh * 0.95f), CornerRadius(4f)); drawRoundRect(Color(0xFF121212), Offset(x + 2, g.cy), Size(wdt, hh * 0.95f), CornerRadius(4f))
                    poles(x - wdt / 2, 2, Color(0xFF9AA0A6), hh * 0.4f); for (k in 0..1) drawCircle(Color(0xFF9AA0A6), g.halfNut * 0.07f, Offset(x + 2 + wdt / 2, g.cy + hh * (0.25f + k * 0.45f))) }
                "J" -> { val wdt = g.halfNut * 0.5f; drawRoundRect(Color(0xFF121212), Offset(x - wdt / 2, g.cy - hh * 1.05f), Size(wdt, hh * 2.1f), CornerRadius(5f)); poles(x, n * 2, Color(0xFF8A9096)) }
                "MM" -> { val wdt = g.halfNut * 1.3f; drawRoundRect(Color(0xFF101010), Offset(x - wdt / 2, g.cy - hh * 1.05f), Size(wdt, hh * 2.1f), CornerRadius(wdt * 0.35f)); poles(x - wdt / 4, n, Color(0xFFB8BEC4)); poles(x + wdt / 4, n, Color(0xFFB8BEC4)) }
                "SH" -> { val wdt = g.halfNut * 0.7f; drawRoundRect(Color(0xFF151515), Offset(x - wdt / 2, g.cy - hh), Size(wdt, hh * 2), CornerRadius(5f)); poles(x, n, Color(0xFFB8BEC4)) }
                else -> {}
            } }
        // ---- bridge
        val br = StringCatalog.part(b.bridge)?.style ?: "hardtail"; val bx = g.bridgeX; val bh2 = g.halfBody * 1.25f
        when (br) {
            "trem", "locking", "hardtail", "bassplate", "bassheavy" -> { val wdt = g.halfNut * (if (br == "locking") 1.6f else 1.2f)
                drawRoundRect(metal, Offset(bx - wdt * 0.85f, g.cy - bh2), Size(wdt * (if (br == "bassplate") 1.6f else 1.2f), bh2 * 2), CornerRadius(4f))
                for (i in 0 until g.strings) drawRoundRect(Brush.linearGradient(listOf(hw[0].lighten(0.4f), hw[1])), Offset(bx - wdt * 0.25f, g.stringY(i, bx) - bh2 / g.strings * 0.45f), Size(wdt * 0.5f, bh2 / g.strings * 0.9f), CornerRadius(2f))
                if (br == "locking") for (i in 0 until g.strings) drawCircle(hw[1], g.halfNut * 0.08f, Offset(bx - wdt * 0.7f, g.stringY(i, bx)))
                if (br == "trem" || br == "locking") drawLine(hw[0], Offset(bx - wdt * 0.5f, g.cy + bh2), Offset(bx + g.bodyLen * 0.12f, g.cy + bh2 + g.bodyH * 0.22f), max(2f, g.halfNut * 0.08f), StrokeCap.Round) }
            "ashtray" -> { val wdt = g.halfNut * 1.8f; drawRoundRect(metal, Offset(bx - wdt * 0.7f, g.cy - bh2 * 1.1f), Size(wdt, bh2 * 2.2f), CornerRadius(4f)); drawRoundRect(Color.Black.copy(alpha = 0.15f), Offset(bx - wdt * 0.6f, g.cy - bh2 * 1.0f), Size(wdt * 0.8f, bh2 * 2f), CornerRadius(3f))
                for (k in 0 until 3) drawRoundRect(Brush.linearGradient(listOf(hw[0].lighten(0.4f), hw[1])), Offset(bx - wdt * 0.2f, g.cy - bh2 + 2 * bh2 * k / 3f + 2), Size(wdt * 0.35f, 2 * bh2 / 3f - 4), CornerRadius(3f)) }
            "tom", "armtail" -> { drawRoundRect(metal, Offset(bx - g.halfNut * 0.2f, g.cy - bh2), Size(g.halfNut * 0.4f, bh2 * 2), CornerRadius(3f))
                for (i in 0 until g.strings) drawRect(hw[1], Offset(bx - g.halfNut * 0.1f, g.stringY(i, bx) - 2), Size(g.halfNut * 0.2f, 4f))
                if (br == "tom") drawRoundRect(metal, Offset(bx - g.bodyLen * 0.1f, g.cy - bh2 * 1.05f), Size(g.halfNut * 0.45f, bh2 * 2.1f), CornerRadius(g.halfNut * 0.2f))
                else { drawRoundRect(metal, Offset(g.left + g.bodyLen * 0.02f, g.cy - bh2 * 1.3f), Size(g.bodyLen * 0.1f, bh2 * 2.6f), CornerRadius(8f)); drawRect(Brush.horizontalGradient(listOf(hw[1], hw[0].lighten(0.4f), hw[1])), Offset(g.left + g.bodyLen * 0.06f, g.cy - bh2 * 1.1f), Size(g.halfNut * 0.3f, bh2 * 2.2f)) } }
            "trapeze" -> { drawRoundRect(Color(0xFF2A1A10), Offset(bx - g.halfNut * 0.25f, g.cy - bh2 * 1.1f), Size(g.halfNut * 0.5f, bh2 * 2.2f), CornerRadius(3f)); drawRect(hw[1], Offset(bx - 1.5f, g.cy - bh2), Size(3f, bh2 * 2))
                val tp = Path().apply { moveTo(g.left + 4, g.cy - bh2 * 0.4f); lineTo(bx - g.bodyLen * 0.08f, g.cy - bh2 * 1.1f); lineTo(bx - g.bodyLen * 0.08f, g.cy + bh2 * 1.1f); lineTo(g.left + 4, g.cy + bh2 * 0.4f); close() }; drawPath(tp, metal, style = Stroke(g.halfNut * 0.14f)) }
            "pin" -> { drawRoundRect(Brush.verticalGradient(listOf(fb[0].lighten(0.1f), fb[1])), Offset(bx - g.halfNut * 0.9f, g.cy - bh2 * 1.6f), Size(g.halfNut * 1.4f, bh2 * 3.2f), CornerRadius(g.halfNut * 0.5f))
                drawRect(Color(0xFFF4EEDC), Offset(bx - 1.5f, g.cy - bh2), Size(3f, bh2 * 2)); for (i in 0 until g.strings) drawCircle(Brush.radialGradient(listOf(Color.White, Color(0xFFD6CCB4))), g.halfNut * 0.11f, Offset(bx - g.halfNut * 0.45f, g.stringY(i, bx))) }
        }
        // ---- knobs and switch
        if (kind != "acoustic") { val knobs = if (b.bass) 3 else if (layout.size >= 3) 3 else if (kind == "carved" || kind == "semi" || kind == "hollow") 4 else 2
            for (k in 0 until knobs) { val pt = if (knobs == 4) g.bodyPt(0.2f + (k % 2) * 0.12f, 0.42f + (k / 2) * 0.2f) else g.bodyPt(0.18f + k * 0.11f, 0.5f - k * 0.06f); val r = g.bodyH * 0.035f
                drawCircle(Color(0x66000000), r, pt + Offset(1.5f, 2.5f))
                drawCircle(Brush.radialGradient(if (b.hardware == "hw_gold" || kind == "carved") listOf(Color(0xFFF2D27A), Color(0xFF9A7428)) else if (coverDark) listOf(Color(0xFF3A3A3A), Color(0xFF080808)) else listOf(Color(0xFFFFFFFF), Color(0xFFCFC8B8)), pt - Offset(r * 0.3f, r * 0.3f), r * 1.3f), r, pt)
                for (s in 0 until 12) { val a = s / 12f * 6.283f; drawLine(Color.Black.copy(alpha = 0.18f), pt + Offset(cos(a), sin(a)) * r * 0.75f, pt + Offset(cos(a), sin(a)) * r, 1f) } }
            if (layout.size >= 2) { val sw = if (kind == "carved" || kind == "semi" || kind == "hollow") g.bodyPt(0.78f, -0.7f) else g.bodyPt(0.42f, 0.62f)
                drawCircle(metal, g.bodyH * 0.02f, sw); drawLine(Color(0xFFF2EEE2), sw, sw + Offset(g.bodyH * 0.03f, -g.bodyH * 0.04f), g.bodyH * 0.012f, StrokeCap.Round) }
            // output jack
            drawCircle(metal, g.bodyH * 0.025f, g.bodyPt(0.1f, 0.78f)) }

        // ---- strings (with ring animation for played strings)
        val tail = when (br) { "trapeze", "armtail" -> g.left + g.bodyLen * 0.06f; "tom" -> g.bridgeX - g.bodyLen * 0.08f; else -> g.bridgeX }
        for (i in 0 until g.strings) {
            val gauge = if (b.bass) 1.2f + (g.strings - 1 - i) * 0.0f + (g.strings - i) * 0.5f else 0.6f + (g.strings - 1 - i) * 0.32f
            val wpx = max(0.8f, gauge * g.halfNut / 22f)
            val y0 = g.stringY(i, g.bridgeX); val y1 = g.stringY(i, g.nutX)
            val ringAge = ringing[i]?.let { now - it } ?: Long.MAX_VALUE
            val amp = if (ringAge < 900) (1f - ringAge / 900f) * g.halfNut * 0.12f else 0f
            val col = if (b.strings.contains("bronze", true)) Color(0xFFD8B070) else Color(0xFFDADDE0)
            if (amp > 0f) { val pp = Path().apply { moveTo(g.bridgeX, y0); quadraticBezierTo((g.bridgeX + g.nutX) / 2, (y0 + y1) / 2 + amp * sin(ringAge * 0.12f), g.nutX, y1) }
                drawPath(pp, Color(0x44FFE6A0), style = Stroke(wpx * 4)); drawPath(pp, col, style = Stroke(wpx)) }
            else { drawLine(Color(0x55000000), Offset(g.bridgeX, y0 + 2), Offset(g.nutX, y1 + 2), wpx); drawLine(col, Offset(g.bridgeX, y0), Offset(g.nutX, y1), wpx); drawLine(Color.White.copy(alpha = 0.5f), Offset(g.bridgeX, y0 - wpx * 0.3f), Offset(g.nutX, y1 - wpx * 0.3f), max(0.5f, wpx * 0.3f)) }
            if (tail < g.bridgeX) drawLine(col.darken(0.2f), Offset(tail, g.cy + (y0 - g.cy) * 0.8f), Offset(g.bridgeX, y0), wpx)
            tunerPts.getOrNull(i)?.let { drawLine(col.darken(0.1f), Offset(g.nutX, y1), it, wpx * 0.9f) }
        }
    }

    /** (string index, fret) under a touch on the drawn neck, or an open string when struck over the body/pickups. */
    fun hit(g: StringGeo, o: Offset, b: StringBuild): Pair<Int, Int>? {
        if (o.x > g.nutX + g.headLen || o.x < g.left + g.bodyLen * 0.1f) return null
        val half = if (o.x >= g.bridgeX) g.halfAt(o.x) * 0.82f else g.halfBody * 0.95f
        if (abs(o.y - g.cy) > half * 1.35f) return null
        val i = (((o.y - (g.cy - half)) / (2 * half)) * (g.strings - 1)).roundToInt().coerceIn(0, g.strings - 1)
        val fret = if (o.x >= g.nutX || o.x < g.neckEndX) 0 else (1..g.frets).firstOrNull { g.fretX(it) <= o.x } ?: g.frets
        return i to fret
    }
}
