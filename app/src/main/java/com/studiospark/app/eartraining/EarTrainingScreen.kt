package com.studiospark.app.eartraining

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Ear-training palette (2.2.0). */
object EarColors {
    val Background = Color(0xFF0E1214)
    val Accent = Color(0xFF3DDC97)
    val Text = Color(0xFFE7EEE9)
    val TextDim = Text.copy(alpha = 0.62f)
    val Raised = Text.copy(alpha = 0.07f)
    val Line = Text.copy(alpha = 0.18f)
    val Wrong = Text.copy(alpha = 0.28f)
}

/**
 * Compose-side holder. Created by [EarTrainingScreen] with remember, so nothing exists before the tab is first composed.
 * Source selection (stem scan) runs once on Dispatchers.Default when the screen first composes.
 */
class EarTrainerController(private val player: EarPlayer, private val stemClips: StemClipProvider?, private val scope: CoroutineScope) {
    val session = EarSession()
    var rev by mutableIntStateOf(0); private set
    var choice by mutableStateOf<SourceChoice?>(null); private set
    var rendering by mutableStateOf(false); private set
    private var cached: Pair<Question, ShortArray>? = null
    private var playJob: Job? = null

    suspend fun prepare() {
        if (choice != null) return
        choice = withContext(Dispatchers.Default) { EarSourceSelector.select(stemClips) }
        play()
    }
    fun play() {
        val src = choice?.source ?: return
        val q = session.question
        playJob?.cancel()
        playJob = scope.launch {
            val pcm = cached?.takeIf { it.first == q }?.second ?: run { rendering = true
                try { withContext(Dispatchers.Default) { src.render(q.notes, q.layout) }.also { cached = q to it } } finally { rendering = false } }
            runCatching { player.play(pcm) }
        }
    }
    fun answer(a: Any) { if (session.answer(a) != null) rev++ }
    fun next() { session.next(); rev++; play() }
    fun settings(s: QuizSettings) { session.updateSettings(s); rev++; play() }
    fun stop() { playJob?.cancel(); runCatching { player.stop() } }
}

/**
 * Ear-training tab entry point. Self-contained; initializes lazily on first composition.
 * @param player where clips play. Pass `PreviewEarPlayer { vm.engine.preview(it) }` to use the app engine; null = a private AudioTrack.
 * @param stemClips source of stem audio, e.g. `StemLibraryClips({ vm.importer().library })`. Null or empty = labelled synth fallback.
 */
@Composable
fun EarTrainingScreen(player: EarPlayer? = null, stemClips: StemClipProvider? = null, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val out = remember(player) { player ?: AudioTrackEarPlayer() }
    val c = remember(out, stemClips) { EarTrainerController(out, stemClips, scope) }
    LaunchedEffect(c) { c.prepare() }
    DisposableEffect(c) { onDispose { c.stop() } }
    @Suppress("UNUSED_VARIABLE") val tick = c.rev // read so answers/next recompose
    val s = c.session

    Column(modifier.fillMaxSize().background(EarColors.Background).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Ear training", color = EarColors.Text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        SourceBadge(c.choice)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QuizMode.entries.forEach { m -> Chip(m.label, s.settings.mode == m) { if (s.settings.mode != m) c.settings(s.settings.copy(mode = m)) } }
        }
        if (s.settings.mode == QuizMode.INTERVALS) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Direction.entries.forEach { d -> val on = d in s.settings.directions
                Chip("${d.arrow} ${d.label}", on) { val nd = if (on) s.settings.directions - d else s.settings.directions + d
                    if (nd.isNotEmpty()) c.settings(s.settings.copy(directions = nd)) } }
        }

        ScoreRow(s.score)

        val q = s.question
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(EarColors.Raised).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(when (q) { is Question.IntervalQ -> "What interval is this?  (${q.direction.label.lowercase()})"; is Question.ChordQ -> "What chord quality is this?" },
                color = EarColors.Text, fontSize = 16.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { c.play() }, enabled = c.choice != null, colors = ButtonDefaults.buttonColors(containerColor = EarColors.Accent, contentColor = EarColors.Background)) { Text("Replay") }
                OutlinedButton(onClick = { c.next() }, enabled = c.choice != null, border = BorderStroke(1.dp, EarColors.Accent), colors = ButtonDefaults.outlinedButtonColors(contentColor = EarColors.Accent)) {
                    Text(if (s.answered) "Next question" else "Skip") }
                if (c.choice == null || c.rendering) Text("Preparing sounds…", color = EarColors.TextDim, fontSize = 13.sp)
            }
        }

        AnswerGrid(s, onPick = { c.answer(it) })

        s.feedback?.let { f ->
            Text(f.message, color = if (f.correct) EarColors.Accent else EarColors.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.semantics { contentDescription = f.message })
        }
    }
}

@Composable
private fun SourceBadge(choice: SourceChoice?) {
    val text = choice?.source?.label ?: "Looking for single notes in your stem library…"
    val fallback = choice?.source?.isFallback == true
    Text(text, color = if (fallback) EarColors.Background else EarColors.Accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(50)).then(if (fallback) Modifier.background(EarColors.Accent) else Modifier.border(1.dp, EarColors.Accent, RoundedCornerShape(50)))
            .padding(horizontal = 12.dp, vertical = 6.dp).semantics { contentDescription = "Audio source: $text" })
}

@Composable
private fun ScoreRow(score: Score) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Stat("Score", "${score.correct}/${score.answered}")
        Stat("Accuracy", "${score.accuracyPercent}%")
        Stat("Streak", "${score.streak}")
        Stat("Best", "${score.bestStreak}")
    }
}

@Composable
private fun Stat(label: String, value: String) = Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Text(label.uppercase(), color = EarColors.TextDim, fontSize = 10.sp, letterSpacing = 0.8.sp)
    Text(value, color = EarColors.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AnswerGrid(s: EarSession, onPick: (Any) -> Unit) {
    val q = s.question; val f = s.feedback
    val options: List<Any> = s.settings.answers
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { o ->
            val label = EarSession.labelOf(o)
            val isAnswer = f != null && Judge.isCorrect(q, o)
            val isWrongPick = f != null && !f.correct && f.picked == o
            val bg = when { isAnswer -> EarColors.Accent; isWrongPick -> EarColors.Wrong; else -> EarColors.Raised }
            val fg = if (isAnswer) EarColors.Background else EarColors.Text
            Box(Modifier.clip(RoundedCornerShape(10.dp)).background(bg).border(1.dp, if (isAnswer) EarColors.Accent else EarColors.Line, RoundedCornerShape(10.dp))
                .clickable(enabled = f == null) { onPick(o) }.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(label, color = fg, fontSize = 14.sp, fontWeight = if (isAnswer) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(label, color = if (selected) EarColors.Background else EarColors.Text, fontSize = 13.sp,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (selected) EarColors.Accent else EarColors.Raised)
            .border(1.dp, if (selected) EarColors.Accent else EarColors.Line, RoundedCornerShape(50)).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp))
}
