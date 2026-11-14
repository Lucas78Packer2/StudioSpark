package com.studiospark.app

import androidx.compose.ui.geometry.Offset
import com.studiospark.app.core.*
import com.studiospark.app.dsp.*
import com.studiospark.app.ui.GuitarRender
import com.studiospark.app.ui.KitRender
import com.studiospark.app.ui.KitView
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.math.*

/** Drum Kit, Guitar and Bass builder tabs: catalogs, starters vs blank, honest sound mapping, persistence, first-class use and rig hookup. */
class BuilderTests {
    private val assets = File("src/main/assets").let { if (it.exists()) it else File("app/src/main/assets") }
    private fun drumFile(key: String) = (1..5).any { File(assets, "original_drums/$key-$it.wav").exists() } || File(assets, "original_drums/$key.wav").exists()

    // ------------------------------------------------------------------ drums
    @Test fun blankKitIsEmptyAndStartersAreComplete() {
        assertTrue(DrumCatalog.blank.pieces.isEmpty())
        assertTrue(DrumCatalog.starters.size >= 9)
        for (s in DrumCatalog.starters) { val shapes = s.pieces.map { DrumCatalog.shapeOf(it) }.toSet()
            assertTrue("${s.name} kick", Shape.KICK in shapes); assertTrue("${s.name} snare", Shape.SNARE in shapes); assertTrue("${s.name} hats", Shape.HIHAT in shapes)
            assertTrue("${s.name} cymbal", shapes.any { it == Shape.RIDE || it == Shape.CRASH }); assertTrue("${s.name} has hardware", s.pieces.any { !it.sounding }) }
    }

    @Test fun addingCymbalsDrumsPedalsAndHardwareToABlankKit() {
        var k = DrumCatalog.blank.copy(id = "ck_t", custom = true)
        val hat = DrumCatalog.inCategory(DrumCategory.CYMBALS).first { it.shape == Shape.HIHAT }
        k = DrumCatalog.addTo(k, hat)
        assertTrue("hi-hat gets linked edge + foot zones", k.pieces.count { it.link.isNotEmpty() } >= 2)
        val crash = DrumCatalog.inCategory(DrumCategory.CYMBALS).first { it.shape == Shape.CRASH }; k = DrumCatalog.addTo(k, crash)
        val snare = DrumCatalog.inCategory(DrumCategory.DRUMS).first { it.shape == Shape.SNARE }; k = DrumCatalog.addTo(k, snare)
        val tom = DrumCatalog.inCategory(DrumCategory.DRUMS).first { it.shape == Shape.TOM }; k = DrumCatalog.addTo(k, tom)
        val throne = DrumCatalog.inCategory(DrumCategory.HARDWARE).first(); k = DrumCatalog.addTo(k, throne)
        assertFalse("hardware is silent", k.pieces.last { it.catalogId == throne.id }.sounding)
        assertEquals("unique MIDI notes for sounding zones", k.pieces.filter { it.sounding }.size, k.pieces.filter { it.sounding }.map { it.midi }.toSet().size)
        // remove drops linked zones; move carries them
        val hatHost = k.pieces.first { it.catalogId == hat.id && it.role.isEmpty() }
        val moved = DrumCatalog.move(k, hatHost.id, 0.2f, 0.6f)
        moved.pieces.filter { it.link == hatHost.id }.forEach { assertEquals(0.2f, it.x, 0.05f) }
        val removed = DrumCatalog.remove(k, hatHost.id)
        assertTrue(removed.pieces.none { it.id == hatHost.id || it.link == hatHost.id })
    }

    @Test fun doublePedalGivesTwoPlayableKickHits() {
        val dbl = DrumCatalog.inCategory(DrumCategory.PEDALS).first { it.shape == Shape.PEDAL_DOUBLE }
        val k = DrumCatalog.addTo(DrumCatalog.blank.copy(id = "ck_d", custom = true), dbl)
        val kicks = k.pieces.filter { it.sounding && it.sample.startsWith("kick") }
        assertTrue("auto-added bass drum + left beater", kicks.size >= 2)
        assertEquals(2, kicks.map { it.midi }.toSet().size)
        // both halves of the drawn double pedal resolve to different kick zones
        val placed = KitRender.layout(k, KitView.OVERHEAD, 1000f, 700f)
        val pedal = placed.first { it.shape == Shape.PEDAL_DOUBLE }
        val left = KitRender.hit(k, placed, Offset(pedal.c.x - pedal.rx * 0.5f, pedal.c.y), KitView.OVERHEAD)
        val right = KitRender.hit(k, placed, Offset(pedal.c.x + pedal.rx * 0.5f, pedal.c.y), KitView.OVERHEAD)
        assertNotNull(left); assertNotNull(right); assertNotEquals(left!!.first.midi, right!!.first.midi)
        // single pedal triggers the kit's bass drum
        val single = DrumCatalog.inCategory(DrumCategory.PEDALS).first { it.shape == Shape.PEDAL_SINGLE }
        assertEquals(SoundStatus.TRIGGER, single.status)
    }

