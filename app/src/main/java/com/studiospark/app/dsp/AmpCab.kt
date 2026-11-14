package com.studiospark.app.dsp

import kotlin.math.*

/** Voicing for an original amp model. Values describe the circuit flavour, not any manufacturer's design. */
data class AmpVoicing(
    val inputHp: Float, val stageGain: Float, val stages: Int, val bias: Float,
    val bassF: Float, val midF: Float, val trebleF: Float, val midQ: Float,
    val sag: Float, val brightDb: Float, val solidState: Boolean = false, val bass: Boolean = false,
)

/**
 * Preamp → tone stack → power amp model. Params: gain, bass, mid, treble, presence, master, channel(0 clean / 1 crunch / 2 lead), bright(0/1).
 * Each preamp stage is a coupling high-pass, gain and a biased tanh triode approximation, followed by an inter-stage low-pass.
 */
class AmpModel(sr: Float, p: FloatArray, private val v: AmpVoicing) : Processor(sr, p) {
    private val inHp = Biquad(); private val bright = Biquad()
    private val stageHp = Array(4) { Biquad() }; private val stageLp = Array(4) { Biquad() }
    private val bassB = Biquad(); private val midB = Biquad(); private val trebB = Biquad(); private val presB = Biquad(); private val outLp = Biquad()
    private val sagEnv = EnvelopeFollower(); private var pre = 1f; private var master = 1f; private var stages = 1
    override fun update() {
        val ch = p[6].roundToInt().coerceIn(0, 2)
        stages = (v.stages + ch - 1).coerceIn(1, 4)
        val chMul = when (ch) { 0 -> 0.25f; 1 -> 1f; else -> 3.2f }
        pre = 1f + p[0] * p[0] * v.stageGain * chMul
        inHp.set(Biquad.Kind.HighPass, sr, v.inputHp, 0.6f)
        bright.set(Biquad.Kind.HighShelf, sr, 1800f, 0.7f, if (p[7] > 0.5f) v.brightDb else 0f)
        for (s in 0 until 4) { stageHp[s].set(Biquad.Kind.HighPass, sr, if (v.bass) 30f else 60f + s * 40f, 0.6f); stageLp[s].set(Biquad.Kind.LowPass, sr, 9000f - s * 1200f, 0.6f) }
        bassB.set(Biquad.Kind.LowShelf, sr, v.bassF, 0.7f, (p[1] - 0.5f) * 20f)
        midB.set(Biquad.Kind.Peak, sr, v.midF, v.midQ, (p[2] - 0.5f) * 18f)
        trebB.set(Biquad.Kind.HighShelf, sr, v.trebleF, 0.7f, (p[3] - 0.5f) * 20f)
        presB.set(Biquad.Kind.Peak, sr, 3800f, 0.9f, (p[4] - 0.5f) * 12f)
        outLp.set(Biquad.Kind.LowPass, sr, if (v.bass) 6000f else 11000f, 0.7f)
        master = Shapers.dbToLin((p[5] - 0.7f) * 30f)
        sagEnv.set(sr, 8f, 180f)
    }
    override fun process(buf: FloatArray, n: Int) {
        for (i in 0 until n) {
            var x = bright.process(inHp.process(buf[i]))
            for (s in 0 until stages) {
                x = stageHp[s].process(x) * (if (s == 0) pre else 2.2f)
                x = if (v.solidState) Shapers.hard(Shapers.soft(x), 0.95f) else Shapers.asym(x, v.bias * (1 + s * 0.3f))
                x = stageLp[s].process(x)
            }
            x = trebB.process(midB.process(bassB.process(x)))
            val sag = 1f - v.sag * sagEnv.process(x).coerceAtMost(1f) * 0.5f
            x = Shapers.soft(x * 1.4f * sag) * 0.8f
            buf[i] = outLp.process(presB.process(x)) * master
        }
    }
}

