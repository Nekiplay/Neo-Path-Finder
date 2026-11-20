package com.nekiplay.neopathfinder;

import com.nekiplay.neopathfinder.command.PathCommand;
import com.nekiplay.neopathfinder.event.MotionUpdateEvent;
import com.nekiplay.neopathfinder.handler.RotationHandler;
import com.nekiplay.neopathfinder.pathfinder.PathManager;
import com.nekiplay.neopathfinder.util.Logger;
import com.nekiplay.neopathfinder.util.RenderUtil;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;

/**
 * Pathfinder-only client entry point.
 */
public class NeoPathfinderClient implements ClientModInitializer {

    public static final String MOD_ID = NeoPathfinder.MOD_ID;
    public static NeoPathfinderClient instance;

    public static Minecraft mc() {
        return Minecraft.getInstance();
    }

    @Override
    public void onInitializeClient() {
        instance = this;
        Logger.sendLog("Initializing NeoPathfinder (pathfinder-only)...");

        // Rotation handler receives server-side rotation packets.
        MotionUpdateEvent.register(RotationHandler.getInstance()::onMotionUpdate);

        // Tick pathfinding + path executor.
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.level == null || client.player == null) {
                return;
            }
            RotationHandler.getInstance().onTick();
            PathManager.getInstance().onClientTick();
        });

        // World render: path visualization + smooth rotation playback.
        LevelRenderEvents.COLLECT_SUBMITS.register(context -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null || mc.player == null) {
                return;
            }
            RenderUtil.beginWorldRender(context);
            RotationHandler.getInstance().onWorldRender(context);
            PathManager.getInstance().renderInWorld(context);
            RenderUtil.endWorldRender();
        });

        // Commands.
        ClientCommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess) -> PathCommand.register(dispatcher));

        Logger.sendLog("NeoPathfinder initialized!");
    }
}