    @Test fun customKitSavesAndLoadsWithCatalogFieldsAndPan() {
        var k = DrumCatalog.starter("studio_maple")!!.copy(id = "ck_s", name = "Mine", custom = true, shellFinish = "black_sparkle")
        k = DrumCatalog.addTo(k, DrumCatalog.inCategory(DrumCategory.CYMBALS).first { it.shape == Shape.CHINA })
        k = k.copy(pieces = k.pieces.mapIndexed { i, p -> if (i == 0) p.copy(pan = -0.6f) else p })
        val p = Project(); p.customKits.add(k)
        val back = ProjectJson.read(ProjectJson.write(p)).customKits.single()
        assertEquals(k.pieces.map { it.catalogId to it.role }, back.pieces.map { it.catalogId to it.role })
        assertEquals(k.pieces.map { it.link }, back.pieces.map { it.link }); assertEquals("black_sparkle", back.shellFinish); assertEquals(-0.6f, back.pieces[0].pan, 1e-4f)
        assertEquals("studio_maple", back.starterId)
    }

    @Test fun drumCatalogCoversEveryCategory() {
        val counts = DrumCategory.values().associateWith { if (it == DrumCategory.STARTERS) DrumCatalog.starters.size + 1 else DrumCatalog.inCategory(it).size }
        println("DRUM CATALOG " + counts.entries.joinToString { "${it.key.label}=${it.value}" } + " total=${DrumCatalog.all.size}")
        counts.forEach { (c, n) -> assertTrue("$c empty", n > 0) }
        val cym = DrumCatalog.inCategory(DrumCategory.CYMBALS).map { it.shape }.toSet()
        for (s in listOf(Shape.HIHAT, Shape.CRASH, Shape.RIDE, Shape.CHINA, Shape.SPLASH, Shape.STACK, Shape.BELL)) assertTrue("cymbal type $s", s in cym)
        assertTrue(DrumCatalog.inCategory(DrumCategory.CYMBALS).filter { it.shape == Shape.CRASH }.map { it.sizeIn }.toSet().size >= 3)
        assertTrue(DrumCatalog.inCategory(DrumCategory.PEDALS).any { it.shape == Shape.PEDAL_SINGLE } && DrumCatalog.inCategory(DrumCategory.PEDALS).any { it.shape == Shape.PEDAL_DOUBLE })
    }

    @Test fun everySoundingDrumEntryResolvesToARealSampleAndGapsAreSilent() {
        val sounding = DrumCatalog.all.filter { it.sounding }
        val missing = sounding.filter { !drumFile(it.sample) }
        assertTrue("missing samples: ${missing.map { it.id + "→" + it.sample }}", missing.isEmpty())
        val starterMissing = DrumCatalog.starters.flatMap { s -> s.pieces.filter { it.sounding && !drumFile(it.sample) }.map { s.id + ":" + it.sample } }
        assertTrue("starter samples missing: $starterMissing", starterMissing.isEmpty())
        val gaps = DrumCatalog.gaps(); assertTrue(gaps.isNotEmpty()); gaps.forEach { assertFalse("${it.id} gap must be silent", it.sounding) }
        val distinct = sounding.map { it.sample }.toSet().size
        println("DRUM SOUND own=${sounding.count { it.status == SoundStatus.OWN }} retuned=${sounding.count { it.status == SoundStatus.RETUNED }} distinctSamples=$distinct trigger=${DrumCatalog.all.count { it.status == SoundStatus.TRIGGER }} silentHardware=${DrumCatalog.all.count { it.status == SoundStatus.SILENT }} gaps=${gaps.map { it.name }}")
    }

    // ------------------------------------------------------------------ guitars & bass
    @Test fun guitarAndBassStartersVsScratch() {
        for (bass in listOf(false, true)) {
            val blank = StringCatalog.blank(bass); assertEquals("none", blank.pickups); assertEquals("guard_none", blank.guard); assertEquals(bass, blank.bass)
            val st = StringCatalog.starters(bass); assertTrue(st.size >= if (bass) 6 else 10)
            st.forEach { s -> assertEquals(bass, s.bass); assertNotNull(StringCatalog.part(s.body)); assertNotNull(StringCatalog.part(s.pickups)); assertNotNull(StringCatalog.part(s.finish)); assertNotNull("${s.id} tuning", Tunings.find(s.tuning))
                assertEquals(Tunings.find(s.tuning)!!.midi.size, s.stringCount) }
        }
    }

