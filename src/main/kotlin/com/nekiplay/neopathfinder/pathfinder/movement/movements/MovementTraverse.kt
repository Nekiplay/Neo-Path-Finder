package com.nekiplay.neopathfinder.pathfinder.movement.movements

import com.nekiplay.neopathfinder.NeoPathfinder
import com.nekiplay.neopathfinder.pathfinder.movement.CalculationContext
import com.nekiplay.neopathfinder.pathfinder.movement.Movement
import com.nekiplay.neopathfinder.pathfinder.movement.MovementHelper
import com.nekiplay.neopathfinder.pathfinder.movement.MovementResult
import net.minecraft.core.BlockPos

class MovementTraverse(mm: NeoPathfinder, from: BlockPos, to: BlockPos) : Movement(mm, from, to) {

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
            res.set(destX, y, destZ)
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
            if (!MovementHelper.canStandOn(ctx.bsa, destX, y, destZ)) return

            val destUpState = ctx.get(destX, y + 1, destZ)
            if (!MovementHelper.canWalkThrough(
                    ctx.bsa,
                    destX,
                    y + 1,
                    destZ,
                    destUpState
                ) || !MovementHelper.canWalkThrough(ctx.bsa, destX, y + 2, destZ)
            ) return

            val srcUpState = ctx.get(x, y + 1, z)

            val isSourceTopWalkableLadder = MovementHelper.canWalkIntoLadder(srcUpState, x - destX, z - destZ)
            val isDestTopWalkableLadder = MovementHelper.canWalkIntoLadder(destUpState, destX - x, destZ - z)
            if (MovementHelper.isLadder(destUpState) && !isDestTopWalkableLadder) {
                res.cost = ctx.cost.INF_COST
                return
            }
            if (MovementHelper.isLadder(srcUpState) && !isSourceTopWalkableLadder) {
                res.cost = ctx.cost.INF_COST
                return
            }
            val sourceState = ctx.get(x, y, z)
            val destState = ctx.get(destX, y, destZ)
            val stepX = destX - x
            val stepZ = destZ - z
            // The source is left from its collision top: a stair the player walks up is crossed
            // over its whole cell and stood on at the tall side. The destination is entered from
            // the low side when the stair faces the travel direction, so only half a block is
            // climbed there. Raw collision tops made every correctly oriented stair look like a
            // one-block jump.
            val sourceSurfaceY = y + MovementHelper.collisionMaxY(sourceState, ctx.world, BlockPos(x, y, z))
            val destSurfaceY = y + MovementHelper.walkSurfaceOffset(
                destState,
                ctx.world,
                BlockPos(destX, y, destZ),
                stepX,
                stepZ
            )
            val diff = destSurfaceY - sourceSurfaceY
            res.cost = when {
                diff <= 0.5 -> ctx.cost.ONE_BLOCK_SPRINT_COST
                diff <= 1 -> ctx.cost.JUMP_ONE_BLOCK_COST
                else -> ctx.cost.INF_COST
            }
        }
    }
}
