package com.studiospark.app.eartraining

import com.studiospark.app.importer.StemLibrary

/**
 * Adapter from the importer's stem libraries (com.studiospark.app.importer.StemLibrary, mono PCM16, rate in StemMeta.sampleRate)
 * to [StemClipProvider]. [libraries] is a lambda so nothing is constructed until the tab first opens,
 * e.g. `StemLibraryClips({ vm.importer().libraries })` (ImportController.libraries: best model first).
 * Loads only the [maxEntries] most recently used entries across all libraries; drums are skipped.
 * Note: StemLibrary.load() refreshes an entry's lastUsed (LRU), as any library hit does.
 */
class StemLibraryClips(private val libraries: () -> List<StemLibrary>, private val maxEntries: Int = 3) : StemClipProvider {
    override fun clips(): List<StemClip> {
        val libs = libraries()
        return libs.flatMap { lib -> lib.entries().map { lib to it } }.sortedByDescending { it.second.lastUsed }.take(maxEntries)
            .flatMap { (lib, m) ->
                val (meta, stems) = lib.load(m.hash) ?: return@flatMap emptyList()
                val sr = meta.sampleRate.takeIf { it > 0 } ?: DEFAULT_STEM_SR
                stems.filterKeys { it != "drums" }.map { (name, pcm) -> StemClip(name, pcm, sr) }
            }
    }
    companion object { const val DEFAULT_STEM_SR = 44100 }
}
