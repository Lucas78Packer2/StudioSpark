package com.studiospark.app

import android.content.ContextWrapper
import com.studiospark.app.core.*
import com.studiospark.app.dsp.Family
import org.junit.Assert.*
import org.junit.Test

class BootGuardTests {
    @Test fun enforceBlankLaunchReturnsAnEmptySession() {
        val s = BootGuard.enforceBlankLaunch(ContextWrapper(null))
        assertEquals(StudioSession.empty(), s); assertTrue(BootGuard.isLaunchClean(s))
    }

    @Test fun anyTrackGearOrModelMakesTheLaunchDirty() {
        val t = Track(name = "x", family = Family.GUITAR, instrumentId = "gtr_0", color = 0)
        assertFalse(BootGuard.isLaunchClean(StudioSession(listOf(t), emptySet(), emptySet())))
        assertFalse(BootGuard.isLaunchClean(StudioSession(emptyList(), setOf("amp_x"), emptySet())))
        assertFalse(BootGuard.isLaunchClean(StudioSession(emptyList(), emptySet(), setOf("htdemucs"))))
    }

    @Test fun snapshotOfABlankProjectIsClean() {
        assertTrue(BootGuard.isLaunchClean(StudioSession.of(Sessions.blank(), emptySet(), emptySet(), 0)))
        assertFalse(BootGuard.isLaunchClean(StudioSession.of(Sessions.blank(), setOf("t1"), emptySet(), 0)))
        assertFalse(BootGuard.isLaunchClean(StudioSession.of(Sessions.blank(), emptySet(), emptySet(), 1, "yamnet")))
    }
}
