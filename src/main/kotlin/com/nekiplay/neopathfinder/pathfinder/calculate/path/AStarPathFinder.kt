package com.nekiplay.neopathfinder.pathfinder.calculate.path

import it.unimi.dsi.fastutil.longs.Long2ObjectMap
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import com.nekiplay.neopathfinder.pathfinder.cache.DeadEndCache
import com.nekiplay.neopathfinder.pathfinder.calculate.Path
import com.nekiplay.neopathfinder.pathfinder.calculate.PathNode
import com.nekiplay.neopathfinder.pathfinder.calculate.openset.BinaryHeapOpenSet
import com.nekiplay.neopathfinder.pathfinder.goal.Goal
import com.nekiplay.neopathfinder.pathfinder.movement.CalculationContext
import com.nekiplay.neopathfinder.pathfinder.movement.MovementResult
import com.nekiplay.neopathfinder.pathfinder.movement.Moves

import net.minecraft.core.BlockPos
import com.nekiplay.neopathfinder.pathfinder.util.BlockUtil
import com.nekiplay.neopathfinder.pathfinder.movement.MovementHelper
import kotlin.math.abs
import kotlin.math.sqrt
import com.nekiplay.neopathfinder.util.Logger
class AStarPathFinder(val startX: Int, val startY: Int, val startZ: Int, val goal: Goal, val ctx: CalculationContext) {
    companion object {
        /**
         * Upper bound on search loop passes. Far goals legitimately need hundreds of thousands of
         * expansions, so the default is generous and callers may raise it at runtime.
         */
        @JvmStatic
        var maxIterations = 2_000_000
        private const val ITERATION_STEP = 1

        /**
         * Max blocks from player to search in one A* pass (8 chunks). Beyond this
         * the goal is snapped to a cell on the chunk boundary in the goal direction.
         */
        private const val SEGMENT_RADIUS_BLOCKS = 128

        /**
         * Number of chunk-sized steps to backtrack (e.g. 3 chunks ≈ 48 blocks) when
         * the dead-end resolver needs to go far enough to find a viable reroute.
         */
        private const val BACKTRACK_CHUNKS = 3
    }

    data class SearchTelemetry(
        val iterations: Int = 0,
        val expandedNodes: Int = 0,
        val openSetPeak: Int = 0,
        val terminationReason: String = "not_started"
    )

    private val closedSet: Long2ObjectMap<PathNode> = Long2ObjectOpenHashMap()
    @Volatile
    private var calculating = false
    private var iterations = 0
    @Volatile
    private var lastTelemetry = SearchTelemetry()

    // Dead-end tracking
    private val deadEndCache = DeadEndCache()

    fun getLastTelemetry(): SearchTelemetry {
        return lastTelemetry
    }

    // ------------------------------------------------------------------
    // Public entry point: automatically segments far goals.
    // ------------------------------------------------------------------

    /**
     * Runs A* for the current goal. If the goal is beyond SEGMENT_RADIUS_BLOCKS,
     * it is temporarily snapped to a cell on the chunk boundary in the goal direction.
     * The caller (PathManager) will chain the next segment from wherever the player
     * arrives (standard queuing). Dead-end backtracking is handled inside search.
     */
    fun calculatePath(): Path? {
        // If the goal is outside our render zone, create an intermediate proxy goal
        val proxyGoal = computeProxyGoal()
        return if (proxyGoal != null) {
            searchWithDeadEndTracking(proxyGoal, isProxy = true)
        } else {
            searchWithDeadEndTracking(goal, isProxy = false)
        }
    }

    // ------------------------------------------------------------------
    // Proxy goal: snap far-away goals to the boundary of loaded area.
    // ------------------------------------------------------------------

