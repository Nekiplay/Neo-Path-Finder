package me.grish.neopathfinder.pathfinder.movement

import me.grish.neopathfinder.NeoPathfinder
import net.minecraft.core.BlockPos

interface IMovement {
    val mm: NeoPathfinder
    val source: BlockPos
    val dest: BlockPos
    val costs: Double // plural cuz kotlin gae

    fun getCost(): Double
    fun calculateCost(ctx: CalculationContext, res: MovementResult)
}
