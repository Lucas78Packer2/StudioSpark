package com.aimusicstudio.app

import java.io.File
import java.util.Locale

/** Port of SampleLibrary.swift */
data class SampleLibraryItem(val id: String, val name: String, val file: File, val duration: Double) {
    val formatLabel: String get() = file.extension.uppercase(Locale.US).ifEmpty { "AUDIO" }
    val category: String get() {
        val n = name.lowercase(Locale.US)
        return when {
            listOf("kick", "bass drum", "tom", "cymbal", "cowbell", "rimshot", "snare", "hat", "clap", "drum", "perc").any { it in n } -> "Drums"
            n.startsWith("lib ") && listOf("guitar","strat","paul","tele","superstrat").any { it in n } -> "Guitars"
            n.startsWith("lib ") && listOf("piano","organ","keys","bell").any { it in n } -> "Keys"
            "bass" in n -> "Bass"
            listOf("synth", "lead", "pad", "chord").any { it in n } -> "Synth"
            n.startsWith("lib ") && listOf("pad","choir","strings","brass","flute").any { it in n } -> "Pads & Orch"
            "take" in n || "rec" in n -> "Recordings"
            else -> "Other"
        }
    }
}

object SampleLibrary {
    private val audioExtensions = setOf("wav", "caf", "aif", "aiff", "m4a", "mp3")

    fun scan(dir: File): List<SampleLibraryItem> =
        (dir.listFiles() ?: emptyArray())
            .filter { !it.isHidden && it.extension.lowercase(Locale.US) in audioExtensions }
            .map { SampleLibraryItem(it.name, displayName(it.name), it, AudioDecoder.durationOf(it)) }
            .sortedBy { it.name.lowercase(Locale.US) }

    fun displayName(fileName: String): String {
        val base = fileName.substringBeforeLast('.', fileName)
        var name = base
        if (base.startsWith("stem-")) {
            val rest = base.removePrefix("stem-")
            val dash = rest.indexOf('-')
            name = if (dash >= 0) rest.substring(dash + 1) else rest
        }
        name = stripImportPrefix(name).replace('-', ' ').replace('_', ' ').trim()
        while ("  " in name) name = name.replace("  ", " ")
        if (name.isEmpty()) return "Untitled Sound"
        return if (name == name.lowercase(Locale.US))
            name.split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.titlecase(Locale.US) } } else name
    }

    private fun stripImportPrefix(name: String): String {
        if (name.length <= 9) return name
        val sep = name[8]
        if (sep != '_' && sep != '-') return name
        if (!name.take(8).all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return name
        return name.drop(9)
    }
}
