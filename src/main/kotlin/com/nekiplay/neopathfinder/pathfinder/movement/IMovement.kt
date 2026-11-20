package com.nekiplay.neopathfinder.pathfinder.movement

import com.nekiplay.neopathfinder.NeoPathfinder
import net.minecraft.core.BlockPos

interface IMovement {
    val mm: NeoPathfinder
    val source: BlockPos
    val dest: BlockPos
    val costs: Double // plural cuz kotlin gae

    fun getCost(): Double
    fun calculateCost(ctx: CalculationContext, res: MovementResult)
}
