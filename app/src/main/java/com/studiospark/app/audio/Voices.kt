package com.studiospark.app.audio

import com.studiospark.app.core.ENGINE_SR
import com.studiospark.app.dsp.Biquad
import kotlin.math.*

/** A sounding voice. render() ADDS into [out]. Return false when finished. */
abstract class Voice(val tag: Int, val choke: Int) {
    var age = 0L
    /** Stereo position for kit pieces (−1 left … +1 right); used by stereo drum channels. */
    var pan = 0f
    var releasing = false; protected set
    open fun release() { releasing = true }
    open fun kill() { releasing = true }
    abstract fun render(out: FloatArray, n: Int): Boolean
}

class SampleVoice(private val s: ShortArray, private val rate: Double, private val gain: Float, tag: Int, choke: Int, brightness: Float = 1f, attackMs: Float = 0f, private val releaseMs: Float = 60f, private val sustainHold: Boolean = false) : Voice(tag, choke) {
    private var pos = 0.0; private var env = if (attackMs > 0) 0f else 1f
    private val attInc = if (attackMs > 0) 1f / (ENGINE_SR * attackMs / 1000f) else 1f
    private var relMul = 1f
    private val lpA = if (brightness >= 0.99f) 0f else exp(-2f * PI.toFloat() * (300f + brightness * brightness * 16000f) / ENGINE_SR)
    private var z = 0f; private var fastKill = false
    override fun release() { if (!sustainHold) { releasing = true; relMul = exp(-1f / (ENGINE_SR * releaseMs / 1000f)) } }
    override fun kill() { releasing = true; fastKill = true; relMul = exp(-1f / (ENGINE_SR * 0.008f)) }
    override fun render(out: FloatArray, n: Int): Boolean {
        val last = s.size - 2
        for (i in 0 until n) {
            val ip = pos.toInt(); if (ip >= last) return false
            val fr = (pos - ip).toFloat(); var x = (s[ip] + (s[ip + 1] - s[ip]) * fr) / 32768f
            if (lpA > 0f) { z = x + lpA * (z - x); x = z }
            if (env < 1f && !releasing) env = min(1f, env + attInc)
            if (releasing) { env *= relMul; if (env < 0.0005f) return false }
            out[i] += x * gain * env; pos += rate
        }
        age += n; return true
    }
}

