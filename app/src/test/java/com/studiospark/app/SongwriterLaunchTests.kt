package com.studiospark.app

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.studiospark.app.ui.StudioVM
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 2.2.0 blank launch through BootGuard + shared engine, and the Songwriter tab's blank fretboard. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SongwriterLaunchTests {
    val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val chain: org.junit.rules.RuleChain = org.junit.rules.RuleChain.outerRule(FreshInstallRule()).around(compose)
    private fun vm() = ViewModelProvider(compose.activity)[StudioVM::class.java]

    @Test fun coldStartIsCleanAndCreatesNoSharedEngine() {
        assertTrue(pumpUntil { vm().ready }); pumpFor(500)
        assertTrue("BootGuard.isLaunchClean at startup", vm().launchClean)
        assertFalse("shared engine host is lazy", vm().sessionHostCreated)
        assertFalse(vm().songwriterCreated)
    }

    @Test fun songwriterTabShowsABlankFretboardAndLeavesStudioAlone() {
        assertTrue(pumpUntil { vm().ready })
        compose.onNodeWithContentDescription("Songwriter tab").performScrollTo().performClick(); compose.waitForIdle(); pumpFor(300)
        compose.onNodeWithContentDescription("Fretboard").assertExists()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "blank"))
        assertTrue(vm().songwriterCreated); assertFalse(vm().sessionHostCreated); assertEquals(0, vm().project.tracks.size)
    }
}
