package com.nekiplay.neopathfinder.pathfinder.cache

import net.minecraft.core.BlockPos
/**
 * Block-based dead-end cache: remembers chunk regions that led to a dead end
 * and the entry point so backtracking knows where to go back to.
 */
class DeadEndCache {
    data class DeadEndInfo(
        /** Chunk coordinates (chunkX shr 4, chunkZ shr 4). */
        val chunkX: Int,
        val chunkZ: Int,
        /** The entry block position into this chunk (where we came from). */
        val entryPos: BlockPos,
        /** Direction we were heading when we entered this chunk. */
        val entryDirectionX: Int,
        val entryDirectionZ: Int,
        /** Timestamp when this entry was created (System.currentTimeMillis()). */
        val timestampMs: Long = System.currentTimeMillis()
    )

    companion object {
        /** Entries older than this are evicted (5 minutes). */
        private const val MAX_AGE_MS = 5 * 60 * 1000L
        /** Hard cap on cache entries before pruning. */
        private const val MAX_ENTRIES = 500

        fun chunkHash(x: Int, z: Int): Long {
            return (x.toLong() shl 32) or (z.toLong() and 0xFFFFFFFFL)
        }
    }
    /** Chunk long hash -> DeadEndInfo */
    private val cache = linkedMapOf<Long, DeadEndInfo>()

    /** Ordered stack of chunk entry nodes for backtracking. */
    private val entryStack = mutableListOf<DeadEndInfo>()

    /** Last prune timestamp to avoid pruning on every operation. */
    private var lastPruneMs = System.currentTimeMillis()
    private val PRUNE_INTERVAL_MS = 30_000L // prune at most every 30s
    fun pushEntry(pos: BlockPos, directionX: Int, directionZ: Int) {
        pruneIfNeeded()
        val cx = pos.x shr 4
        val cz = pos.z shr 4
        val key = chunkHash(cx, cz)
        // Don't push duplicate entries for the same chunk
        if (cache.containsKey(key)) return
        val info = DeadEndInfo(cx, cz, pos, directionX, directionZ)
        cache[key] = info
        entryStack.add(info)
    }
    /**
     * Mark the current chunk as a dead end and return the previous entry point for backtracking.
     * Returns null if there is no previous entry to backtrack to.
     */
    /**
     * Mark the current chunk as a dead end and return the previous entry point for backtracking.
     * Returns null if there is no previous entry to backtrack to.
     */
    fun markDeadEndAndBacktrack(): BlockPos? {
        pruneIfNeeded()
        if (entryStack.isEmpty()) return null
        val deadInfo = entryStack.removeAt(entryStack.lastIndex)
        val deadKey = chunkHash(deadInfo.chunkX, deadInfo.chunkZ)
        // Re-mark with dead-end flag and fresh timestamp
        cache[deadKey] = deadInfo.copy(
            chunkX = deadInfo.chunkX or (1 shl 31),
            timestampMs = System.currentTimeMillis()
        )
        // Return the entry before this one
        if (entryStack.isEmpty()) return null
        return entryStack.last().entryPos
    }
    fun isDeadEnd(x: Int, z: Int): Boolean {
        pruneIfNeeded()
        val key = chunkHash(x shr 4, z shr 4)
        val info = cache[key] ?: return false
        // High bit of chunkX set = dead end flag
        val flagged = info.chunkX and (1 shl 31) != 0
        // Also check expiry even for dead ends
        if (flagged && ageMs(info) > MAX_AGE_MS) {
            cache.remove(key)
            return false
        }
        return flagged
    }
    fun clear() {
        cache.clear()
        entryStack.clear()
    }

    /** Remove expired entries and trim if over capacity. */
    private fun pruneIfNeeded() {
        val now = System.currentTimeMillis()
        if (now - lastPruneMs < PRUNE_INTERVAL_MS) return
        lastPruneMs = now

        val tooOld = cache.filter { (_, info) -> ageMs(info, now) > MAX_AGE_MS }
        tooOld.keys.forEach { cache.remove(it) }

        if (cache.size > MAX_ENTRIES) {
            // Remove oldest entries first (linkedMapOf insertion order)
            val toRemove = cache.size - MAX_ENTRIES
            val iter = cache.iterator()
            repeat(toRemove) {
                if (iter.hasNext()) {
                    iter.next()
                    iter.remove()
                }
            }
        }
    }

    private fun ageMs(info: DeadEndInfo, now: Long = System.currentTimeMillis()): Long {
        return now - info.timestampMs
    }
}