data class CabVoicing(val resonance: Float, val resGain: Float, val lowCut: Float, val highCut: Float, val presenceF: Float, val closedBack: Boolean)

/**
 * Speaker cabinet + movable microphone. Params: micX (0 cone centre .. 1 edge), micDistance (0 grille .. 1 far),
 * micType (0 dynamic, 1 condenser, 2 ribbon), room mix, level dB.
 * Off-axis placement darkens the top end, distance removes proximity bass and adds an early reflection/comb, so moving the mic audibly changes tone.
 */
class CabModel(sr: Float, p: FloatArray, private val v: CabVoicing) : Processor(sr, p) {
    private val lowCut = Biquad(); private val res = Biquad(); private val hiCut = Biquad(); private val hiCut2 = Biquad(); private val presence = Biquad()
    private val axis = Biquad(); private val prox = Biquad(); private val micColor = Biquad(); private val micColor2 = Biquad()
    private val refl = DelayLine((sr * 0.03f).toInt()); private var reflDelay = 1f; private var reflGain = 0f; private var level = 1f
    private val room = Reverb(sr, floatArrayOf(0.35f, 0.5f, 1f, 4f))
    private val wet = FloatArray(4096)
    override fun update() {
        val x = p[0]; val d = p[1]; val type = p[2].roundToInt()
        lowCut.set(Biquad.Kind.HighPass, sr, v.lowCut, 0.7f)
        res.set(Biquad.Kind.Peak, sr, v.resonance, 1.4f, v.resGain * (if (v.closedBack) 1f else 0.6f))
        val cut = v.highCut * (1f - 0.45f * x) * (1f - 0.15f * d)
        hiCut.set(Biquad.Kind.LowPass, sr, cut, 0.7f); hiCut2.set(Biquad.Kind.LowPass, sr, cut * 1.15f, 0.6f)
        presence.set(Biquad.Kind.Peak, sr, v.presenceF * (1f - 0.25f * x), 1.2f, 5f - x * 9f)
        axis.set(Biquad.Kind.HighShelf, sr, 3000f, 0.7f, -x * 9f)
        prox.set(Biquad.Kind.LowShelf, sr, 160f, 0.7f, (1f - d) * (if (type == 0) 5f else 3f) - d * 3f)
        when (type) {
            0 -> { micColor.set(Biquad.Kind.Peak, sr, 5500f, 1.0f, 4f); micColor2.set(Biquad.Kind.HighPass, sr, 70f, 0.7f) }
            1 -> { micColor.set(Biquad.Kind.HighShelf, sr, 9000f, 0.7f, 2.5f); micColor2.set(Biquad.Kind.Peak, sr, 250f, 0.8f, -1.5f) }
            else -> { micColor.set(Biquad.Kind.HighShelf, sr, 5000f, 0.7f, -6f); micColor2.set(Biquad.Kind.Peak, sr, 300f, 0.7f, 2f) }
        }
        reflDelay = 1f + d * sr * 0.006f; reflGain = d * 0.45f; level = Shapers.dbToLin(p[4] + d * 4f)
    }
    override fun process(buf: FloatArray, n: Int) {
        for (i in 0 until n) {
            var y = lowCut.process(buf[i]); y = res.process(y); y = presence.process(y); y = hiCut2.process(hiCut.process(y))
            y = axis.process(y); y = prox.process(y); y = micColor2.process(micColor.process(y))
            refl.write(y); y += refl.read(reflDelay) * reflGain
            buf[i] = y * level
        }
        val mix = p[3]
        if (mix > 0.001f) {
            var off = 0
            while (off < n) { val c = min(wet.size, n - off); for (i in 0 until c) wet[i] = buf[off + i]
                room.block(wet, c); for (i in 0 until c) { val dry = buf[off + i]; buf[off + i] = dry + (wet[i] - 0.5f * dry) * mix }; off += c }
        }
    }
}
