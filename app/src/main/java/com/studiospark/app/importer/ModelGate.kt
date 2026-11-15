package com.studiospark.app.importer

import java.util.concurrent.atomic.AtomicInteger

/**
 * Launch guard (2.2.0): every heavy Import resource — the ONNX Runtime environment and sessions, and the YAMNet
 * interpreter — is created only through here, and only from an import the user started. Tests assert the count is
 * still 0 after a cold start and after merely opening the Import tab. Each load is also written to logcat under the
 * "SSImport" tag so a device cold start can be checked with `adb logcat -s SSImport`.
 */
object ModelGate {
    const val TAG = "SSImport"
    private val count = AtomicInteger(0)
    val loads get() = count.get()
    @Volatile var lastLoad: String? = null; private set
    fun record(what: String) { count.incrementAndGet(); lastLoad = what; runCatching { android.util.Log.i(TAG, "LOAD $what") } }
    fun note(msg: String) { runCatching { android.util.Log.i(TAG, msg) } }
    /** Tests only. */
    internal fun resetForTest() { count.set(0); lastLoad = null }
}
