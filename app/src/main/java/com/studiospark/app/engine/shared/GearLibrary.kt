package com.studiospark.app.engine.shared

import com.studiospark.app.core.InstrumentDef
import com.studiospark.app.core.InstrumentLibrary
import com.studiospark.app.core.Track
import com.studiospark.app.dsp.GearCatalog
import com.studiospark.app.dsp.GearDef
import com.studiospark.app.dsp.GearKind
import com.studiospark.app.dsp.RigSlot

/**
 * Universal gear library (amps, pedals, instruments) shared by every tab and session. Nothing is indexed or loaded
 * until the user adds gear to a track; [loadedGear] lists only gear that is actually on a track.
 * Every name comes from the existing original-name catalogs (GearCatalog, InstrumentLibrary): no new assets.
 */
class GearLibrary(private val loader: () -> Catalog = ::defaultCatalog) {
    class Catalog(val amps: List<GearDef>, val pedals: List<GearDef>, val instruments: List<InstrumentDef>) {
        private val gearById = (amps + pedals).associateBy { it.id }
        fun gear(id: String) = gearById[id]
        fun instrument(id: String) = instruments.firstOrNull { it.id == id }
    }
    private var catalog: Catalog? = null
    private val inUse = LinkedHashSet<String>()
    var loads = 0; private set

    @Synchronized fun isLoaded() = catalog != null
    /** Gear ids the user has put on a track in any session (empty at launch). */
    @Synchronized fun loadedGear(): Set<String> = inUse.toSet()
    @Synchronized private fun catalog(): Catalog = catalog ?: loader().also { catalog = it; loads++ }
    /** Browsing the shelf (e.g. opening a gear picker) — still a user action, never called at launch. */
    fun browse(): Catalog = catalog()

    /** User added an amp/cab/pedal to [track]: loads the library if needed and appends the gear to the track's chain. */
    @Synchronized fun addGear(track: Track, gearId: String): RigSlot {
        requireNotNull(catalog().gear(gearId)) { "Unknown gear $gearId" }
        inUse += gearId
        return track.rig.add(gearId)
    }
    /** User picked an instrument for [track]. */
    @Synchronized fun setInstrument(track: Track, instrumentId: String) {
        val inst = requireNotNull(catalog().instrument(instrumentId)) { "Unknown instrument $instrumentId" }
        inUse += inst.id; track.instrumentId = inst.id; track.family = inst.family
    }
    /** Called when a session is discarded or a track removed: forgets gear no track uses any more. */
    @Synchronized fun retain(tracks: Collection<Track>) {
        val live = tracks.flatMap { t -> t.rig.slots.map { it.gearId } + t.instrumentId }.toSet(); inUse.retainAll(live)
    }

    companion object {
        fun defaultCatalog() = Catalog(
            amps = GearCatalog.all.filter { it.kind == GearKind.AMP || it.kind == GearKind.CAB },
            pedals = GearCatalog.all.filter { it.kind == GearKind.STOMP || it.kind == GearKind.RACK },
            instruments = InstrumentLibrary.stock)
    }
}
