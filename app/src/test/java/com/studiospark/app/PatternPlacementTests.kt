package com.studiospark.app

import androidx.lifecycle.ViewModelProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.studiospark.app.core.*
import com.studiospark.app.dsp.Family
import com.studiospark.app.ui.StudioVM
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 2.1 milestone 6: patterns load on demand and are placed as MIDI notes at the project tempo. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PatternPlacementTests {
    val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val chain: org.junit.rules.RuleChain = org.junit.rules.RuleChain.outerRule(FreshInstallRule()).around(compose)
    private fun vm() = ViewModelProvider(compose.activity)[StudioVM::class.java].also { v -> compose.waitUntil(20_000) { v.ready } }

    @Test fun libraryIsNotLoadedAtLaunchAndPlacementWritesNotes() {
        val v = vm(); compose.waitForIdle()
        assertFalse("blank studio does not load pattern packs", v.patternsLoaded)
        val before = v.project.tracks.size
        compose.runOnUiThread { v.setTempo(100f); v.locate(v.project.samplesPerBar * 2 + 123); v.placePattern(PatternPick(genre = 0, part = PatternPart.DRUMS, index = 1, vary = false)) }
        assertTrue(v.patternsLoaded)
        val drums = v.project.tracks.first { it.family == Family.DRUMS }
        assertTrue(v.project.tracks.size >= before)
        val bar = v.project.samplesPerBar; val step16 = v.project.samplesPerBeat / 4
        assertTrue(drums.notes.isNotEmpty()); assertTrue("starts at the playhead bar", drums.notes.all { it.start >= bar * 2 && it.start < bar * 6 })
        assertTrue("on the 100 BPM grid", drums.notes.all { (it.start - bar * 2) % step16 == 0L })
        val kit = v.kitPieces(drums); assertTrue(drums.notes.all { it.pitch in kit })
        // placing again over the same span replaces instead of stacking
        val n = drums.notes.size; compose.runOnUiThread { v.placePattern(PatternPick(genre = 0, part = PatternPart.DRUMS, index = 1, vary = false)) }
        assertEquals(n, v.project.tracks.first { it.id == drums.id }.notes.size)
        compose.runOnUiThread { v.undo() }; assertEquals(n, v.project.tracks.first { it.id == drums.id }.notes.size)
    }

    @Test fun backingSetFillsDrumsBassAndGuitarAndPromptSetsTempo() {
        val v = vm()
        compose.runOnUiThread { val pk = v.applyPrompt("metal band 150")!!; assertEquals(PatternPart.BACKING, pk.part); v.locate(0); v.placePattern(pk) }
        assertEquals(150f, v.project.tempo)
        listOf(Family.DRUMS, Family.BASS, Family.GUITAR).forEach { f -> assertTrue("$f", v.project.tracks.any { it.family == f && it.notes.isNotEmpty() }) }
    }

    @Test fun pickerOpensFromTheEditToolbar() {
        vm(); compose.waitForIdle()
        compose.onNodeWithText("Patterns").performScrollTo().performClick(); compose.waitForIdle()
        compose.onNodeWithText("Genre patterns").assertExists()
        compose.onNodeWithText("Metal").performClick(); compose.onNodeWithText("Backing set").performClick()
        compose.onNodeWithText("Place at bar 1").performClick(); compose.waitForIdle()
        compose.onNodeWithText("Shuffle").assertExists()
    }
}
