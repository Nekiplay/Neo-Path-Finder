package me.grish.neopathfinder.api;

import me.grish.neopathfinder.pathfinder.PathExecutor;
import me.grish.neopathfinder.pathfinder.PathManager;
import me.grish.neopathfinder.pathfinder.calculate.PathfindingTelemetry;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * Public static API for the NeoPathfinder pathfinder.
 * <p>
 * Typical usage:
 * <pre>{@code
 * PathAPI.addWaypoint(100, 70, -20);
 * PathAPI.addWaypoint(120, 70, -20);
 * PathAPI.start();
 * }</pre>
 * or a single call:
 * <pre>{@code
 * PathAPI.goTo(new BlockPos(100, 70, -20));
 * }</pre>
 * All methods are static and thread-safe: calls from worker threads are re-scheduled
 * onto the client thread.
 */
public final class PathAPI {

    private PathAPI() {
    }

    /* ------------------------------------------------------------------ */
    /* Movement                                                            */
    /* ------------------------------------------------------------------ */

    /**
     * Stops any running path, clears the waypoint queue and walks to the given block.
     */
    public static void goTo(BlockPos pos) {
        if (pos == null) {
            return;
        }
        onClient(() -> {
            PathManager manager = PathManager.getInstance();
            if (manager.isRunning()) {
                manager.stop("Retargeting");
            }
            manager.clearQueue();
            manager.queue(pos);
            manager.start();
        });
    }

    /**
     * Stops any running path, clears the waypoint queue and walks to the given coordinates.
     */
    public static void goTo(int x, int y, int z) {
        goTo(new BlockPos(x, y, z));
    }

    /**
     * Appends a waypoint to the end of the current queue.
     * The pathfinder chains segments: each new point starts where the previous one ends.
     */
    public static void addWaypoint(BlockPos pos) {
        if (pos == null) {
            return;
        }
        onClient(() -> PathManager.getInstance().queue(pos));
    }

    /**
     * Appends a waypoint to the end of the current queue.
     */
    public static void addWaypoint(int x, int y, int z) {
        addWaypoint(new BlockPos(x, y, z));
    }

    /**
     * Starts executing the queued waypoints. No-op with an error message when the queue is empty.
     */
    public static void start() {
        onClient(() -> PathManager.getInstance().start());
    }

    /**
     * Stops pathfollowing and clears the queue.
     */
    public static void stop() {
        onClient(() -> PathManager.getInstance().stop("Stopped by API"));
    }

    /**
     * Stops pathfollowing and clears the queue with an explicit reason.
     */
    public static void stop(String reason) {
        onClient(() -> PathManager.getInstance().stop(reason));
    }

    /**
     * Clears queued waypoints without stopping the current segment.
     */
    public static void clearWaypoints() {
        onClient(() -> PathManager.getInstance().clearQueue());
    }

    /* ------------------------------------------------------------------ */
    /* State                                                               */
    /* ------------------------------------------------------------------ */

    public static boolean isRunning() {
        return PathManager.getInstance().isRunning();
    }

    public static boolean isPathing() {
        return PathExecutor.getInstance().isEnabled();
    }

    public static boolean hasWaypoints() {
        return !getWaypoints().isEmpty();
    }

    /**
     * @return queued destination points, in traversal order.
     */
    public static List<BlockPos> getWaypoints() {
        return new ArrayList<>(PathManager.getInstance().getQueuedTargets());
    }

    /**
     * @return the block the executor is currently walking toward, or {@code null}.
     */
    public static BlockPos getCurrentTarget() {
        PathExecutor executor = PathExecutor.getInstance();
        return executor.getCurrentPath() == null ? null : executor.getCurrentPath().getEnd();
    }

    /**
     * @return the block the executor last reached, or {@code null}.
     */
    public static BlockPos getCurrentPosition() {
        PathExecutor executor = PathExecutor.getInstance();
        return executor.getCurrentPath() == null ? null : executor.getCurrentPath().getStart();
    }

    public static PathfindingTelemetry getLastTelemetry() {
        return PathManager.getInstance().getLastTelemetry();
    }

    /* ------------------------------------------------------------------ */
    /* Behaviour                                                           */
    /* ------------------------------------------------------------------ */

    /**
     * Allows or forbids sprinting while following the path.
     */
    public static void setSprintAllowed(boolean allowed) {
        PathExecutor.getInstance().setAllowSprint(allowed);
    }

    /**
     * Rotation easing profile used while turning toward the next path node.
     *
     * @param fixed          use a constant rotation time instead of distance-based easing
     * @param fixedTimeMs    rotation time when {@code fixed} is true
     * @param distanceFactor distance multiplier for the adaptive rotation time
     */
    public static void setRotationProfile(boolean fixed, long fixedTimeMs, float distanceFactor) {
        PathExecutor executor = PathExecutor.getInstance();
        executor.setUseFixedRotation(fixed);
        executor.setFixedRotationTime(fixedTimeMs);
        executor.setRotationDistanceMultiplier(distanceFactor);
    }

    /**
     * Called once when the queued path completes successfully.
     */
    public static void onFinish(Runnable callback) {
        PathManager.getInstance().onFinish(callback);
    }

    /**
     * Called once when the queued path fails (no path, stuck, timeout).
     */
    public static void onFail(Runnable callback) {
        PathManager.getInstance().onFail(callback);
    }

    /* ------------------------------------------------------------------ */

    private static void onClient(Runnable action) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.isSameThread()) {
            action.run();
        } else {
            mc.execute(action);
        }
    }
}