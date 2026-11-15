package com.studiospark.app

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.studiospark.app.importer.ModelGate
import com.studiospark.app.ui.Screen
import com.studiospark.app.ui.StudioVM
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 2.2.0 launch guard: a cold start, and even opening the Import tab, loads no model, ORT session or YAMNet. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ImportLaunchTests {
    val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val chain: org.junit.rules.RuleChain = org.junit.rules.RuleChain.outerRule(FreshInstallRule()).around(compose)
    private fun vm() = ViewModelProvider(compose.activity)[StudioVM::class.java]

    @Test fun coldStartLoadsNoModelAndCreatesNoImporter() {
        ModelGate.resetForTest()
        assertTrue(pumpUntil { vm().ready })
        pumpFor(1500)
        val vm = vm()
        assertEquals(Screen.STUDIO, vm.screen)
        assertTrue("blank studio", vm.project.tracks.isEmpty())
        assertFalse("import controller must not exist at launch", vm.importerCreated)
        assertFalse("songwriter must not exist at launch", vm.songwriterCreated)
        assertEquals("no ORT/YAMNet load at launch (last: ${ModelGate.lastLoad})", 0, ModelGate.loads)
    }

    @Test fun openingTheImportTabStillLoadsNoModel() {
        ModelGate.resetForTest()
        assertTrue(pumpUntil { vm().ready })
        compose.onNodeWithContentDescription("Import tab").performScrollTo().performClick(); compose.waitForIdle()
        compose.onNodeWithText("IMPORT · SPLIT TO TRACKS", substring = true).assertExists()
        pumpFor(1000)
        assertTrue(vm().importerCreated)
        assertEquals("the tab exists and is open, but nothing was imported, so nothing loads", 0, ModelGate.loads)
        assertEquals("the project is untouched by opening Import", 0, vm().project.tracks.size)
    }

    @Test fun openingSongwriterAndEarTrainingLoadsNoModelAndLeavesStudioAlone() {
        ModelGate.resetForTest()
        assertTrue(pumpUntil { vm().ready })
        compose.onNodeWithContentDescription("Songwriter tab").performScrollTo().performClick(); compose.waitForIdle(); pumpFor(500)
        assertTrue(vm().songwriterCreated)
        compose.onNodeWithContentDescription("Ear Training tab").performScrollTo().performClick(); compose.waitForIdle(); pumpFor(500)
        assertEquals(0, ModelGate.loads); assertEquals(0, vm().project.tracks.size)
    }
}