/** Merged synth library: subtractive, wavetable and FM engines behind one preset list. */
class SynthVoice(preset: String, midi: Int, vel: Int, tag: Int) : Voice(tag, 0) {
    private enum class Eng { SUB, WT, FM, ORGAN }
    private val hz = 440.0 * 2.0.pow((midi - 69) / 12.0)
    private val sr = ENGINE_SR.toDouble()
    private var ph = 0.0; private var ph2 = 0.0; private var ph3 = 0.0; private var mph = 0.0
    private val amp = vel / 127f * 0.32f
    private val eng: Eng; private val att: Float; private val dec: Float; private val sus: Float; private val rel: Float
    private var cutoff: Float; private val envAmt: Float; private val res: Float; private val wave: Int; private val fmRatio: Double; private val fmIndex: Double; private val detune: Double
    init { when (preset) {
        "sub_bass" -> { eng = Eng.SUB; wave = 0; att = 2f; dec = 300f; sus = 0.7f; rel = 120f; cutoff = 300f; envAmt = 1500f; res = 2f; fmRatio = 1.0; fmIndex = 0.0; detune = 0.0 }
        "saw_lead" -> { eng = Eng.SUB; wave = 0; att = 5f; dec = 400f; sus = 0.8f; rel = 200f; cutoff = 1800f; envAmt = 3000f; res = 3f; fmRatio = 1.0; fmIndex = 0.0; detune = 0.006 }
        "warm_pad" -> { eng = Eng.SUB; wave = 0; att = 600f; dec = 900f; sus = 0.85f; rel = 1200f; cutoff = 900f; envAmt = 600f; res = 1f; fmRatio = 1.0; fmIndex = 0.0; detune = 0.01 }
        "square_arp" -> { eng = Eng.SUB; wave = 1; att = 1f; dec = 180f; sus = 0.3f; rel = 90f; cutoff = 1500f; envAmt = 4000f; res = 4f; fmRatio = 1.0; fmIndex = 0.0; detune = 0.0 }
        "pluck" -> { eng = Eng.SUB; wave = 0; att = 1f; dec = 250f; sus = 0f; rel = 150f; cutoff = 400f; envAmt = 6000f; res = 2f; fmRatio = 1.0; fmIndex = 0.0; detune = 0.003 }
        "brass_synth" -> { eng = Eng.SUB; wave = 0; att = 40f; dec = 500f; sus = 0.75f; rel = 250f; cutoff = 700f; envAmt = 2500f; res = 1.2f; fmRatio = 1.0; fmIndex = 0.0; detune = 0.004 }
        "wt_glass" -> { eng = Eng.WT; wave = 0; att = 8f; dec = 800f; sus = 0.6f; rel = 600f; cutoff = 9000f; envAmt = 0f; res = 0.7f; fmRatio = 1.0; fmIndex = 0.0; detune = 0.0 }
        "wt_vowel" -> { eng = Eng.WT; wave = 1; att = 80f; dec = 600f; sus = 0.8f; rel = 500f; cutoff = 6000f; envAmt = 0f; res = 0.7f; fmRatio = 1.0; fmIndex = 0.0; detune = 0.0 }
        "fm_ep" -> { eng = Eng.FM; wave = 0; att = 1f; dec = 1400f; sus = 0.2f; rel = 300f; cutoff = 12000f; envAmt = 0f; res = 0.7f; fmRatio = 1.0; fmIndex = 1.8; detune = 0.0 }
        "fm_bell" -> { eng = Eng.FM; wave = 0; att = 1f; dec = 2500f; sus = 0f; rel = 1500f; cutoff = 14000f; envAmt = 0f; res = 0.7f; fmRatio = 3.5; fmIndex = 3.0; detune = 0.0 }
        "fm_bass" -> { eng = Eng.FM; wave = 0; att = 1f; dec = 350f; sus = 0.5f; rel = 100f; cutoff = 5000f; envAmt = 0f; res = 0.7f; fmRatio = 0.5; fmIndex = 2.5; detune = 0.0 }
        else -> { eng = Eng.ORGAN; wave = 0; att = 5f; dec = 10f; sus = 1f; rel = 60f; cutoff = 12000f; envAmt = 0f; res = 0.7f; fmRatio = 1.0; fmIndex = 0.0; detune = 0.0 }
    } }
    private var env = 0f; private var stage = 0; private var fenv = 1f
    private val filt = Biquad(); private var cnt = 0
    override fun render(out: FloatArray, n: Int): Boolean {
        val aInc = 1f / (sr.toFloat() * att / 1000f); val dMul = exp(-1f / (sr.toFloat() * dec / 1000f)); val rMul = exp(-1f / (sr.toFloat() * rel / 1000f)); val fMul = exp(-1f / (sr.toFloat() * 0.25f))
        for (i in 0 until n) {
            if (releasing) { env *= rMul; if (env < 0.0003f) return false }
            else if (stage == 0) { env += aInc; if (env >= 1f) { env = 1f; stage = 1 } }
            else { env = sus + (env - sus) * dMul }
            fenv *= fMul
            if (cnt++ % 32 == 0) filt.set(Biquad.Kind.LowPass, sr.toFloat(), cutoff + envAmt * fenv, res)
            val inc = hz / sr
            var x = when (eng) {
                Eng.SUB -> { ph += inc * (1 + detune); ph2 += inc * (1 - detune); if (ph >= 1) ph -= 1; if (ph2 >= 1) ph2 -= 1
                    if (wave == 0) ((2 * ph - 1) + (2 * ph2 - 1)).toFloat() * 0.5f else ((if (ph < 0.5) 1f else -1f) + (if (ph2 < 0.5) 1f else -1f)) * 0.4f }
                Eng.WT -> { ph += inc; if (ph >= 1) ph -= 1; mph += 0.3 / sr; val morph = (0.5 + 0.5 * sin(2 * PI * mph)).toFloat()
                    val s1 = sin(2 * PI * ph); val s2 = if (wave == 0) sin(2 * PI * ph * 3) * 0.5 + sin(2 * PI * ph * 5) * 0.25 else sin(2 * PI * ph * 2) * 0.6 + sin(2 * PI * ph * 4) * 0.35
                    (s1 * (1 - morph) + s2 * morph).toFloat() }
                Eng.FM -> { ph += inc; ph2 += inc * fmRatio; if (ph >= 1) ph -= 1; if (ph2 >= 1) ph2 -= 1; val idx = fmIndex * (0.3 + 0.7 * fenv); sin(2 * PI * ph + idx * sin(2 * PI * ph2)).toFloat() }
                Eng.ORGAN -> { ph += inc; ph2 += inc * 2; ph3 += inc * 3; if (ph >= 1) ph -= 1; if (ph2 >= 1) ph2 -= 1; if (ph3 >= 1) ph3 -= 1
                    (sin(2 * PI * ph) * 0.6 + sin(2 * PI * ph2) * 0.35 + sin(2 * PI * ph3) * 0.25 + sin(2 * PI * ph * 0.5) * 0.3).toFloat() * 0.6f }
            }
            x = filt.process(x)
            out[i] += x * env * amp
        }
        age += n; return true
    }
}

