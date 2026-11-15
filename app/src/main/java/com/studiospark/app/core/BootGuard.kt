package com.studiospark.app.core

import android.content.Context

/**
 * What a launch has loaded. [tracks] = the open session's strips; [loadedGear] = amp/pedal/instrument gear actually
 * instantiated (built rig processors, gear added through the shared GearLibrary); [loadedModels] = ML models created
 * (Import's ONNX/YAMNet loads counted by importer.ModelGate). A thin view over the existing Project/StudioVM state.
 */
data class StudioSession(val tracks: List<Track>, val loadedGear: Set<String>, val loadedModels: Set<String>) {
    companion object {
        fun empty() = StudioSession(emptyList(), emptySet(), emptySet())
        /** Snapshot of the running app: [builtRigTrackIds] from LazyRigs.builtIds(), [gearInUse] from GearLibrary.loadedGear(). */
        fun of(project: Project, builtRigTrackIds: Set<String>, gearInUse: Set<String>, modelLoads: Int, lastModel: String? = null) = StudioSession(
            tracks = project.tracks.toList(),
            loadedGear = builtRigTrackIds.map { "rig:$it" }.toSet() + gearInUse,
            loadedModels = if (modelLoads > 0) setOf(lastModel ?: "model×$modelLoads") else emptySet())
    }
}

/**
 * Blank-launch guard (2.2.0, permanent rule). Lucas specified package com.studiospark.core; it lives in the app's
 * core package (com.studiospark.app.core) so it compiles with the rest of the app.
 */
object BootGuard {
    /**
     * The session a launch opens with: empty. Loads nothing — no gear, no models, no stems, no samples. [context] is
     * accepted for the agreed signature and deliberately not used to read any asset, file or preference.
     */
    @Suppress("UNUSED_PARAMETER")
    fun enforceBlankLaunch(context: Context): StudioSession = StudioSession.empty()

    fun isLaunchClean(session: StudioSession): Boolean =
        session.tracks.isEmpty() && session.loadedGear.isEmpty() && session.loadedModels.isEmpty()
}