    @Test fun catalogsHaveBodiesFinishesPickupsHardwareForBoth() {
        for (bass in listOf(false, true)) for (c in PartCategory.values().filter { it != PartCategory.STARTERS }) assertTrue("$c for bass=$bass", StringCatalog.forInstrument(c, bass).isNotEmpty())
        val g = PartCategory.values().associateWith { StringCatalog.forInstrument(it, false).size }; val b = PartCategory.values().associateWith { StringCatalog.forInstrument(it, true).size }
        println("GUITAR CATALOG " + g.entries.joinToString { "${it.key.label}=${if (it.key == PartCategory.STARTERS) StringCatalog.guitarStarters.size + 1 else it.value}" })
        println("BASS CATALOG " + b.entries.joinToString { "${it.key.label}=${if (it.key == PartCategory.STARTERS) StringCatalog.bassStarters.size + 1 else it.value}" })
        assertTrue(StringCatalog.finishes.map { it.style }.toSet().containsAll(listOf("burst", "solid", "metal", "wood", "quilt", "flame", "relic")))
        StringCatalog.bodies.forEach { assertTrue("${it.id} outline", StringCatalog.outline(it.id).size >= 16) }
    }

    @Test fun tuningsIncludeStandardDropDDropCAndOpenForGuitarAndBass() {
        val gn = Tunings.guitar.map { it.name }; val bn = Tunings.bass.map { it.name }
        for (w in listOf("Standard", "Drop D", "Drop C", "Open")) assertTrue("guitar $w", gn.any { it.contains(w, true) })
        for (w in listOf("standard", "drop D", "drop C", "open")) assertTrue("bass $w", bn.any { it.contains(w, true) })
        assertEquals(listOf(38, 45, 50, 55, 59, 64), Tunings.find("Drop D (D A D G B E)")!!.midi)
        assertEquals(24, Tunings.bass.first { it.name.contains("drop C") }.midi[0])
        val b = StringCatalog.apply(StringCatalog.starters(true)[0], StringCatalog.tunings.first { it.name.contains("Bass drop D") })
        assertEquals(26, Tunings.find(b.tuning)!!.midi[0])
    }

    @Test fun soundMapsToOwnedSamplesAndFlagsGaps() {
        val gtr = File(assets, "strings").listFiles()!!.map { it.name }
        assertTrue(gtr.count { it.startsWith("guitar-") } >= 30); assertTrue(gtr.count { it.startsWith("bass-") } >= 20)
        for (s in StringCatalog.guitarStarters + StringCatalog.bassStarters) { val snd = StringCatalog.sound(s)
            assertTrue("${s.id} voice ${snd.voice}", snd.voice in setOf("guitar", "bass", "nylon_like")); assertTrue(snd.brightness in 0.05f..1f) }
        // the low notes of every starter tuning are within reach of the owned sample roots (retune ≤ 7 semitones)
        val roots = { g: String -> gtr.filter { it.startsWith("$g-") }.map { it.split("-")[1].toInt() }.toSet() }
        for (s in StringCatalog.guitarStarters + StringCatalog.bassStarters) { val v = StringCatalog.sound(s).voice; if (v == "nylon_like") continue
            val low = Tunings.find(s.tuning)!!.midi.min(); assertTrue("${s.id} low $low", roots(v).minOf { abs(it - low) } <= 7) }
        val gaps = StringCatalog.gaps().map { it.id }
        assertTrue(gaps.containsAll(listOf("jumbo12", "fretless", "acoustic_bass", "piezo", "bass_piezo")))
        assertEquals(VoiceStatus.GAP, StringCatalog.sound(StringCatalog.starter("b_fretless")!!).status)
        println("STRING SOUND gaps=" + StringCatalog.gaps().joinToString { it.name })
    }

    @Test fun pickupsSelectorAndToneChangeTheVoiceHonestly() {
        val s = StringCatalog.starter("g_glass")!!
        val single = StringCatalog.sound(s.copy(pickups = "ss")).brightness; val hum = StringCatalog.sound(s.copy(pickups = "hh")).brightness
        assertTrue("single-coils brighter than humbuckers", single > hum)
        val bridge = StringCatalog.sound(s.copy(pickupPos = 0)).brightness; val neck = StringCatalog.sound(s.copy(pickupPos = 4)).brightness
        assertTrue("bridge brighter than neck", bridge > neck)
        assertTrue(StringCatalog.sound(s.copy(tone = 1f)).brightness > StringCatalog.sound(s.copy(tone = 0.1f)).brightness)
        assertEquals(5, StringCatalog.positions(s).size)
    }

