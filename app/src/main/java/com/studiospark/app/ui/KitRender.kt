package com.studiospark.app.ui

import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import com.studiospark.app.core.*
import com.studiospark.app.core.Shape
import kotlin.math.*

/** Kit camera: head-over (overhead) or behind the kit (drummer's seat). */
enum class KitView(val label: String) { OVERHEAD("Overhead"), DRUMMER("Behind the kit") }

/** A piece projected to screen space. Linked zones (open hat edge, ride bell, rim, hat foot, left beater) are hit regions on their host. */
class Placed(val piece: KitPiece, val shape: Shape, val c: Offset, val rx: Float, val ry: Float, val order: Float, val finish: Finish?, val elevated: Boolean)

private val CHROME = listOf(Color(0xFF5E6368), Color(0xFFF4F6F8), Color(0xFF8A9096), Color(0xFFDDE1E5), Color(0xFF5E6368))
private val isCymbal = setOf(Shape.HIHAT, Shape.CRASH, Shape.RIDE, Shape.CHINA, Shape.SPLASH, Shape.STACK, Shape.BELL, Shape.GONG)
private val isDrum = setOf(Shape.SNARE, Shape.TOM, Shape.FLOOR_TOM)

object KitRender {
    fun finishOf(kit: DrumKit, p: KitPiece, shape: Shape): Finish? = Finishes.find(p.finish.ifEmpty { if (shape in isCymbal) kit.cymbalFinish else kit.shellFinish })
        ?: if (shape in isCymbal) Finishes.cymbals[1] else Finishes.shells[0]

    fun layout(kit: DrumKit, view: KitView, w: Float, h: Float): List<Placed> {
        val out = ArrayList<Placed>()
        for (p in kit.pieces) {
            if (p.role in setOf("edge", "bell", "rim", "left")) continue
            val sh = if (p.role == "foot") Shape.PEDAL_SINGLE else DrumCatalog.shapeOf(p)
            val f = finishOf(kit, p, sh); val r0 = p.size * w / 2
            if (view == KitView.OVERHEAD) {
                val c = Offset(p.x * w, (0.03f + p.y * 0.88f) * h)
                val order = when { sh == Shape.RUG -> -10f; sh == Shape.RACK -> -5f; sh == Shape.THRONE || sh == Shape.PEDAL_SINGLE || sh == Shape.PEDAL_DOUBLE -> -3f; sh == Shape.KICK -> -2f; sh in isCymbal -> 5f + p.y; else -> p.y }
                val (rx, ry) = when (sh) { Shape.KICK -> r0 to (DrumCatalog.get(p.catalogId)?.depthIn ?: 18f) * 0.0085f * w / 2; Shape.RUG -> w * 0.46f to h * 0.47f
                    Shape.PEDAL_SINGLE, Shape.PEDAL_DOUBLE -> w * 0.03f to h * 0.07f; Shape.THRONE -> w * 0.075f to w * 0.075f; Shape.RACK -> w * 0.36f to h * 0.05f; else -> r0 to r0 }
                out += Placed(p, sh, c, rx, ry, order, f, sh in isCymbal)
            } else {
                val y = p.y; val s = 0.62f + 0.55f * y
                val sx = w * (0.5f + (p.x - 0.5f) * (0.82f + 0.38f * y))
                val rx = r0 * s
                when (sh) {
                    Shape.RUG -> out += Placed(p, sh, Offset(w / 2, h * 0.80f), w * 0.62f, h * 0.24f, -10f, f, false)
                    Shape.KICK -> out += Placed(p, sh, Offset(sx, h * (0.40f + 0.38f * y)), rx * 0.95f, rx * 0.95f, -2f + y * 0.1f, f, false)
                    Shape.THRONE -> out += Placed(p, sh, Offset(w / 2, h * 1.02f), w * 0.16f, h * 0.06f, 20f, f, false)
                    Shape.PEDAL_SINGLE, Shape.PEDAL_DOUBLE -> out += Placed(p, sh, Offset(sx, h * (0.62f + 0.32f * y)), w * 0.035f * s, h * 0.06f, 15f, f, false)
                    Shape.RACK -> out += Placed(p, sh, Offset(w / 2, h * 0.34f), w * 0.42f, h * 0.02f, -4f, f, false)
                    in isCymbal -> out += Placed(p, sh, Offset(sx, h * (0.08f + 0.46f * y)), rx, rx * 0.22f, 8f + y, f, true)
                    else -> out += Placed(p, sh, Offset(sx, h * (0.22f + 0.52f * y)), rx, rx * 0.42f, y + 1f, f, false)
                }
            }
        }
        return out.sortedBy { it.order }
    }