    /**
     * Returns a Goal snapped to a cell on the edge of the 8-chunk search radius
     * in the direction of the real goal, or null when the goal is already close enough.
     */
    private fun computeProxyGoal(): Goal? {
        val dx = goal.goalX - startX
        val dz = goal.goalZ - startZ
        val dist = sqrt((dx * dx + dz * dz).toDouble())
        if (dist <= SEGMENT_RADIUS_BLOCKS.toDouble()) return null

        // Unit direction to goal
        val ux = dx / dist
        val uz = dz / dist

        // Walk from radius inward until we find a loaded standable cell.
        // We DO NOT require canWalkStraight here — if the proxy is behind a wall
        // A* will explore around it and we fall back to the closest explored node.
        var radius = SEGMENT_RADIUS_BLOCKS
        while (radius > 16) {
            val px = startX + (ux * radius).toInt()
            val pz = startZ + (uz * radius).toInt()
            if (isChunkLoaded(px, startY, pz)) {
                val snapped = snapToLoadedStandable(px, startY, pz)
                if (snapped != null) {
                    return Goal(snapped.x, snapped.y, snapped.z, ctx)
                }
            }
            radius -= 16
        }

        // Try findClosestUnblockedOnRay without canWalkStraight check
        var rayStep = 64
        while (rayStep >= 8) {
            val closest = try {
                BlockUtil.findClosestUnblockedOnRay(
                    ctx, startX, startY, startZ, ux, uz, SEGMENT_RADIUS_BLOCKS, rayStep
                )
            } catch (e: Exception) {
                null
            }
            if (closest != null) {
                return Goal(closest.x, closest.y, closest.z, ctx)
            }
            rayStep /= 2
        }

        // Absolute fallback: search full distance (caller will retry)
        return null
    }
    /** Find a standable cell in the loaded area around (x, y, z). */
    private fun snapToLoadedStandable(x: Int, y: Int, z: Int): BlockPos? {
        for (dy in 0..3) {
            for (dx in -2..2) {
                for (dz in -2..2) {
                    val cx = x + dx
                    val cz = z + dz
                    if (!isChunkLoaded(cx, y + dy, cz)) continue
                    try {
                        if (MovementHelper.canStandOn(ctx.bsa, cx, y + dy, cz, ctx.get(cx, y + dy, cz))
                            && MovementHelper.canWalkThrough(ctx.bsa, cx, y + dy + 1, cz, ctx.get(cx, y + dy + 1, cz))
                            && MovementHelper.canWalkThrough(ctx.bsa, cx, y + dy + 2, cz, ctx.get(cx, y + dy + 2, cz))) {
                            return BlockPos(cx, y + dy, cz)
                        }
                    } catch (e: Exception) {
                        // Partially loaded chunk (Missing Palette entry) — skip this cell.
                        continue
                    }
                }
            }
        }
        return null
    }

    // ------------------------------------------------------------------
    // Core A* search with dead-end detection
    // ------------------------------------------------------------------

    data class SearchResult(
        val path: Path?,
        val deadEndChunkX: Int?,
        val deadEndChunkZ: Int?
    )

