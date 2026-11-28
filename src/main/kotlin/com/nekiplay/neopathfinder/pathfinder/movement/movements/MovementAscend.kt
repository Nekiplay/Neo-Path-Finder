package com.nekiplay.neopathfinder.pathfinder.movement.movements

import com.nekiplay.neopathfinder.NeoPathfinder
import com.nekiplay.neopathfinder.pathfinder.movement.CalculationContext
import com.nekiplay.neopathfinder.pathfinder.movement.Movement
import com.nekiplay.neopathfinder.pathfinder.movement.MovementHelper
import com.nekiplay.neopathfinder.pathfinder.movement.MovementResult
import net.minecraft.core.BlockPos

class MovementAscend(mm: NeoPathfinder, from: BlockPos, to: BlockPos) : Movement(mm, from, to) {
    override fun calculateCost(ctx: CalculationContext, res: MovementResult) {
        calculateCost(ctx, source.x, source.y, source.z, dest.x, dest.z, res)
        costs = res.cost
    }

    companion object {
        fun calculateCost(
            ctx: CalculationContext,
            x: Int,
            y: Int,
            z: Int,
            destX: Int,
            destZ: Int,
            res: MovementResult
        ) {
            res.set(destX, y + 1, destZ)
            cost(ctx, x, y, z, destX, destZ, res)
        }

        private fun cost(
            ctx: CalculationContext,
            x: Int,
            y: Int,
            z: Int,
            destX: Int,
            destZ: Int,
            res: MovementResult
        ) {
            val destState = ctx.get(destX, y + 1, destZ)
            if (!MovementHelper.canStandOn(ctx.bsa, destX, y + 1, destZ, destState)) return
            if (!MovementHelper.canWalkThrough(ctx.bsa, destX, y + 3, destZ)) return
            if (!MovementHelper.canWalkThrough(ctx.bsa, destX, y + 2, destZ)) return
            if (!MovementHelper.canWalkThrough(ctx.bsa, x, y + 3, z)) return

            val sourceState = ctx.get(x, y, z)
            if (MovementHelper.isLadder(sourceState)) return
            if (MovementHelper.isLadder(destState) && !MovementHelper.canWalkIntoLadder(
                    destState,
                    destX - x,
                    destZ - z
                )
            ) return

            // small = half block / stair - in this case it doesnt matter which way the source stair(if it is a stair) is facing
            // big = fill block
            // this logic is actually fucking sick ngl

            val stepX = destX - x
            val stepZ = destZ - z
            // The source and the destination live in different cells, so the one-block cell offset
            // must be part of the comparison. Using the raw collision tops (destHeight - sourceHeight)
            // made a bottom slab (0.5) -> full block one level up (1.0) look like a 0.5 step, while
            // the real climb is 1.5 blocks and can never be jumped.
            val sourceSurfaceY = y + MovementHelper.collisionMaxY(sourceState, ctx.world, BlockPos(x, y, z))
            val destSurfaceY = (y + 1) + MovementHelper.walkSurfaceOffset(
                destState,
                ctx.world,
                BlockPos(destX, y + 1, destZ),
                stepX,
                stepZ
            )
            val rise = destSurfaceY - sourceSurfaceY
//      println("SourceSurfaceY: $sourceSurfaceY, DestSurfaceY: $destSurfaceY, Rise: $rise")
            res.cost = when {
                rise <= 0.5 -> ctx.cost.ONE_BLOCK_SPRINT_COST
                rise <= MovementHelper.MAX_JUMPABLE_RISE -> ctx.cost.JUMP_ONE_BLOCK_COST
                else -> ctx.cost.INF_COST
            }
        }
//    }
    }
}
