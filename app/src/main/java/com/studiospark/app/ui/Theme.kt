package com.studiospark.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Palette: Pro Tools-like graphite edit window, AmpliTube-like dark chrome for gear, StudioSpark amber (or Gold) accent. */
data class Palette(
    val name: String,
    val window: Color, val panel: Color, val header: Color, val raised: Color, val inset: Color, val line: Color, val lineSoft: Color,
    val text: Color, val textDim: Color, val textFaint: Color,
    val accent: Color, val accentDim: Color, val select: Color,
    val rec: Color, val solo: Color, val mute: Color, val monitor: Color, val play: Color,
    val chromeTop: Color, val chromeBottom: Color, val wood: Color, val timeline: Color, val gridBar: Color, val gridBeat: Color,
)

val Graphite = Palette("Graphite",
    window = Color(0xFF131416), panel = Color(0xFF1A1C1F), header = Color(0xFF212428), raised = Color(0xFF2A2E33), inset = Color(0xFF0E0F11), line = Color(0xFF353A40), lineSoft = Color(0xFF272B30),
    text = Color(0xFFE7E9EB), textDim = Color(0xFFA2A9B1), textFaint = Color(0xFF6C747D),
    accent = Color(0xFFE8A33D), accentDim = Color(0xFF7A5A28), select = Color(0xFF5B9BD5),
    rec = Color(0xFFE2463F), solo = Color(0xFFE9C640), mute = Color(0xFFE0813A), monitor = Color(0xFF4DB884), play = Color(0xFF5CC46E),
    chromeTop = Color(0xFF2B2D31), chromeBottom = Color(0xFF151618), wood = Color(0xFF5E4029), timeline = Color(0xFF17191C), gridBar = Color(0xFF30353B), gridBeat = Color(0xFF212429))

val Gold = Palette("Gold",
    window = Color(0xFF15120C), panel = Color(0xFF1D1910), header = Color(0xFF262014), raised = Color(0xFF31291A), inset = Color(0xFF0F0D08), line = Color(0xFF4A3F27), lineSoft = Color(0xFF30281A),
    text = Color(0xFFF4EBD3), textDim = Color(0xFFC9B98E), textFaint = Color(0xFF8C7D58),
    accent = Color(0xFFD9B44A), accentDim = Color(0xFF7D6528), select = Color(0xFFE3C46A),
    rec = Color(0xFFE2463F), solo = Color(0xFFF2D35B), mute = Color(0xFFE0913A), monitor = Color(0xFF7FC48A), play = Color(0xFF8CCB6E),
    chromeTop = Color(0xFF332A18), chromeBottom = Color(0xFF17130B), wood = Color(0xFF6A4A27), timeline = Color(0xFF19150D), gridBar = Color(0xFF3D3320), gridBeat = Color(0xFF261F14))

val LocalPalette = staticCompositionLocalOf { Graphite }

object Type {
    val label = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp)
    val small = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium)
    val body = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Normal)
    val bodyStrong = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    val title = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.2.sp)
    val display = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Light, letterSpacing = 0.5.sp)
    val counter = TextStyle(fontSize = 20.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
    val mono = TextStyle(fontSize = 11.sp, fontFamily = FontFamily.Monospace)
}

@Composable
fun StudioTheme(gold: Boolean, content: @Composable () -> Unit) {
    val p = if (gold) Gold else Graphite
    CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(colorScheme = darkColorScheme(primary = p.accent, onPrimary = Color.Black, surface = p.panel, onSurface = p.text, background = p.window, onBackground = p.text,
            surfaceVariant = p.raised, onSurfaceVariant = p.textDim, outline = p.line, secondary = p.select, error = p.rec), content = content)
    }
}