    @Test fun savedBuildIsFirstClassAndRoundTrips() {
        val p = Project()
        val draft = StringCatalog.starter("g_shred")!!.copy(id = "sb_x", name = "Draft")
        val saved = StringCatalog.starter("b_five")!!.copy(id = "sb_y", name = "My Five", saved = true)
        p.stringBuilds.add(draft); p.stringBuilds.add(saved)
        val all = p.allInstruments()
        val inst = all.single { it.id == StringCatalog.instrumentId(saved) }
        assertEquals(Family.BASS, inst.family); assertEquals(Surface.STRINGS, inst.surface); assertEquals(saved.tuning, inst.tuning); assertEquals("build:sb_y", inst.art); assertTrue(inst.custom)
        assertTrue("drafts stay off the pickers", all.none { it.id == StringCatalog.instrumentId(draft) })
        assertEquals("draft still resolves for its builder track", "Draft", p.instrument(StringCatalog.instrumentId(draft)).name)
        // a track using it, through save/load
        p.tracks.add(Track(name = "Bass", family = Family.BASS, instrumentId = inst.id, color = TrackColors.all[0], tuning = inst.tuning))
        val back = ProjectJson.read(ProjectJson.write(p))
        assertEquals(p.stringBuilds, back.stringBuilds)
        assertEquals("My Five", back.instrument(back.tracks.last().instrumentId).name)
        assertEquals(InstrumentLibrary.categories(back.allInstruments()).contains("My basses"), true)
    }

    @Test fun customGuitarAndBassPlugIntoTheAmpRig() {
        val presets = RigPresets.factory.map { it.name }.toSet()
        for (s in StringCatalog.guitarStarters + StringCatalog.bassStarters) if (s.rigPreset.isNotEmpty()) assertTrue("${s.id} preset ${s.rigPreset}", s.rigPreset in presets)
        val s = StringCatalog.starter("g_carved")!!
        val rig = RigPresets.factory.first { it.name == s.rigPreset }.build()
        val kinds = rig.slots.mapNotNull { it.gear?.kind }
        assertTrue("stomp → amp → cab chain", kinds.contains(GearKind.AMP) && kinds.contains(GearKind.CAB))
        val sr = 48000f; val proc = RigProcessor(sr, rig)
        val x = FloatArray(4800) { (0.3 * sin(2 * PI * 110.0 * it / sr)).toFloat() }; val y = x.copyOf(); proc.process(y, y.size)
        assertTrue("rig changes the guitar signal", x.indices.sumOf { abs(x[it] - y[it]).toDouble() } / x.size > 1e-3)
        for (bp in StringCatalog.bassStarters.mapNotNull { b -> RigPresets.factory.firstOrNull { it.name == b.rigPreset } }) assertTrue(bp.family == Family.BASS)
    }

    @Test fun drawnNeckIsPlayable() {
        val b = StringCatalog.starter("g_glass")!!
        val g = GuitarRender.geo(b, Offset.Zero, 1200f, 400f)
        val x5 = (g.fretX(5) + g.fretX(4)) / 2
        val hit = GuitarRender.hit(g, Offset(x5, g.stringY(0, x5)), b)
        assertEquals(0 to 5, hit)
        val open = GuitarRender.hit(g, Offset(g.bridgeX + g.bodyLen * 0.15f, g.stringY(5, g.bridgeX + g.bodyLen * 0.15f)), b)
        assertEquals(5 to 0, open)
    }

    @Test fun noBrandModelOrSignatureNames() {
        val banned = listOf("Fender", "Gibson", "Strat", "Telecaster", "Les Paul", "Precision", "Jazz Bass", "Ibanez", "PRS", "Rickenbacker", "Gretsch", "Sabian", "Zildjian", "Tama", "Paiste", "AmpliTube", "Pro Tools",
            "Marshall", "Mesa", "Ampeg", "Celestion", "Bigsby", "Floyd", "Tune-o-matic", "Signature", "Orange", "Ludwig", "Gretsch", "Yamaha", "Roland", "Boss ", "335", "White 2A", "Vintage-30")
        val names = DrumCatalog.all.map { it.name } + DrumCatalog.starters.map { it.name } + StringCatalog.all.map { it.name } + (StringCatalog.guitarStarters + StringCatalog.bassStarters).map { it.name } +
            InstrumentLibrary.stock.map { it.name } + GearCatalog.all.map { it.name } + RigPresets.factory.map { it.name } + Finishes.shells.map { it.name } + Finishes.cymbals.map { it.name }
        val bad = names.filter { n -> banned.any { n.contains(it, ignoreCase = false) } || Regex("\\bDW\\b").containsMatchIn(n) || Regex("\\bPearl\\b").containsMatchIn(n) && !n.contains("Dots") && !n.contains("Blocks") }
        assertTrue("trademark-like names: $bad", bad.isEmpty())
    }
}
