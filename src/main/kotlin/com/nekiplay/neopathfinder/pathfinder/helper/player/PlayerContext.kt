package com.nekiplay.neopathfinder.pathfinder.helper.player

import com.nekiplay.neopathfinder.util.PlayerUtil
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos

class PlayerContext(override val mc: Minecraft) : IPlayerContext {
    override val player get() = mc.player
    override val playerController get() = mc.gameMode
    override val world get() = mc.level

    // Block player is standing on
    override val playerPosition: BlockPos?
        get() {
            if (player == null) return null
            // Same definition as the pathfinder uses: thin snow/carpet covers are skipped.
            return PlayerUtil.getBlockStandingOn()
        }
}
