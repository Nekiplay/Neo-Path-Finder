package com.nekiplay.neopathfinder.pathfinder.calculate

import com.nekiplay.neopathfinder.pathfinder.goal.Goal
import com.nekiplay.neopathfinder.pathfinder.movement.CalculationContext
import com.nekiplay.neopathfinder.pathfinder.util.BlockUtil
import com.nekiplay.neopathfinder.pathfinder.util.toVec3
import com.nekiplay.neopathfinder.pathfinder.util.world
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Blocks
import java.util.*

class Path(start: PathNode, end: PathNode, val goal: Goal, val ctx: CalculationContext) {
    var start: BlockPos = BlockPos(start.x, start.y, start.z)
    var end: BlockPos = BlockPos(end.x, end.y, end.z)
    var path: List<BlockPos>
    var node: List<PathNode>
    var smoothPath: List<BlockPos> = listOf()
    var smoothingDurationMs: Double = -1.0
        private set

    init {
        var temp: PathNode? = end
        val listOfBlocks = LinkedList<BlockPos>()
        val listOfNodes = LinkedList<PathNode>()
        while (temp != null) {
            listOfNodes.addFirst(temp)
            listOfBlocks.addFirst(BlockPos(temp.x, temp.y, temp.z))
            temp = temp.parentNode
        }
        path = listOfBlocks.toList()
        node = listOfNodes.toList()
    }

    /** Prepends the cell the player actually stands in, so the follower walks out of it first. */
    fun prependStart(pos: BlockPos) {
        if (path.isEmpty() || path[0] == pos) {
            return
        }
        path = listOf(pos) + path
        start = pos
        smoothPath = listOf()
    }

    // english is not my strong suit
    fun getSmoothedPath(): List<BlockPos> {
        if (smoothPath.isNotEmpty()) return smoothPath

        val startedNs = System.nanoTime()
        val smooth = LinkedList<BlockPos>()
        if (path.isNotEmpty()) {
            smooth.add(path[0])
            var currPoint = 0

            while (currPoint + 1 < path.size) {
                var nextPos = currPoint + 1

                for (i in (path.size - 1) downTo nextPos) {
                    if (BlockUtil.canWalkStraight(ctx, path[currPoint], path[i])) {
                        nextPos = i
                        break
                    }
                }
                smooth.add(path[nextPos])
                currPoint = nextPos
            }
        }
        smoothPath = smooth.toList()
        smoothingDurationMs = (System.nanoTime() - startedNs) / 1_000_000.0
        return smoothPath
    }

    fun reconstructPath(end: PathNode): List<BlockPos> {
        val path = mutableListOf<BlockPos>()
        var currentNode: PathNode? = end
        while (currentNode != null) {
            path.add(0, currentNode.getBlock())
            currentNode = currentNode.parentNode
        }

        val smooth = mutableListOf<BlockPos>()
        if (path.isNotEmpty()) {
            smooth.add(path[0])
            var currPoint = 0
            var maxiters = 2000

            while (currPoint + 1 < path.size && maxiters-- > 0) {
                var nextPos = currPoint + 1

                for (i in (path.size - 1) downTo nextPos) {
                    if (BlockUtil.canWalkStraight(ctx, path[currPoint], path[i])) {
                        nextPos = i
                        break
                    }
                }
                smooth.add(path[nextPos])
                currPoint = nextPos
            }
        }
        smooth.removeIf { world?.getBlockState(it)?.block == Blocks.AIR }
        return smooth
    }

}
