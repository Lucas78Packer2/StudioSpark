package com.studiospark.app.dsp

/**
 * Builds amp/pedal chains (RigProcessor) lazily, per track. A track gets a processor only after it is marked wanted:
 * the user added or changed gear on it, played it live, armed/monitored it, or the transport is playing it.
 * Nothing is built at launch.
 */
class LazyRigs(private val sr: Float, private val factory: (Float, Rig) -> RigProcessor = { s, r -> RigProcessor(s, r) }) {
    private val wanted = HashSet<String>()
    private val mono = HashMap<String, RigProcessor>()
    private val right = HashMap<String, RigProcessor>()
    var builds = 0; private set

    /** Marks a track as needing its rig; returns true if it was not wanted before. */
    @Synchronized fun want(id: String): Boolean = wanted.add(id)
    @Synchronized fun isWanted(id: String) = id in wanted
    @Synchronized fun builtIds(): Set<String> = mono.keys.toSet()
    @Synchronized fun builtCount() = mono.size + right.size

    /** Returns the (mono, right) processors for a track, building or rebuilding them only if the track is wanted. */
    @Synchronized fun resolve(id: String, rig: Rig, stereo: Boolean): Pair<RigProcessor?, RigProcessor?> {
        if (id !in wanted) { mono.remove(id); right.remove(id); return null to null }
        val m = mono[id]?.takeIf { it.matches(rig) } ?: factory(sr, rig).also { mono[id] = it; builds++ }
        val r = if (!stereo) { right.remove(id); null } else right[id]?.takeIf { it.matches(rig) } ?: factory(sr, rig).also { right[id] = it; builds++ }
        return m to r
    }
    /** Forgets tracks that no longer exist. */
    @Synchronized fun retain(ids: Set<String>) { wanted.retainAll(ids); mono.keys.retainAll(ids); right.keys.retainAll(ids) }
    @Synchronized fun clear() { wanted.clear(); mono.clear(); right.clear() }
}
