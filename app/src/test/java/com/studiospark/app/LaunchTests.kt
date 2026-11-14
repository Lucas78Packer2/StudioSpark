package com.studiospark.app

import com.studiospark.app.core.*
import com.studiospark.app.dsp.*
import org.junit.Assert.*
import org.junit.Test

/** 2.0.1: launch opens a basic studio; amp/pedal chains are built per track only when needed. */
class LaunchTests {
    private val sr = 48000f
    private fun project() = Project().apply {
        tracks += Track(name = "Gtr", family = Family.GUITAR, instrumentId = "gtr_0", color = 0)
        tracks += Track(name = "Bass", family = Family.BASS, instrumentId = "bass_0", color = 0)
        tracks += Track(name = "Drums", family = Family.DRUMS, instrumentId = "kit_studio_maple", color = 0, kitId = "studio_maple")
    }

    @Test fun blankSessionHasNoGearPreloaded() {
        val p = Sessions.blank()
        assertTrue(p.tracks.isEmpty()); assertTrue(p.room.isEmpty()); assertTrue(p.cords.isEmpty())
        // blank session still round-trips through the saved format
        assertTrue(ProjectJson.read(ProjectJson.write(p)).tracks.isEmpty())
    }

    @Test fun nothingIsBuiltUntilATrackIsWanted() {
        val p = project(); val rigs = LazyRigs(sr)
        p.tracks.forEach { t -> val (m, r) = rigs.resolve(t.id, t.rig, t.family == Family.DRUMS); assertNull(m); assertNull(r) }
        assertEquals(0, rigs.builtCount()); assertEquals(0, rigs.builds)
    }

    @Test fun addingGearBuildsExactlyThatTracksRig() {
        val p = project(); val rigs = LazyRigs(sr); val gtr = p.tracks[0]
        gtr.rig.add("citrus_valve"); assertTrue(rigs.want(gtr.id))
        p.tracks.forEach { t -> rigs.resolve(t.id, t.rig, t.family == Family.DRUMS) }
        assertEquals(setOf(gtr.id), rigs.builtIds()); assertEquals(1, rigs.builds)
        // republishing without changes reuses the processor
        val first = rigs.resolve(gtr.id, gtr.rig, false).first
        assertSame(first, rigs.resolve(gtr.id, gtr.rig, false).first); assertEquals(1, rigs.builds)
        assertFalse(rigs.want(gtr.id))
    }

    @Test fun stereoKitGetsRightChannelOnlyWhenWanted() {
        val p = project(); val rigs = LazyRigs(sr); val kit = p.tracks[2]
        rigs.want(kit.id); val (m, r) = rigs.resolve(kit.id, kit.rig, true)
        assertNotNull(m); assertNotNull(r); assertEquals(2, rigs.builtCount())
    }

    @Test fun replacedRigRebuildsAndDeletedTracksAreForgotten() {
        val p = project(); val rigs = LazyRigs(sr); val gtr = p.tracks[0]
        rigs.want(gtr.id); val a = rigs.resolve(gtr.id, gtr.rig, false).first
        gtr.rig = RigPresets.defaultFor(Family.BASS); val b = rigs.resolve(gtr.id, gtr.rig, false).first
        assertNotSame(a, b)
        rigs.retain(emptySet()); assertEquals(0, rigs.builtCount()); assertFalse(rigs.isWanted(gtr.id))
    }
}