/** On-device formant singing voice for the AI Vocal track: glottal source + three vowel formants + consonant noise. Synthetic, not a recorded singer. */
class FormantVoice(midi: Int, vel: Int, private val vowel: Char, private val consonant: Boolean, style: Int, tag: Int) : Voice(tag, 0) {
    private val sr = ENGINE_SR.toFloat(); private val hz = (440.0 * 2.0.pow((midi - 69) / 12.0)).toFloat()
    private var ph = 0f; private val f = Array(3) { Biquad() }; private var env = 0f; private var t = 0; private val vib = 5.5f; private var noiseSeed = 12345
    private val amp = vel / 127f * (if (style == 1) 0.5f else 0.4f); private val breath = if (style == 2) 0.25f else 0.06f
    init {
        val (f1, f2, f3) = when (vowel) { 'a' -> Triple(800f, 1150f, 2900f); 'e' -> Triple(400f, 1700f, 2600f); 'i' -> Triple(300f, 2200f, 3000f); 'o' -> Triple(450f, 800f, 2830f); else -> Triple(350f, 600f, 2700f) }
        val shift = if (style == 1) 1.08f else 1f
        f[0].set(Biquad.Kind.BandPass, sr, f1 * shift, 6f); f[1].set(Biquad.Kind.BandPass, sr, f2 * shift, 8f); f[2].set(Biquad.Kind.BandPass, sr, f3 * shift, 10f)
    }
    private fun noise(): Float { noiseSeed = noiseSeed * 1103515245 + 12345; return ((noiseSeed ushr 16) and 0x7FFF) / 16384f - 1f }
    override fun render(out: FloatArray, n: Int): Boolean {
        val att = 1f / (sr * 0.03f); val rel = exp(-1f / (sr * 0.09f))
        for (i in 0 until n) {
            t++
            if (releasing) { env *= rel; if (env < 0.0005f) return false } else env = min(1f, env + att)
            val vibAmt = if (t > sr * 0.25f) 0.012f else 0f
            ph += hz * (1f + vibAmt * sin(2f * PI.toFloat() * vib * t / sr)) / sr; if (ph >= 1f) ph -= 1f
            val glottal = (if (ph < 0.6f) sin(PI.toFloat() * ph / 0.6f) else 0f) * 2f - 0.75f
            var src = glottal + noise() * breath
            if (consonant && t < sr * 0.05f) src = src * (t / (sr * 0.05f)) + noise() * 0.8f * (1f - t / (sr * 0.05f))
            val y = f[0].process(src) * 1.0f + f[1].process(src) * 0.6f + f[2].process(src) * 0.3f
            out[i] += y * env * amp
        }
        age += n; return true
    }
}