    /** Topmost piece under [o]; returns the MIDI piece to trigger (zones resolved) and a velocity from where it was struck. */
    fun hit(kit: DrumKit, placed: List<Placed>, o: Offset, view: KitView): Pair<KitPiece, Int>? {
        for (pl in placed.asReversed()) {
            val dx = (o.x - pl.c.x) / max(1f, pl.rx); val dy = (o.y - pl.c.y) / max(1f, pl.ry)
            val inside = when (pl.shape) { Shape.KICK -> if (view == KitView.OVERHEAD) abs(dx) <= 1f && abs(dy) <= 1f else dx * dx + dy * dy <= 1f
                Shape.PEDAL_SINGLE, Shape.PEDAL_DOUBLE -> abs(dx) <= (if (pl.shape == Shape.PEDAL_DOUBLE) 2.4f else 1.3f) && abs(dy) <= 1.2f
                Shape.RUG, Shape.RACK, Shape.THRONE, Shape.STAND, Shape.BOOM, Shape.HAT_STAND, Shape.SNARE_STAND, Shape.CLAMP, Shape.BEATER, Shape.KEY -> false
                else -> dx * dx + dy * dy <= 1.05f }
            if (!inside) continue
            val p = pl.piece; val r = sqrt(dx * dx + dy * dy).coerceAtMost(1f)
            fun zone(role: String) = kit.pieces.firstOrNull { it.link == p.id && it.role == role }
            val target = when {
                p.role == "foot" -> p
                pl.shape == Shape.PEDAL_SINGLE -> kit.pieces.firstOrNull { DrumCatalog.shapeOf(it) == Shape.KICK && it.role.isEmpty() }
                pl.shape == Shape.PEDAL_DOUBLE -> if (dx < 0) kit.pieces.firstOrNull { it.role == "left" } else kit.pieces.firstOrNull { DrumCatalog.shapeOf(it) == Shape.KICK && it.role.isEmpty() }
                pl.shape == Shape.HIHAT && r > 0.72f -> zone("edge") ?: p
                pl.shape == Shape.RIDE && r < 0.26f -> zone("bell") ?: p
                pl.shape == Shape.SNARE && r > 0.86f -> zone("rim") ?: p
                else -> p
            } ?: continue
            if (!target.sounding) return target to 0
            val vel = when (pl.shape) { in isDrum, Shape.KICK -> (122 - 36 * r * r).toInt(); else -> (104 + 18 * r).toInt() }.coerceIn(30, 127)
            return target to vel
        }
        return null
    }

    // ------------------------------------------------------------------ drawing
    fun DrawScope.stage(view: KitView) {
        if (view == KitView.OVERHEAD) {
            drawRect(Brush.verticalGradient(listOf(Color(0xFF2A1E15), Color(0xFF1B140E))))
            val plank = size.height / 9; for (i in 0..9) drawLine(Color.Black.copy(alpha = 0.28f), Offset(0f, i * plank), Offset(size.width, i * plank), 1.5f)
            for (i in 0..9) for (j in 0..3) drawLine(Color.Black.copy(alpha = 0.18f), Offset(((j * 0.27f + i * 0.13f) % 1f) * size.width, i * plank), Offset(((j * 0.27f + i * 0.13f) % 1f) * size.width, (i + 1) * plank), 1f)
        } else {
            val hz = size.height * 0.36f
            drawRect(Brush.verticalGradient(listOf(Color(0xFF26292D), Color(0xFF32363B)), 0f, hz), size = Size(size.width, hz))
            for (i in 0..12) { val x = size.width * i / 12f; drawLine(Color.Black.copy(alpha = 0.25f), Offset(x, 0f), Offset(x, hz), 2f) }
            drawRect(Brush.verticalGradient(listOf(Color(0xFF3A2A1C), Color(0xFF1E160F)), hz, size.height), Offset(0f, hz), Size(size.width, size.height - hz))
            for (i in 0..14) { val t = i / 14f; val y = hz + (size.height - hz) * t * t; drawLine(Color.Black.copy(alpha = 0.2f), Offset(0f, y), Offset(size.width, y), 1.2f) }
        }
    }