    /**
     * Runs the A* search loop. When the open set is exhausted and the goal
     * is unreachable, records the last-visited chunk as a dead end so the
     * backtrack resolver can avoid it on a re-route.
     *
     * @param isProxy when true, the targetGoal is an artificial proxy and we
     *   fall back to the closest explored node to the **real** goal on exhaustion.
     */
    private fun searchWithDeadEndTracking(targetGoal: Goal, isProxy: Boolean = false): Path? {
        calculating = true
        iterations = 0
        closedSet.clear()
        deadEndCache.clear()
        lastTelemetry = SearchTelemetry(terminationReason = "running")

        val openSet = BinaryHeapOpenSet()
        val startNode = PathNode(startX, startY, startZ, targetGoal)
        val res = MovementResult()
        val moves = Moves.entries.toTypedArray()
        var expandedNodes = 0
        var openSetPeak = 0

        // Track the node closest to the real goal — used for partial-path fallback
        // when isProxy is true and the proxy is unreachable.
        var closestToRealNode: PathNode = startNode
        var closestRealHeuristic = goal.heuristic(startNode.x, startNode.y, startNode.z)

        val startCellBlocked = try {
            !MovementHelper.canWalkThrough(
                ctx.bsa, startX, startY + 1, startZ, ctx.get(startX, startY + 1, startZ)
            )
        } catch (e: Exception) {
            false
        }
        startNode.costSoFar = 0.0
        startNode.totalCost = startNode.costToEnd
        openSet.add(startNode)
        openSetPeak = maxOf(openSetPeak, openSet.size)

        // Track the last touched chunk for dead-end reporting
        var lastChunkX = startX shr 4
        var lastChunkZ = startZ shr 4
        var lastChunkEntryX = startX
        var lastChunkEntryZ = startZ
        var chunksEntered = 0

        while (!openSet.isEmpty() && calculating && iterations < maxIterations) {
            iterations++

            if (iterations % ITERATION_STEP != 0) continue

            val currentNode = openSet.poll()
            expandedNodes++

            // Track chunk entry for backtracking
            val chunkX = currentNode.x shr 4
            val chunkZ = currentNode.z shr 4
            if (chunkX != lastChunkX || chunkZ != lastChunkZ) {
                val dirX = chunkX - lastChunkX
                val dirZ = chunkZ - lastChunkZ
                deadEndCache.pushEntry(
                    BlockPos(currentNode.x, currentNode.y, currentNode.z),
                    dirX, dirZ
                )
                lastChunkX = chunkX
                lastChunkZ = chunkZ
                lastChunkEntryX = currentNode.x
                lastChunkEntryZ = currentNode.z
                chunksEntered++
            }

            // Skip expansions into known dead-end chunks
            if (deadEndCache.isDeadEnd(currentNode.x, currentNode.z)) {
                continue
            }

            // Update closest-to-real-goal tracking (when searching via a proxy)
            if (isProxy) {
                val h = goal.heuristic(currentNode.x, currentNode.y, currentNode.z)
                if (h < closestRealHeuristic) {
                    closestRealHeuristic = h
                    closestToRealNode = currentNode
                }
            }

            if (targetGoal.isAtGoal(currentNode.x, currentNode.y, currentNode.z)) {
                calculating = false
                lastTelemetry = SearchTelemetry(
                    iterations = iterations,
                    expandedNodes = expandedNodes,
                    openSetPeak = openSetPeak,
                    terminationReason = "goal_reached"
                )
                return Path(startNode, currentNode, targetGoal, ctx)
            }

            val expandingStart = currentNode === startNode
            for (move in moves) {
                res.reset()
                try {
                    move.calculate(ctx, currentNode.x, currentNode.y, currentNode.z, res)

                    if (expandingStart && startCellBlocked && res.y == startY
                        && !BlockUtil.canWalkStraight(ctx, ctx.startFeet, ctx.startFeet.y, BlockPos(res.x, res.y, res.z))
                    ) {
                        continue
                    }
                } catch (e: Exception) {
                    // Chunk may be partially loaded (Missing Palette entry) or unreadable
                    // from this worker thread — treat the neighbour as impassable.
                    continue
                }

                var cost = res.cost
                val isLoaded = isChunkLoaded(res.x, res.y, res.z)
                if (!isLoaded) {
                    cost = ctx.cost.INF_COST / 2
                }

                if (cost >= ctx.cost.INF_COST) continue

                val neighbourNode = getNode(res.x, res.y, res.z, PathNode.longHash(res.x, res.y, res.z))
                val neighbourCostSoFar = currentNode.costSoFar + cost

                if (neighbourNode.costSoFar > neighbourCostSoFar) {
                    neighbourNode.parentNode = currentNode
                    neighbourNode.costSoFar = neighbourCostSoFar
                    neighbourNode.totalCost = neighbourCostSoFar + neighbourNode.costToEnd

                    if (neighbourNode.heapPosition == -1) {
                        openSet.add(neighbourNode)
                    } else {
                        openSet.relocate(neighbourNode)
                    }
                    if (openSet.size > openSetPeak) {
                        openSetPeak = openSet.size
                    }
                }
            }
        }

        // If the open set was exhausted (goal unreachable), decide what to return.
        val terminationReason = when {
            !calculating -> "stop_requested"
            iterations >= maxIterations -> "max_iterations"
            else -> {
                // Dead end — mark it
                if (chunksEntered > 0) {
                    deadEndCache.markDeadEndAndBacktrack()
                }
                "open_set_exhausted"
            }
        }

        calculating = false
        lastTelemetry = SearchTelemetry(
            iterations = iterations,
            expandedNodes = expandedNodes,
            openSetPeak = openSetPeak,
            terminationReason = terminationReason
        )

        // --- Partial-path fallback ---
        // If we were searching via a proxy and couldn't reach it, return a path
        // to the closest node to the **real** goal that A* explored.
        if (isProxy && closestToRealNode !== startNode) {
            val partialPath = Path(startNode, closestToRealNode, targetGoal, ctx)
            Logger.sendLog(
                "Partial path: proxy unreachable, returning path to closest node " +
                "(" + closestToRealNode.x + ", " + closestToRealNode.y + ", " + closestToRealNode.z + ") " +
                "heuristic " + closestRealHeuristic
            )
            return partialPath
        }

        return null
    }

    // ------------------------------------------------------------------
    // Chunk helpers
    // ------------------------------------------------------------------

    private fun isChunkLoaded(x: Int, y: Int, z: Int): Boolean {
        val world = ctx.world ?: return false
        val pos = BlockPos(x, y, z)
        return world.isLoaded(pos) ||
                world.isLoaded(pos.offset(1, 0, 0)) ||
                world.isLoaded(pos.offset(-1, 0, 0)) ||
                world.isLoaded(pos.offset(0, 0, 1)) ||
                world.isLoaded(pos.offset(0, 0, -1))
    }

    fun getNode(x: Int, y: Int, z: Int, hash: Long): PathNode {
        var n: PathNode? = closedSet.get(hash)
        if (n == null) {
            n = PathNode(x, y, z, goal)
            closedSet.put(hash, n)
        }
        return n
    }

    fun requestStop() {
        if (!calculating) return
        calculating = false
    }
}
