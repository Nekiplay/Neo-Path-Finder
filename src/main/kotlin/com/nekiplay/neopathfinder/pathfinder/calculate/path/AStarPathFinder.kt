package com.nekiplay.neopathfinder.pathfinder.calculate.path

import it.unimi.dsi.fastutil.longs.Long2ObjectMap
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
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


class AStarPathFinder(val startX: Int, val startY: Int, val startZ: Int, val goal: Goal, val ctx: CalculationContext) {
    companion object {
        /**
         * Upper bound on search loop passes. Far goals legitimately need hundreds of thousands of
         * expansions, so the default is generous and callers may raise it at runtime.
         */
        @JvmStatic
        var maxIterations = 2_000_000
        private const val ITERATION_STEP = 1
    }

    data class SearchTelemetry(
        val iterations: Int = 0,
        val expandedNodes: Int = 0,
        val openSetPeak: Int = 0,
        val terminationReason: String = "not_started"
    )

    private val closedSet: Long2ObjectMap<PathNode> = Long2ObjectOpenHashMap()
    private var calculating = false
    private var iterations = 0
    @Volatile
    private var lastTelemetry = SearchTelemetry()

    fun getLastTelemetry(): SearchTelemetry {
        return lastTelemetry
    }

    fun calculatePath(): Path? {
        calculating = true
        iterations = 0
        closedSet.clear()
        lastTelemetry = SearchTelemetry(terminationReason = "running")
        val openSet = BinaryHeapOpenSet()
        val startNode = PathNode(startX, startY, startZ, goal)
        val res = MovementResult()
        val moves = Moves.entries.toTypedArray()
        var expandedNodes = 0
        var openSetPeak = 0
        val startCellBlocked = !MovementHelper.canWalkThrough(
            ctx.bsa, startX, startY + 1, startZ, ctx.get(startX, startY + 1, startZ)
        )
        startNode.costSoFar = 0.0
        startNode.totalCost = startNode.costToEnd
        openSet.add(startNode)
        openSetPeak = maxOf(openSetPeak, openSet.size)

        while (!openSet.isEmpty() && calculating && iterations < maxIterations) {
            iterations++

            if (iterations % ITERATION_STEP != 0) continue

            val currentNode = openSet.poll()
            expandedNodes++

            if (goal.isAtGoal(currentNode.x, currentNode.y, currentNode.z)) {
                calculating = false
                lastTelemetry = SearchTelemetry(
                    iterations = iterations,
                    expandedNodes = expandedNodes,
                    openSetPeak = openSetPeak,
                    terminationReason = "goal_reached"
                )
                return Path(startNode, currentNode, goal, ctx)
            }
            val expandingStart = currentNode === startNode
            for (move in moves) {
                res.reset()
                move.calculate(ctx, currentNode.x, currentNode.y, currentNode.z, res)
                // The player can stand inside a fence cell (the post is only a quarter block wide),
                // so a same-level move out of such a start cell must be checked against the real
                // player position: the fence slab is not passable even though the cell looks free.
                if (expandingStart && startCellBlocked && res.y == startY
                    && !BlockUtil.canWalkStraight(ctx, ctx.startFeet, ctx.startFeet.y, BlockPos(res.x, res.y, res.z))
                ) {
                    continue
                }
                var cost = res.cost
                val isLoaded = isChunkLoaded(res.x, res.y, res.z)
                if (!isLoaded) {
                    cost = ctx.cost.INF_COST / 2 // Достаточно высокая стоимость, но путь возможен
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
        val terminationReason = when {
            !calculating -> "stop_requested"
            iterations >= maxIterations -> "max_iterations"
            else -> "open_set_exhausted"
        }
        calculating = false
        lastTelemetry = SearchTelemetry(
            iterations = iterations,
            expandedNodes = expandedNodes,
            openSetPeak = openSetPeak,
            terminationReason = terminationReason
        )


        // Important: never return a partial/closest path here.
        // Callers treat any non-null Path as successful completion to the requested goal.
        return null
    }

    private fun isChunkLoaded(x: Int, y: Int, z: Int): Boolean {
        return ctx.world?.let { world ->
            val pos = BlockPos(x, y, z)
            world.isLoaded(pos) ||
                    world.isLoaded(pos.offset(1, 0, 0)) ||
                    world.isLoaded(pos.offset(-1, 0, 0)) ||
                    world.isLoaded(pos.offset(0, 0, 1)) ||
                    world.isLoaded(pos.offset(0, 0, -1))
        } ?: false
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
