package com.studiospark.app.dsp

import java.util.concurrent.atomic.AtomicLong
import kotlin.math.*

enum class Family(val label: String) { DRUMS("Drums"), GUITAR("Guitar"), BASS("Bass"), KEYS("Keys"), SYNTH("Synth"), VOCAL("Vocal"), ORCHESTRAL("Orchestral"), AUDIO("Audio"), CUSTOM("Custom") }

class RigSlot(val uid: Long, val gearId: String, val values: FloatArray, var enabled: Boolean = true) {
    val gear get() = GearCatalog.get(gearId)
    fun copy() = RigSlot(uid, gearId, values.copyOf(), enabled)
    companion object { private val ids = AtomicLong(1); fun next() = ids.incrementAndGet()
        fun of(gearId: String) = RigSlot(next(), gearId, GearCatalog.get(gearId).defaults()) }
}

/** An ordered, per-track signal chain. Order in [slots] is the audio order (input → … → output). */
class Rig(val slots: MutableList<RigSlot> = mutableListOf(), var inputGainDb: Float = 0f, var outputGainDb: Float = 0f, var power: Boolean = true, var presetName: String = "Init") {
    fun copy() = Rig(slots.map { it.copy() }.toMutableList(), inputGainDb, outputGainDb, power, presetName)
    fun add(gearId: String, index: Int = slots.size): RigSlot { val s = RigSlot.of(gearId); slots.add(index.coerceIn(0, slots.size), s); return s }
    fun remove(uid: Long) { slots.removeAll { it.uid == uid } }
    fun move(uid: Long, to: Int) { val i = slots.indexOfFirst { it.uid == uid }; if (i < 0) return; val s = slots.removeAt(i); slots.add(to.coerceIn(0, slots.size), s) }
    fun order(): List<String> = slots.map { it.gearId }
    fun structureKey(): String = slots.joinToString(",") { "${it.uid}:${it.gearId}" }
}

/** Audio-thread side of a rig. Rebuilt (off the audio thread) when the chain structure changes; knob moves are read live from the shared value arrays. */
class RigProcessor(val sr: Float, val rig: Rig) {
    private val key = rig.structureKey()
    private val stages: List<Pair<RigSlot, Processor>> = rig.slots.map { it to it.gear.factory(sr, it.values) }
    private val tmp = FloatArray(1)
    var inPeak = 0f; private set
    var outPeak = 0f; private set
    fun matches(r: Rig) = r === rig && r.structureKey() == key
    fun process(buf: FloatArray, n: Int) {
        if (!rig.power) { meter(buf, n, true); outPeak = inPeak; return }
        val ig = Shapers.dbToLin(rig.inputGainDb); for (i in 0 until n) buf[i] *= ig
        meter(buf, n, true)
        for ((slot, proc) in stages) if (slot.enabled) proc.block(buf, n)
        val og = Shapers.dbToLin(rig.outputGainDb)
        var pk = 0f
        for (i in 0 until n) { var x = buf[i] * og; if (!x.isFinite()) x = 0f; x = x.coerceIn(-4f, 4f); buf[i] = x; pk = max(pk, abs(x)) }
        outPeak = pk
    }
    private fun meter(buf: FloatArray, n: Int, input: Boolean) { var pk = 0f; for (i in 0 until n) pk = max(pk, abs(buf[i])); if (input) inPeak = pk }
    fun stageOf(uid: Long): Processor? = stages.firstOrNull { it.first.uid == uid }?.second
}

data class RigPreset(val name: String, val family: Family?, val build: () -> Rig)

object RigPresets {
    private fun rig(name: String, vararg ids: String, tweak: (Rig) -> Unit = {}) = Rig(presetName = name).also { r -> ids.forEach { r.add(it) }; tweak(r) }
    private fun Rig.set(gear: String, key: String, v: Float) { val s = slots.first { it.gearId == gear }; val i = s.gear.params.indexOfFirst { it.key == key }; s.values[i] = v }

