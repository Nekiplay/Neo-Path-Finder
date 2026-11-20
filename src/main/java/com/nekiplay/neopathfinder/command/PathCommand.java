package com.nekiplay.neopathfinder.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.nekiplay.neopathfinder.api.PathAPI;
import com.nekiplay.neopathfinder.pathfinder.calculate.PathfindingTelemetry;
import com.nekiplay.neopathfinder.util.Logger;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;

import java.util.List;
import java.util.Locale;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

/**
 * {@code /path x y z} — walk to the given coordinates.
 * Also exposes {@code /path add}, {@code /path stop}, {@code /path clear}, {@code /path list}, {@code /path stats}.
 */
public final class PathCommand {

    private PathCommand() {
    }

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        LiteralArgumentBuilder<FabricClientCommandSource> root = literal("path")
                .executes(context -> {
                    BlockPos current = PathAPI.getCurrentTarget();
                    Logger.sendMessage("[Path] running=" + PathAPI.isRunning()
                            + ", queued=" + PathAPI.getWaypoints().size()
                            + (current == null ? "" : ", target=" + current.getX() + " " + current.getY() + " " + current.getZ()));
                    return 1;
                })
                .then(literal("stop").executes(context -> {
                    PathAPI.stop("Stopped by command");
                    Logger.sendMessage("[Path] Stopped.");
                    return 1;
                }))
                .then(literal("clear").executes(context -> {
                    PathAPI.clearWaypoints();
                    Logger.sendMessage("[Path] Waypoint queue cleared.");
                    return 1;
                }))
                .then(literal("list").executes(context -> {
                    List<BlockPos> waypoints = PathAPI.getWaypoints();
                    if (waypoints.isEmpty()) {
                        Logger.sendMessage("[Path] Queue is empty.");
                        return 1;
                    }
                    Logger.sendMessage("[Path] Queue (" + waypoints.size() + "):");
                    for (int i = 0; i < waypoints.size(); i++) {
                        BlockPos pos = waypoints.get(i);
                        Logger.sendMessage("  " + (i + 1) + ". " + pos.getX() + " " + pos.getY() + " " + pos.getZ());
                    }
                    return 1;
                }))
                .then(literal("stats").executes(context -> {
                    PathfindingTelemetry telemetry = PathAPI.getLastTelemetry();
                    if (telemetry == null) {
                        Logger.sendWarning("[Path] No telemetry recorded yet.");
                        return 1;
                    }
                    Logger.sendMessage(String.format(Locale.ROOT,
                            "[Path] success=%s reason=%s search_ms=%.2f smoothing_ms=%.2f expanded=%d iterations=%d length=%d",
                            telemetry.success(),
                            telemetry.failureReason().isEmpty() ? "-" : telemetry.failureReason(),
                            telemetry.searchMs(),
                            telemetry.smoothingMs(),
                            telemetry.expandedNodes(),
                            telemetry.iterations(),
                            telemetry.smoothedPathLength()));
                    return 1;
                }))
                .then(literal("add").then(coordinates((x, y, z) -> {
                    PathAPI.addWaypoint(x, y, z);
                    Logger.sendMessage("[Path] Waypoint added: " + x + " " + y + " " + z);
                })))
                .then(literal("goto").then(coordinates((x, y, z) -> {
                    PathAPI.goTo(x, y, z);
                    Logger.sendMessage("[Path] Going to " + x + " " + y + " " + z);
                })))
                // bare form: /path x y z
                .then(coordinates((x, y, z) -> {
                    PathAPI.goTo(x, y, z);
                    Logger.sendMessage("[Path] Going to " + x + " " + y + " " + z);
                }));

        dispatcher.register(root);
    }

    private static ArgumentBuilder<FabricClientCommandSource, ?> coordinates(TargetConsumer consumer) {
        return argument("x", DoubleArgumentType.doubleArg())
                .then(argument("y", DoubleArgumentType.doubleArg())
                        .then(argument("z", DoubleArgumentType.doubleArg())
                                .executes(context -> {
                                    int x = Mth.floor(DoubleArgumentType.getDouble(context, "x"));
                                    int y = Mth.floor(DoubleArgumentType.getDouble(context, "y"));
                                    int z = Mth.floor(DoubleArgumentType.getDouble(context, "z"));
                                    consumer.accept(x, y, z);
                                    return 1;
                                })));
    }

    @FunctionalInterface
    private interface TargetConsumer {
        void accept(int x, int y, int z);
    }
}