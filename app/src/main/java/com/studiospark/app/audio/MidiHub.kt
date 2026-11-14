package com.studiospark.app.audio

import android.content.Context
import android.media.midi.*
import android.os.Handler
import android.os.Looper

/** USB/Bluetooth MIDI controller input (plays the selected track) and MIDI output of on-screen playing. */
class MidiHub(ctx: Context, private val onNote: (on: Boolean, pitch: Int, vel: Int) -> Unit) {
    private val mm: MidiManager? = ctx.getSystemService(MidiManager::class.java)
    private val opened = mutableListOf<MidiDevice>(); private val outPorts = mutableListOf<MidiInputPort>()
    var devices = listOf<String>(); private set
    var onChange: (() -> Unit)? = null
    val available get() = mm != null

    private val receiver = object : MidiReceiver() {
        override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
            var i = offset; val end = offset + count
            while (i < end) { val st = msg[i].toInt() and 0xFF; val type = st and 0xF0
                if ((type == 0x90 || type == 0x80) && i + 2 < end) { val p = msg[i + 1].toInt() and 0x7F; val v = msg[i + 2].toInt() and 0x7F; onNote(type == 0x90 && v > 0, p, v); i += 3 }
                else i++ }
        }
    }

    fun scan() {
        val m = mm ?: return
        @Suppress("DEPRECATION") val infos = m.devices
        devices = infos.map { it.properties.getString(MidiDeviceInfo.PROPERTY_NAME) ?: "MIDI device" }
        infos.forEach { info -> if (opened.none { it.info.id == info.id }) m.openDevice(info, { dev -> if (dev != null) { opened += dev
            for (p in info.ports) { if (p.type == MidiDeviceInfo.PortInfo.TYPE_OUTPUT) dev.openOutputPort(p.portNumber)?.connect(receiver)
                if (p.type == MidiDeviceInfo.PortInfo.TYPE_INPUT) dev.openInputPort(p.portNumber)?.let { outPorts += it } } } }, Handler(Looper.getMainLooper())) }
        onChange?.invoke()
    }
    private var callback: MidiManager.DeviceCallback? = null
    val listening get() = callback != null
    /** Starts listening for MIDI controllers. Only called when the user turns MIDI input on. */
    fun listen() { if (callback != null) return; val cb = object : MidiManager.DeviceCallback() {
        override fun onDeviceAdded(d: MidiDeviceInfo) = scan()
        override fun onDeviceRemoved(d: MidiDeviceInfo) { opened.removeAll { it.info.id == d.id }; scan() } }
        callback = cb; mm?.registerDeviceCallback(cb, Handler(Looper.getMainLooper())); scan() }
    fun stopListening() { callback?.let { runCatching { mm?.unregisterDeviceCallback(it) } }; callback = null; close(); devices = emptyList() }

    fun send(on: Boolean, pitch: Int, vel: Int, channel: Int = 0) { if (outPorts.isEmpty()) return
        val b = byteArrayOf(((if (on) 0x90 else 0x80) or channel).toByte(), pitch.toByte(), vel.toByte()); outPorts.forEach { runCatching { it.send(b, 0, 3) } } }
    fun close() { opened.forEach { runCatching { it.close() } }; opened.clear(); outPorts.clear() }
}
