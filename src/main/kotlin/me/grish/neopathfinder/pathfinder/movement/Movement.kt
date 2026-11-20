package me.grish.neopathfinder.pathfinder.movement

import me.grish.neopathfinder.NeoPathfinder
import net.minecraft.core.BlockPos

abstract class Movement(override val mm: NeoPathfinder, override val source: BlockPos, override val dest: BlockPos) :
    IMovement {

    override var costs: Double = 1e6
    override fun getCost() = costs
}