    fun DrawScope.drawKit(kit: DrumKit, placed: List<Placed>, view: KitView, selected: String?, flash: String?) {
        for (pl in placed) {
            val sel = pl.piece.id == selected || kit.pieces.any { it.id == selected && it.link == pl.piece.id }
            val lit = pl.piece.id == flash || kit.pieces.any { it.id == flash && it.link == pl.piece.id }
            if (view == KitView.DRUMMER && pl.shape == Shape.TOM) placed.firstOrNull { it.shape == Shape.KICK }?.let { k ->
                // rack-tom mount arm from the bass drum shell
                val top = Offset(k.c.x + (pl.c.x - k.c.x) * 0.25f, k.c.y - k.ry * 0.92f); val bot = Offset(pl.c.x, pl.c.y + pl.ry * 1.6f)
                drawLine(Color(0xFF6E747A), top + Offset(1.5f, 1.5f), bot + Offset(1.5f, 1.5f), max(3f, pl.rx * 0.07f)); drawLine(Color(0xFFDDE2E6), top, bot, max(2f, pl.rx * 0.05f))
                drawCircle(Color(0xFFB8BEC4), max(3f, pl.rx * 0.08f), top) }
            if (view == KitView.OVERHEAD) overheadPiece(pl, kit) else drummerPiece(pl, kit)
            if (lit) drawOval(Color.White.copy(alpha = 0.22f), pl.c - Offset(pl.rx, pl.ry), Size(pl.rx * 2, pl.ry * 2))
            if (sel) drawOval(Color(0xFFE8A33D), pl.c - Offset(pl.rx + 4, pl.ry + 4), Size(pl.rx * 2 + 8, pl.ry * 2 + 8), style = Stroke(3f))
        }
    }

    private fun rnd(seed: Int) = java.util.Random(seed.toLong())
    private fun DrawScope.shadow(c: Offset, rx: Float, ry: Float, k: Float = 1f) = drawOval(Color.Black.copy(alpha = 0.35f * k), c + Offset(rx * 0.08f, ry * 0.12f + 3f) - Offset(rx * 1.04f, ry * 1.04f), Size(rx * 2.08f, ry * 2.08f))

    private fun DrawScope.shellFill(f: Finish, c: Offset, rx: Float, ry: Float, seed: Int) {
        val base = Color(f.base); val acc = Color(f.accent)
        drawOval(Brush.radialGradient(listOf(acc.copy(alpha = 1f), base, base.darken(0.45f)), c - Offset(rx * 0.35f, ry * 0.45f), max(rx, ry) * 1.6f), c - Offset(rx, ry), Size(rx * 2, ry * 2))
        sparkle(f, c, rx, ry, seed)
    }
    private fun DrawScope.sparkle(f: Finish, c: Offset, rx: Float, ry: Float, seed: Int) {
        if (f.kind == "sparkle") { val r = rnd(seed); repeat(90) { val a = r.nextFloat() * 6.283f; val d = sqrt(r.nextFloat()); drawCircle(Color(f.accent).copy(alpha = 0.35f + 0.6f * r.nextFloat()), 0.6f + r.nextFloat() * 1.4f, c + Offset(cos(a) * rx * d, sin(a) * ry * d)) } }
        if (f.kind == "pearl") { val r = rnd(seed); repeat(14) { val a = r.nextFloat() * 6.283f; val d = sqrt(r.nextFloat()) * 0.9f; drawOval(Color(0xFFFFFFFF).copy(alpha = 0.25f), c + Offset(cos(a) * rx * d, sin(a) * ry * d), Size(rx * 0.28f, ry * 0.16f)) } }
        if (f.kind == "wood") { for (i in 1..6) drawOval(Color.Black.copy(alpha = 0.05f), c - Offset(rx * i / 7f, ry * i / 7f), Size(rx * 2 * i / 7f, ry * 2 * i / 7f), style = Stroke(1f)) }
    }
    private fun DrawScope.chromeRing(c: Offset, rx: Float, ry: Float, w: Float) = drawOval(Brush.sweepGradient(CHROME, c), c - Offset(rx, ry), Size(rx * 2, ry * 2), style = Stroke(w))
    private fun DrawScope.head(c: Offset, rx: Float, ry: Float, dark: Boolean = false) {
        drawOval(Brush.radialGradient(if (dark) listOf(Color(0xFF3A3A3C), Color(0xFF151516)) else listOf(Color(0xFFF7F4EC), Color(0xFFE4DFD2), Color(0xFFC9C2B2)), c - Offset(rx * 0.2f, ry * 0.25f), max(rx, ry) * 1.3f), c - Offset(rx, ry), Size(rx * 2, ry * 2))
        drawOval(Color.Black.copy(alpha = 0.05f), c - Offset(rx * 0.55f, ry * 0.55f), Size(rx * 1.1f, ry * 1.1f), style = Stroke(1f))
    }