    val factory: List<RigPreset> = listOf(
        RigPreset("Citrus Crunch", Family.GUITAR) { rig("Citrus Crunch", "quiet_gate", "mid_push", "citrus_valve", "cab_412", "spring_tank") },
        RigPreset("Modern Metal Rhythm", Family.GUITAR) { rig("Modern Metal Rhythm", "quiet_gate", "mid_push", "dual_furnace", "cab_412", "rack_peq") { it.set("mid_push", "drive", 0.1f); it.set("mid_push", "level", 0.8f); it.set("quiet_gate", "thr", -45f) } },
        RigPreset("Glass Clean + Chorus", Family.GUITAR) { rig("Glass Clean + Chorus", "squeeze_comp", "lagoon_chorus", "silverline", "cab_212", "digi_delay", "hall_verb") },
        RigPreset("Tweed Blues", Family.GUITAR) { rig("Tweed Blues", "tread_boost", "honey_tweed", "cab_112", "tape_echo") },
        RigPreset("Fuzz Wah Lead", Family.GUITAR) { rig("Fuzz Wah Lead", "throat_wah", "velvet_fuzz", "crown_plex", "cab_412", "tape_echo") { it.set("throat_wah", "auto", 0.5f) } },
        RigPreset("Tube Bass DI+Amp", Family.BASS) { rig("Tube Bass DI+Amp", "squeeze_comp", "fathom_bass", "cab_810", "rack_peq") },
        RigPreset("Growl Bass", Family.BASS) { rig("Growl Bass", "squeeze_comp", "sub_octave", "deepwell_b8", "cab_115") { it.set("sub_octave", "sub", 0.35f) } },
        RigPreset("Drum Bus", Family.DRUMS) { rig("Drum Bus", "rack_gate", "snap_drum", "atlas_pre", "cab_fullrange", "rack_bus", "rack_limit") { it.set("cab_fullrange", "micDist", 0.0f); it.set("rack_gate", "thr", -60f) } },
        RigPreset("Room Drums Crushed", Family.DRUMS) { rig("Room Drums Crushed", "atlas_pre", "rack_bus", "rack_tape", "rack_plate") { it.set("rack_bus", "ratio", 8f); it.set("rack_bus", "thr", -28f) } },
        RigPreset("Rotary Keys", Family.KEYS) { rig("Rotary Keys", "spin_cab", "ivory_keys", "cab_fullrange", "rack_plate") },
        RigPreset("Tine Keys Tremolo", Family.KEYS) { rig("Tine Keys Tremolo", "pulse_trem", "ivory_keys", "cab_fullrange", "spring_tank") },
        RigPreset("Synth Wide Delay", Family.SYNTH) { rig("Synth Wide Delay", "lagoon_chorus", "glass_jazz", "cab_fullrange", "rack_delay", "rack_plate") },
        RigPreset("Vocal Chain", Family.VOCAL) { rig("Vocal Chain", "rack_peq", "atlas_pre", "rack_opto", "rack_plate", "rack_delay") { it.set("rack_peq", "hpf", 90f) } },
        RigPreset("Orchestral Hall", Family.ORCHESTRAL) { rig("Orchestral Hall", "rack_peq", "rack_opto", "hall_verb") },
        RigPreset("Clean Audio", Family.AUDIO) { rig("Clean Audio", "rack_peq", "rack_opto", "rack_limit") },
    )

    fun defaultFor(f: Family): Rig = (factory.firstOrNull { it.family == f } ?: factory.first { it.family == Family.AUDIO }).build()
}

/** The AmpliTube-style rig is offered for every instrument family and every custom instrument. There is deliberately no allow-list. */
object RigAvailability { fun available(@Suppress("UNUSED_PARAMETER") family: Family, @Suppress("UNUSED_PARAMETER") customName: String? = null) = true }
