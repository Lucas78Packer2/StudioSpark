package com.studiospark.app.core

import java.io.ByteArrayOutputStream

/** Standard MIDI File (SMF) import/export for track notes. */
object MidiFile {
    private const val PPQ = 480
    data class Imported(val tempo: Float, val tracks: List<Pair<String, List<MidiNote>>>)

    fun write(p: Project, tracks: List<Track>): ByteArray {
        val out = ByteArrayOutputStream()
        fun u32(v: Int) { out.write(v ushr 24 and 255); out.write(v ushr 16 and 255); out.write(v ushr 8 and 255); out.write(v and 255) }
        fun u16(v: Int) { out.write(v ushr 8 and 255); out.write(v and 255) }
        out.write("MThd".toByteArray()); u32(6); u16(1); u16(tracks.size + 1); u16(PPQ)
        val spt = p.samplesPerBeat.toDouble() / PPQ
        val conductor = ByteArrayOutputStream().apply { val us = (60_000_000 / p.tempo).toInt(); varLen(this, 0); write(byteArrayOf(0xFF.toByte(), 0x51, 3, (us shr 16).toByte(), (us shr 8).toByte(), us.toByte()))
            varLen(this, 0); write(byteArrayOf(0xFF.toByte(), 0x58, 4, p.sigNum.toByte(), Integer.numberOfTrailingZeros(p.sigDen).toByte(), 24, 8)); varLen(this, 0); write(byteArrayOf(0xFF.toByte(), 0x2F, 0)) }.toByteArray()
        out.write("MTrk".toByteArray()); u32(conductor.size); out.write(conductor)
        tracks.forEachIndexed { ti, t -> val ch = if (t.family == com.studiospark.app.dsp.Family.DRUMS) 9 else ti % 9
            data class Ev(val tick: Long, val on: Boolean, val n: MidiNote)
            val evs = t.notes.flatMap { listOf(Ev((it.start / spt).toLong(), true, it), Ev(((it.start + it.length) / spt).toLong(), false, it)) }.sortedWith(compareBy({ it.tick }, { it.on }))
            val b = ByteArrayOutputStream(); val name = t.name.toByteArray(); varLen(b, 0); b.write(0xFF); b.write(0x03); varLen(b, name.size); b.write(name)
            var last = 0L; for (e in evs) { varLen(b, (e.tick - last).toInt()); last = e.tick; b.write((if (e.on) 0x90 else 0x80) or ch); b.write(e.n.pitch and 127); b.write(if (e.on) e.n.velocity.coerceIn(1, 127) else 0) }
            varLen(b, 0); b.write(byteArrayOf(0xFF.toByte(), 0x2F, 0)); val bytes = b.toByteArray(); out.write("MTrk".toByteArray()); u32(bytes.size); out.write(bytes) }
        return out.toByteArray()
    }

    private fun varLen(o: ByteArrayOutputStream, v0: Int) { var v = v0; var buf = v and 0x7F; while (true) { v = v ushr 7; if (v == 0) break; buf = (buf shl 8) or ((v and 0x7F) or 0x80) }
        while (true) { o.write(buf and 0xFF); if (buf and 0x80 != 0) buf = buf ushr 8 else break } }

    fun read(data: ByteArray, sampleRate: Int = ENGINE_SR): Imported {
        var i = 0
        fun u8() = data[i++].toInt() and 255
        fun u16() = (u8() shl 8) or u8()
        fun u32() = (u16() shl 16) or u16()
        fun vl(): Int { var v = 0; while (true) { val b = u8(); v = (v shl 7) or (b and 0x7F); if (b and 0x80 == 0) return v } }
        require(String(data, 0, 4) == "MThd") { "Not a MIDI file" }; i = 4; val hl = u32(); u16(); val nt = u16(); val div = u16(); i = 8 + hl
        require(div and 0x8000 == 0) { "SMPTE timing not supported" }
        var tempoUs = 500_000; val result = mutableListOf<Pair<String, List<MidiNote>>>()
        repeat(nt) { tn -> if (i + 8 > data.size) return@repeat; require(String(data, i, 4) == "MTrk"); i += 4; val len = u32(); val end = i + len
            var tick = 0L; var status = 0; var name = "MIDI ${tn + 1}"; val open = HashMap<Int, Pair<Long, Int>>(); val notes = mutableListOf<MidiNote>()
            fun toSamples(t: Long) = (t.toDouble() / div * tempoUs / 1e6 * sampleRate).toLong()
            while (i < end) { tick += vl(); var b = u8(); if (b < 0x80) { i--; b = status } else status = b
                when { b == 0xFF -> { val type = u8(); val l = vl(); if (type == 0x51 && l == 3) tempoUs = (u8() shl 16) or (u8() shl 8) or u8() else { if (type == 0x03) name = String(data, i, l); i += l } }
                    b == 0xF0 || b == 0xF7 -> i += vl()
                    else -> { val hi = b and 0xF0; val d1 = u8(); val d2 = if (hi == 0xC0 || hi == 0xD0) 0 else u8()
                        if (hi == 0x90 && d2 > 0) open[d1] = tick to d2
                        else if (hi == 0x80 || (hi == 0x90 && d2 == 0)) open.remove(d1)?.let { (st, vel) -> notes.add(MidiNote(d1, toSamples(st), (toSamples(tick) - toSamples(st)).coerceAtLeast(100), vel)) } } } }
            i = end; if (notes.isNotEmpty()) result.add(name to notes.sortedBy { it.start }) }
        return Imported(60_000_000f / tempoUs, result)
    }
}