    private fun DrawScope.cymbal(c: Offset, rx: Float, ry: Float, f: Finish, shape: Shape, seed: Int) {
        val base = Color(f.base); val acc = Color(f.accent)
        drawOval(Brush.radialGradient(listOf(acc, base, base.darken(0.3f)), c, max(rx, ry)), c - Offset(rx, ry), Size(rx * 2, ry * 2))
        val rings = (rx / 2.2f).toInt().coerceIn(6, 60)
        for (i in 2..rings) { val k = i / rings.toFloat(); drawOval((if (i % 2 == 0) Color.White else Color.Black).copy(alpha = if (f.kind == "raw") 0.03f else 0.07f), c - Offset(rx * k, ry * k), Size(rx * 2 * k, ry * 2 * k), style = Stroke(0.8f)) }
        if (f.kind == "hammered" || f.kind == "raw") { val r = rnd(seed); repeat(40) { val a = r.nextFloat() * 6.283f; val d = 0.3f + 0.65f * r.nextFloat(); drawOval(base.darken(0.35f).copy(alpha = 0.5f), c + Offset(cos(a) * rx * d, sin(a) * ry * d), Size(rx * 0.06f, ry * 0.06f)) } }
        if (shape == Shape.CHINA) drawOval(base.darken(0.35f), c - Offset(rx, ry), Size(rx * 2, ry * 2), style = Stroke(max(2f, rx * 0.12f)))
        // specular sheen
        drawOval(Brush.sweepGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.28f), Color.Transparent, Color.Transparent, Color.White.copy(alpha = 0.2f), Color.Transparent), c), c - Offset(rx, ry), Size(rx * 2, ry * 2))
        val br = if (shape == Shape.BELL) 0.6f else if (shape == Shape.RIDE) 0.3f else 0.22f
        drawOval(Brush.radialGradient(listOf(acc.lighten(0.2f), base), c, rx * br), c - Offset(rx * br, ry * br), Size(rx * 2 * br, ry * 2 * br))
        drawCircle(Color(0xFF1A1A1A), max(1.5f, rx * 0.035f), c); drawCircle(Color(0xFFD0D3D6), max(1f, rx * 0.022f), c)
    }

    private fun DrawScope.overheadPiece(pl: Placed, kit: DrumKit) {
        val c = pl.c; val rx = pl.rx; val ry = pl.ry; val f = pl.finish; val seed = pl.piece.id.hashCode()
        when (pl.shape) {
            Shape.RUG -> { drawRoundRect(Color(0xFF5A1C1E), c - Offset(rx, ry), Size(rx * 2, ry * 2), CornerRadius(12f)); drawRoundRect(Color(0xFF3A1012), c - Offset(rx * 0.95f, ry * 0.95f), Size(rx * 1.9f, ry * 1.9f), CornerRadius(10f), style = Stroke(3f)) }
            Shape.RACK -> { val col = if (pl.piece.finish == "piano_black") listOf(Color(0xFF222326), Color(0xFF55585C), Color(0xFF222326)) else listOf(Color(0xFF6A6F75), Color(0xFFF2F4F6), Color(0xFF6A6F75))
                val path = Path().apply { moveTo(c.x - rx, c.y + ry * 3); quadraticBezierTo(c.x, c.y - ry * 2, c.x + rx, c.y + ry * 3) }; drawPath(path, Brush.verticalGradient(col), style = Stroke(ry * 0.9f, cap = StrokeCap.Round)) }
            Shape.THRONE -> { shadow(c, rx, ry); drawCircle(Brush.sweepGradient(CHROME, c), rx * 1.05f, c); drawCircle(Brush.radialGradient(listOf(Color(0xFF3A3A3C), Color(0xFF0E0E0F)), c, rx), rx * 0.95f, c); drawCircle(Color.White.copy(alpha = 0.08f), rx * 0.8f, c, style = Stroke(1.5f)) }
            Shape.PEDAL_SINGLE, Shape.PEDAL_DOUBLE -> { val boards = if (pl.shape == Shape.PEDAL_DOUBLE) listOf(-1.25f, 1.25f) else listOf(0f)
                for (b in boards) { val bc = c + Offset(b * rx, 0f); drawRoundRect(Brush.horizontalGradient(CHROME), bc - Offset(rx * 0.9f, ry), Size(rx * 1.8f, ry * 2), CornerRadius(6f)); drawRoundRect(Color(0xFF2A2C2F), bc - Offset(rx * 0.6f, ry * 0.85f), Size(rx * 1.2f, ry * 1.6f), CornerRadius(4f)) }
                drawRect(Brush.horizontalGradient(CHROME), c - Offset(rx * 1.3f, ry + 6f), Size(rx * 2.6f, 5f))
                if (pl.shape == Shape.PEDAL_DOUBLE) drawLine(Color(0xFFB8BCC0), c + Offset(-rx * 1.25f, -ry), c + Offset(0f, -ry - 8f), 3f) }
            Shape.KICK -> { shadow(c, rx, ry); val base = Color(f!!.base); val acc = Color(f.accent)
                drawRoundRect(Brush.horizontalGradient(listOf(base.darken(0.5f), base, acc, base, base.darken(0.55f)), c.x - rx, c.x + rx), c - Offset(rx, ry), Size(rx * 2, ry * 2), CornerRadius(rx * 0.06f))
                sparkle(f, c, rx * 0.95f, ry * 0.9f, seed)
                for (e in listOf(-1f, 1f)) drawRect(Brush.horizontalGradient(CHROME, c.x - rx, c.x + rx), Offset(c.x - rx, c.y + e * ry - (if (e < 0) 0f else ry * 0.1f)), Size(rx * 2, ry * 0.1f))
                val r = rnd(seed); for (side in listOf(-1f, 1f)) for (i in 0..4) drawRoundRect(Brush.verticalGradient(CHROME), Offset(c.x + side * rx * 0.97f - 3f, c.y - ry * 0.8f + i * ry * 0.4f), Size(6f, ry * 0.16f), CornerRadius(3f))
                drawRoundRect(Brush.horizontalGradient(CHROME), Offset(c.x - 5f, c.y - ry * 0.2f), Size(10f, ry * 0.4f), CornerRadius(3f)); r.nextInt() }
            in isCymbal -> { val legs = pl.piece.role != "foot"
                if (legs) for (k in 0..2) { val a = -1.57f + k * 2.094f + 0.4f; drawLine(Color(0xFFB9BDC2), c, c + Offset(cos(a) * rx * 1.15f, sin(a) * rx * 1.15f), 3f, StrokeCap.Round) }
                shadow(c, rx, ry, 0.8f)
                if (pl.shape == Shape.HIHAT) cymbal(c + Offset(rx * 0.04f, ry * 0.05f), rx, ry, f!!, pl.shape, seed + 1)
                if (pl.shape == Shape.STACK) cymbal(c + Offset(rx * 0.08f, ry * 0.06f), rx * 1.12f, ry * 1.12f, f!!, Shape.CHINA, seed + 2)
                if (pl.shape == Shape.GONG) { drawCircle(Color(0xFF2A2C2F), rx * 1.12f, c, style = Stroke(rx * 0.06f)) }
                cymbal(c, rx, ry, f!!, pl.shape, seed) }
            in isDrum -> { shadow(c, rx, ry); shellFill(f!!, c, rx * 1.05f, ry * 1.05f, seed)
                val lugs = if (pl.piece.size > 0.12f) 10 else 8
                for (i in 0 until lugs) { val a = i * 6.283f / lugs; drawCircle(Brush.radialGradient(listOf(Color.White, Color(0xFF8A9096)), c + Offset(cos(a) * rx * 1.0f, sin(a) * ry * 1.0f), rx * 0.06f), rx * 0.045f, c + Offset(cos(a) * rx * 1.0f, sin(a) * ry * 1.0f)) }
                chromeRing(c, rx * 0.93f, ry * 0.93f, max(2f, rx * 0.08f)); head(c, rx * 0.88f, ry * 0.88f)
                if (pl.shape == Shape.SNARE) drawLine(Color(0xFFB8BCC0), c + Offset(rx * 0.95f, -ry * 0.1f), c + Offset(rx * 1.12f, -ry * 0.1f), 4f) }
            Shape.COWBELL -> { shadow(c, rx, ry); val p = Path().apply { moveTo(c.x - rx * 0.5f, c.y - ry); lineTo(c.x + rx * 0.5f, c.y - ry); lineTo(c.x + rx * 0.8f, c.y + ry); lineTo(c.x - rx * 0.8f, c.y + ry); close() }
                drawPath(p, Brush.horizontalGradient(listOf(Color(0xFF2B2B2D), Color(0xFF6A6C70), Color(0xFF2B2B2D)), c.x - rx, c.x + rx)); drawPath(p, Color(0xFF9A9DA2), style = Stroke(1.5f)) }
            Shape.TAMBOURINE -> { shadow(c, rx, ry); drawCircle(Color(0xFF8A5A2E), rx, c, style = Stroke(rx * 0.22f)); for (i in 0 until 8) { val a = i * 0.785f; drawCircle(Brush.radialGradient(listOf(Color.White, Color(0xFF7A7E84)), c + Offset(cos(a) * rx, sin(a) * rx), rx * 0.14f), rx * 0.12f, c + Offset(cos(a) * rx, sin(a) * rx)) } }
            Shape.BLOCK -> { shadow(c, rx, ry); drawRoundRect(Brush.verticalGradient(if (pl.piece.catalogId == "jamblock") listOf(Color(0xFFE23A2E), Color(0xFF8E1A14)) else if (pl.piece.catalogId == "clap") listOf(Color(0xFF3A3F45), Color(0xFF15171A)) else listOf(Color(0xFFB07A44), Color(0xFF6A4220))), c - Offset(rx, ry * 0.6f), Size(rx * 2, ry * 1.2f), CornerRadius(rx * 0.25f)) }
            Shape.CHIMES -> { drawLine(Color(0xFF3A2A1C), c - Offset(rx, 0f), c + Offset(rx, 0f), 6f); for (i in 0..14) drawLine(Brush.verticalGradient(CHROME), c + Offset(-rx + i * rx / 7f, 0f), c + Offset(-rx + i * rx / 7f, ry * (0.4f + i / 20f)), 3f) }
            else -> { drawCircle(Brush.sweepGradient(CHROME, c), max(6f, rx * 0.3f), c) }
        }
    }

    private fun DrawScope.drummerPiece(pl: Placed, kit: DrumKit) {
        val c = pl.c; val rx = pl.rx; val ry = pl.ry; val f = pl.finish; val seed = pl.piece.id.hashCode(); val floorY = size.height * 0.98f
        fun stand(top: Offset, bottom: Float) { drawLine(Brush.horizontalGradient(CHROME, top.x - 3, top.x + 3), top, Offset(top.x, bottom), 4f)
            for (k in listOf(-1f, 0f, 1f)) drawLine(Color(0xFFA9AEB3), Offset(top.x, bottom - 18f), Offset(top.x + k * 26f, bottom), 3f) }
        when (pl.shape) {
            Shape.RUG -> drawPath(Path().apply { moveTo(c.x - rx * 0.6f, c.y - ry); lineTo(c.x + rx * 0.6f, c.y - ry); lineTo(c.x + rx, c.y + ry); lineTo(c.x - rx, c.y + ry); close() }, Color(0xFF4A1618))
            Shape.RACK -> { drawRect(Brush.verticalGradient(CHROME, c.y - ry, c.y + ry), c - Offset(rx, ry), Size(rx * 2, ry * 2)); for (x in listOf(-rx, rx)) drawLine(Color(0xFFB9BDC2), c + Offset(x, 0f), Offset(c.x + x, floorY), 5f) }
            Shape.THRONE -> { drawOval(Brush.verticalGradient(listOf(Color(0xFF3A3A3C), Color(0xFF0E0E0F)), c.y - ry, c.y + ry), c - Offset(rx, ry), Size(rx * 2, ry * 2)) }
            Shape.PEDAL_SINGLE, Shape.PEDAL_DOUBLE -> { val boards = if (pl.shape == Shape.PEDAL_DOUBLE) listOf(-1.3f, 1.3f) else listOf(0f)
                for (b in boards) { val bc = c + Offset(b * rx, 0f); val p = Path().apply { moveTo(bc.x - rx * 0.6f, bc.y - ry); lineTo(bc.x + rx * 0.6f, bc.y - ry); lineTo(bc.x + rx, bc.y + ry); lineTo(bc.x - rx, bc.y + ry); close() }
                    drawPath(p, Brush.horizontalGradient(CHROME, bc.x - rx, bc.x + rx)); drawPath(p, Color(0xFF2A2C2F), style = Stroke(2f)) } }
            Shape.KICK -> { shadow(c, rx, ry); val base = Color(f!!.base)
                drawCircle(Brush.radialGradient(listOf(Color(f.accent), base, base.darken(0.5f)), c, rx * 1.05f), rx * 1.04f, c); sparkle(f, c, rx * 1.04f, rx * 1.04f, seed)
                for (i in 0 until 10) { val a = i * 0.628f; drawCircle(Color(0xFFDDE1E5), rx * 0.035f, c + Offset(cos(a) * rx, sin(a) * rx)) }
                chromeRing(c, rx * 0.95f, rx * 0.95f, rx * 0.06f); head(c, rx * 0.9f, rx * 0.9f)
                drawCircle(Color(0xFF2A2A2A).copy(alpha = 0.25f), rx * 0.12f, c + Offset(0f, rx * 0.35f)) }
            in isCymbal -> { stand(c, floorY.coerceAtMost(c.y + size.height * 0.6f)); if (pl.shape == Shape.HIHAT) { cymbal(c + Offset(0f, ry * 0.9f), rx, ry, f!!, Shape.HIHAT, seed + 1) }
                if (pl.shape == Shape.STACK) cymbal(c + Offset(0f, ry * 0.7f), rx * 1.1f, ry * 1.1f, f!!, Shape.CHINA, seed + 2); cymbal(c, rx, ry, f!!, pl.shape, seed) }
            in isDrum -> { val depth = (DrumCatalog.get(pl.piece.catalogId)?.depthIn ?: (if (pl.shape == Shape.FLOOR_TOM) 14f else if (pl.shape == Shape.SNARE) 5.5f else 9f)) / max(6f, pl.piece.size / 0.0085f)
                val hgt = rx * 2 * depth * 0.75f
                if (pl.shape == Shape.FLOOR_TOM) for (k in listOf(-0.8f, 0.8f)) drawLine(Color(0xFFB9BDC2), c + Offset(k * rx, hgt), Offset(c.x + k * rx * 1.1f, floorY.coerceAtMost(c.y + hgt + size.height * 0.3f)), 4f)
                if (pl.shape == Shape.SNARE) stand(Offset(c.x, c.y + hgt), floorY.coerceAtMost(c.y + hgt + size.height * 0.3f))
                val base = Color(f!!.base); val acc = Color(f.accent)
                drawRect(Brush.horizontalGradient(listOf(base.darken(0.6f), base, acc, base, base.darken(0.65f)), c.x - rx, c.x + rx), Offset(c.x - rx, c.y), Size(rx * 2, hgt))
                drawOval(base.darken(0.5f), Offset(c.x - rx, c.y + hgt - ry), Size(rx * 2, ry * 2)); sparkle(f, Offset(c.x, c.y + hgt / 2), rx, hgt / 2, seed)
                val lugs = 6; for (i in 0 until lugs) { val x = c.x + rx * cos(3.1416f - i * 3.1416f / (lugs - 1)) * 0.92f; drawRoundRect(Brush.horizontalGradient(CHROME, x - 3, x + 3), Offset(x - 3f, c.y + hgt * 0.25f), Size(6f, hgt * 0.5f), CornerRadius(3f)) }
                chromeRing(c, rx, ry, max(2f, rx * 0.07f)); head(c, rx * 0.95f, ry * 0.9f) }
            Shape.COWBELL -> { stand(c, c.y + 40f); drawRoundRect(Brush.horizontalGradient(listOf(Color(0xFF2B2B2D), Color(0xFF6A6C70), Color(0xFF2B2B2D)), c.x - rx, c.x + rx), c - Offset(rx * 0.6f, ry * 2f), Size(rx * 1.2f, ry * 4f), CornerRadius(4f)) }
            Shape.TAMBOURINE -> { stand(c, c.y + 50f); drawOval(Color(0xFF8A5A2E), c - Offset(rx, ry * 1.6f), Size(rx * 2, ry * 3.2f), style = Stroke(rx * 0.18f)) }
            Shape.BLOCK -> { stand(c, c.y + 40f); drawRoundRect(Brush.verticalGradient(listOf(Color(0xFFB07A44), Color(0xFF6A4220))), c - Offset(rx, ry * 1.2f), Size(rx * 2, ry * 2.4f), CornerRadius(rx * 0.2f)) }
            else -> {}
        }
    }

    /** Small catalog thumbnail: renders one catalog item in the overhead style. */
    fun DrawScope.thumb(item: CatalogItem, finishId: String) {
        val p = KitPiece("t", item.name, item.sample, 0.5f, 0.5f, when (item.shape) { Shape.KICK -> 0.8f; Shape.RUG -> 1f; in isCymbal -> 0.86f; else -> 0.8f }, catalogId = item.id, finish = item.finish.ifEmpty { finishId })
        val kit = DrumKit("t", "", listOf(p), shellFinish = finishId)
        val pl = layout(kit, KitView.OVERHEAD, size.width, size.height).firstOrNull() ?: return
        val adj = when (pl.shape) { Shape.PEDAL_SINGLE, Shape.PEDAL_DOUBLE -> Placed(p, pl.shape, pl.c, size.width * 0.14f, size.height * 0.36f, 0f, pl.finish, false)
            Shape.THRONE -> Placed(p, pl.shape, pl.c, size.width * 0.38f, size.width * 0.38f, 0f, pl.finish, false)
            Shape.RACK -> Placed(p, pl.shape, pl.c, size.width * 0.42f, size.height * 0.12f, 0f, pl.finish, false)
            Shape.RUG -> Placed(p, pl.shape, pl.c, size.width * 0.45f, size.height * 0.4f, 0f, pl.finish, false)
            Shape.KICK -> Placed(p, pl.shape, pl.c, size.width * 0.4f, size.height * 0.4f * ((item.depthIn / item.sizeIn).coerceIn(0.5f, 1f)), 0f, pl.finish, false)
            else -> pl }
        when (adj.shape) { Shape.STAND, Shape.BOOM, Shape.HAT_STAND, Shape.SNARE_STAND -> { val c = center; drawLine(Brush.horizontalGradient(CHROME), c - Offset(0f, size.height * 0.4f), c + Offset(0f, size.height * 0.3f), 4f)
                if (adj.shape == Shape.BOOM) drawLine(Color(0xFFDDE1E5), c - Offset(0f, size.height * 0.4f), c + Offset(size.width * 0.3f, -size.height * 0.45f), 3f)
                for (k in listOf(-1f, 0f, 1f)) drawLine(Color(0xFFA9AEB3), c + Offset(0f, size.height * 0.2f), c + Offset(k * size.width * 0.3f, size.height * 0.4f), 3f) }
            Shape.BEATER -> { drawLine(Brush.horizontalGradient(CHROME), Offset(center.x, size.height * 0.9f), Offset(center.x, size.height * 0.35f), 4f)
                drawRoundRect(when (item.id) { "beater_felt" -> Color(0xFFE9E4D8); "beater_wood" -> Color(0xFFA8743E); else -> Color(0xFF1E1F22) }, Offset(center.x - size.width * 0.14f, size.height * 0.12f), Size(size.width * 0.28f, size.height * 0.26f), CornerRadius(8f)) }
            Shape.KEY -> { drawCircle(Brush.sweepGradient(CHROME, center), size.width * 0.18f, center - Offset(0f, size.height * 0.12f), style = Stroke(6f)); drawLine(Color(0xFFC9CDD1), center, center + Offset(0f, size.height * 0.35f), 7f) }
            Shape.CLAMP -> { drawRoundRect(Brush.verticalGradient(CHROME), center - Offset(size.width * 0.25f, size.height * 0.12f), Size(size.width * 0.5f, size.height * 0.24f), CornerRadius(6f)); drawCircle(Color(0xFF2A2C2F), size.width * 0.06f, center) }
            else -> overheadPiece(adj, kit) }
    }
}
