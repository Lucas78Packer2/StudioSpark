package com.studiospark.app

import androidx.lifecycle.ViewModelProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.studiospark.app.core.*
import com.studiospark.app.ui.StudioVM
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 2.1 milestone 7: jam-room backing band (pattern loop, part toggles via mute, next-bar swaps). */
class JamBandLogicTests {
    private val bar = 96000L; private val len = 4 * bar
    private fun n(at: Long, p: Int) = MidiNote(p, at, 100, 100)
    @Test fun modesMuteTheRightParts() {
        assertFalse(JamBand.Mode.DRUMS.muted(PatternPart.DRUMS)); assertTrue(JamBand.Mode.DRUMS.muted(PatternPart.BASS)); assertTrue(JamBand.Mode.DRUMS.muted(PatternPart.GUITAR))
        assertFalse(JamBand.Mode.DRUMS_BASS.muted(PatternPart.BASS)); assertTrue(JamBand.Mode.DRUMS_BASS.muted(PatternPart.GUITAR))
        JamBand.ROLES.forEach { assertFalse(JamBand.Mode.FULL.muted(it)) }
    }
    @Test fun swapKeepsTheCurrentBarAndChangesFromTheNextBar() {
        val old = (0 until 4).map { n(it * bar, 36) }; val new = (0 until 4).map { n(it * bar + 10, 38) }
        val sw = JamBand.swapAtNextBar(old, new, pos = bar + 500, bar = bar, loopLen = len)
        assertEquals(1, sw.pendingBar)
        assertEquals(listOf(38, 36, 38, 38), sw.now.map { it.pitch }); assertEquals(listOf(bar + 10), sw.pending.map { it.start })
        val done = JamBand.applyPending(sw.now, sw.pendingBar!!, sw.pending, bar); assertEquals(new, done)
        // second loop pass: position past loop end maps back into the pattern
        assertEquals(1, JamBand.swapAtNextBar(old, new, pos = len + bar + 1, bar = bar, loopLen = len).pendingBar)
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class JamBandTests {
    val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val chain: org.junit.rules.RuleChain = org.junit.rules.RuleChain.outerRule(FreshInstallRule()).around(compose)
    private fun vm() = ViewModelProvider(compose.activity)[StudioVM::class.java].also { v -> compose.waitUntil(20_000) { v.ready } }

    @Test fun bandLoadsMutesLoopsAndRestores() {
        val v = vm()
        compose.runOnUiThread { v.edit { loopStart = 7; loopEnd = 9 }; v.jamLoad(PatternPick(genre = 2, part = PatternPart.BACKING, index = 0, vary = false)) }
        val jam = v.project.tracks.filter { it.jamRole != null }
        assertEquals(setOf("DRUMS", "BASS", "GUITAR"), jam.map { it.jamRole }.toSet()); assertTrue(jam.all { it.notes.isNotEmpty() && it.paused })
        assertFalse("loading does not start playback or load anything at launch", v.jamBandOn)
        compose.runOnUiThread { v.chooseJamMode(JamBand.Mode.DRUMS_BASS); v.jamPlay() }
        assertTrue(v.jamBandOn); assertTrue(v.mixer.session.loopPlayback); assertEquals(0L, v.project.loopStart); assertEquals(4 * v.project.samplesPerBar, v.project.loopEnd)
        val byRole = v.project.tracks.filter { it.jamRole != null }.associateBy { it.jamRole }
        assertFalse(byRole["DRUMS"]!!.mute); assertFalse(byRole["BASS"]!!.mute); assertTrue(byRole["GUITAR"]!!.mute); assertTrue(byRole.values.none { it.paused })
        val snap = v.mixer.snap!!.tracks; assertTrue("non-band tracks silent during the jam", snap.filter { s -> v.project.track(s.id)?.jamRole == null }.all { it.paused })
        // swap while rolling: the current bar keeps its notes
        compose.runOnUiThread { v.jamLoad(PatternPick(genre = 2, part = PatternPart.DRUMS, index = 3, vary = false)) }
        compose.runOnUiThread { v.jamStop() }
        assertFalse(v.jamBandOn); assertEquals(7L, v.project.loopStart); assertEquals(9L, v.project.loopEnd)
        assertTrue(v.project.tracks.filter { it.jamRole != null }.all { it.paused })
        // pending bar is flushed on stop: drums now equal the new pattern
        val expect = v.patternNotes(v.patternLibrary()[2], PatternPart.DRUMS, 3, 1 + PatternPart.DRUMS.ordinal, false, v.kitPieces(byRole["DRUMS"]!!))
        assertEquals(expect, v.project.tracks.first { it.jamRole == "DRUMS" }.notes.toList())
    }

    @Test fun jamRoomShowsTheBand() {
        val v = vm(); compose.runOnUiThread { v.screen = com.studiospark.app.ui.Screen.JAM }; compose.waitForIdle()
        compose.onNodeWithText("Backing band", ignoreCase = true).assertExists()
        compose.onNodeWithText("Full band").assertExists(); compose.onNodeWithText("Drums+Bass").performScrollTo().performClick()
        assertEquals(JamBand.Mode.DRUMS_BASS, v.jamMode)
    }
}
