package com.nekiplay.neopathfinder.pathfinder;

import kotlin.Pair;
import com.nekiplay.neopathfinder.NeoPathfinder;
import com.nekiplay.neopathfinder.pathfinder.calculate.Path;
import com.nekiplay.neopathfinder.pathfinder.calculate.PathNode;
import com.nekiplay.neopathfinder.pathfinder.calculate.PathfindingTelemetry;
import com.nekiplay.neopathfinder.pathfinder.calculate.path.AStarPathFinder;
import com.nekiplay.neopathfinder.pathfinder.goal.Goal;
import com.nekiplay.neopathfinder.pathfinder.movement.CalculationContext;
import com.nekiplay.neopathfinder.util.Logger;
import com.nekiplay.neopathfinder.util.PlayerUtil;
import com.nekiplay.neopathfinder.util.RenderUtil;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.awt.*;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Owns the path request queue and drives {@link AStarPathFinder} + {@link PathExecutor}.
 * All work on the game state happens on the client thread; the search itself runs on
 * the NeoPathfinder worker pool.
 * <p>
 * External callers should normally use {@link com.nekiplay.neopathfinder.api.PathAPI}.
 */
public final class PathManager {

    private static final Minecraft mc = Minecraft.getInstance();
    private static PathManager instance;

    private final Deque<Pair<BlockPos, BlockPos>> pathQueue = new ConcurrentLinkedDeque<>();
    private final PathExecutor pathExecutor = PathExecutor.getInstance();
    private final List<Runnable> finishCallbacks = new CopyOnWriteArrayList<>();
    private final List<Runnable> failCallbacks = new CopyOnWriteArrayList<>();

    private AStarPathFinder finder;
    private volatile boolean skipTick = false;
    private volatile boolean pathfinding = false;
    private boolean enabled = false;
    private boolean failed = false;
    private boolean succeeded = false;
    private volatile PathfindingTelemetry lastTelemetry = null;

    private PathManager() {
    }

    public static PathManager getInstance() {
        if (instance == null) {
            instance = new PathManager();
        }
        return instance;
    }

    public void onClientTick() {
        this.onTick();
    }

    /* ------------------------------------------------------------------ */
    /* Queue management                                                    */
    /* ------------------------------------------------------------------ */

    public void queue(BlockPos end) {
        BlockPos start;
        if (this.pathQueue.isEmpty()) {
            if (this.pathExecutor.getCurrentPath() == null) {
                start = PlayerUtil.getBlockStandingOn();
            } else {
                start = this.pathExecutor.getCurrentPath().getEnd();
            }
        } else {
            start = this.pathQueue.peekLast().getSecond();
        }
        this.pathQueue.offer(new Pair<>(start, end));
    }

    public void queue(BlockPos start, BlockPos end) {
        if (!this.pathQueue.isEmpty() && !this.pathQueue.peekLast().getSecond().equals(start)) {
            Logger.sendError("[Pathfinder] This does not start at the end of the previous path. Ignoring!");
            return;
        }
        this.pathQueue.offer(new Pair<>(start, end));
    }

    public void clearQueue() {
        this.pathQueue.clear();
    }

    /**
     * @return queued destination points in traversal order.
     */
    public List<BlockPos> getQueuedTargets() {
        List<BlockPos> targets = new ArrayList<>();
        for (Pair<BlockPos, BlockPos> segment : this.pathQueue) {
            targets.add(segment.getSecond());
        }
        return targets;
    }

    /* ------------------------------------------------------------------ */
    /* Lifecycle                                                           */
    /* ------------------------------------------------------------------ */

    public boolean isRunning() {
        return this.enabled;
    }

    public boolean failed() {
        return !this.enabled && this.failed;
    }

    public boolean succeeded() {
        return !this.enabled && this.succeeded;
    }

    public PathfindingTelemetry getLastTelemetry() {
        return this.lastTelemetry;
    }

    public Path getCurrentPath() {
        return this.pathExecutor.getCurrentPath();
    }

    public void onFinish(Runnable callback) {
        this.finishCallbacks.add(callback);
    }

    public void onFail(Runnable callback) {
        this.failCallbacks.add(callback);
    }

    public void start() {
        if (this.pathQueue.isEmpty()) {
            Logger.sendError("[Pathfinder] Pathqueue is empty. Cannot start");
            return;
        }
        this.enabled = true;
        this.succeeded = false;
        this.failed = false;
        this.pathExecutor.start();
        Logger.sendMessage("[Pathfinder] Started");
    }

    public void stop() {
        this.stop("No explicit reason");
    }

    public void stop(String reason) {
        String stopReason = (reason == null || reason.trim().isEmpty()) ? "No explicit reason" : reason.trim();
        if (!mc.isSameThread()) {
            mc.execute(() -> this.stop(stopReason));
            return;
        }
        boolean fireFinish = this.succeeded;
        boolean fireFail = this.failed;

        this.enabled = false;
        this.pathfinding = false;
        this.skipTick = false;
        this.pathQueue.clear();
        this.resetStatesAfterStop();

        Logger.sendMessage("[Pathfinder] stopped (" + stopReason + ")");
        Logger.sendLog("stopped. reason: " + stopReason + ", executor: " + pathExecutor.getStopReason());

        if (fireFinish) {
            this.finishCallbacks.forEach(Runnable::run);
        } else if (fireFail) {
            this.failCallbacks.forEach(Runnable::run);
        }
        this.finishCallbacks.clear();
        this.failCallbacks.clear();
    }

    private void resetStatesAfterStop() {
        if (this.finder != null) {
            this.finder.requestStop();
        }
        this.pathExecutor.stop();
        com.nekiplay.neopathfinder.handler.RotationHandler.getInstance().stop();
    }

    private void stopOnClientThread(String reason) {
        String stopReason = (reason == null || reason.trim().isEmpty()) ? "No explicit reason" : reason.trim();
        if (mc.isSameThread()) {
            this.stop(stopReason);
            return;
        }
        mc.execute(() -> this.stop(stopReason));
    }

    /* ------------------------------------------------------------------ */
    /* Tick                                                                */
    /* ------------------------------------------------------------------ */

    private void onTick() {
        if (mc.player == null || mc.level == null) {
            if (this.enabled) {
                this.stop("Player or world is null");
            }
            return;
        }

        boolean okToPath = this.pathExecutor.onTick();

        // let the executor consume the freshly queued path
        if (this.skipTick) {
            this.skipTick = false;
            return;
        }

        if (this.pathExecutor.failed()) {
            String executorReason = this.pathExecutor.getStopReason();
            Logger.sendLog("pathexecutor failed. reason: " + executorReason);
            this.failed = true;
            this.stop("PathExecutor failed: " + executorReason);
            return;
        }

        if (!okToPath) {
            return;
        }

        if (this.pathQueue.isEmpty()) {
            if (this.pathExecutor.getState() == PathExecutor.State.WAITING && !this.pathfinding) {
                this.succeeded = true;
                this.stop("Completed path queue");
            }
            return;
        }

        if (this.pathfinding) {
            return;
        }

        NeoPathfinder.executor().execute(() -> {
            if (this.pathfinding) {
                return;
            }
            this.pathfinding = true;
            try {
                Pair<BlockPos, BlockPos> startEnd = this.pathQueue.poll();
                if (startEnd == null) {
                    this.pathfinding = false;
                    return;
                }

                long startedAtMs = System.currentTimeMillis();
                BlockPos start = startEnd.getFirst();
                BlockPos end = startEnd.getSecond();
                double walkSpeed = mc.player.getSpeed();
                CalculationContext ctx = new CalculationContext(walkSpeed * 1.3, walkSpeed, walkSpeed * 0.3);
                Goal goal = new Goal(end.getX(), end.getY(), end.getZ(), ctx);
                Path path;
                AStarPathFinder.SearchTelemetry searchTelemetry = null;
                double searchMs = 0.0;
                boolean sameHeightSegment = start.getY() == end.getY();
                boolean directWalk = false;
                if (sameHeightSegment && com.nekiplay.neopathfinder.pathfinder.util.BlockUtil.INSTANCE.canWalkBetween(ctx, start, end)) {
                    // Skip A* when the entire segment is directly traversable.
                    directWalk = true;
                    finder = null;
                    PathNode startNode = new PathNode(start.getX(), start.getY(), start.getZ(), goal);
                    PathNode endNode = new PathNode(end.getX(), end.getY(), end.getZ(), goal);
                    endNode.setParentNode(startNode);
                    path = new Path(startNode, endNode, goal, ctx);
                    Logger.sendLog("Skipping A*: direct walkable segment from " + start + " to " + end);
                } else {
                    long searchStartNs = System.nanoTime();
                    finder = new AStarPathFinder(start.getX(), start.getY(), start.getZ(), goal, ctx);
                    path = finder.calculatePath();
                    searchMs = (System.nanoTime() - searchStartNs) / 1_000_000.0;
                    searchTelemetry = finder.getLastTelemetry();
                    Logger.sendLog("done pathfinding");
                }
                if (path != null) {
                    List<BlockPos> smoothedPath = path.getSmoothedPath();
                    this.lastTelemetry = new PathfindingTelemetry(
                            startedAtMs,
                            System.currentTimeMillis(),
                            true,
                            "",
                            searchMs,
                            path.getSmoothingDurationMs(),
                            searchTelemetry != null ? searchTelemetry.getExpandedNodes() : 0,
                            searchTelemetry != null ? searchTelemetry.getOpenSetPeak() : 0,
                            searchTelemetry != null ? searchTelemetry.getIterations() : 0,
                            path.getPath().size(),
                            smoothedPath.size(),
                            directWalk
                    );
                    PathExecutor.getInstance().queuePath(path);
                } else {
                    Logger.sendLog("No Path Found");
                    failed = true;
                    this.lastTelemetry = new PathfindingTelemetry(
                            startedAtMs,
                            System.currentTimeMillis(),
                            false,
                            searchTelemetry != null ? searchTelemetry.getTerminationReason() : "no_path",
                            searchMs,
                            0.0,
                            searchTelemetry != null ? searchTelemetry.getExpandedNodes() : 0,
                            searchTelemetry != null ? searchTelemetry.getOpenSetPeak() : 0,
                            searchTelemetry != null ? searchTelemetry.getIterations() : 0,
                            0,
                            0,
                            directWalk
                    );
                    stopOnClientThread("No path found from " + start + " to " + end);
                }
            } catch (Exception e) {
                NeoPathfinder.LOGGER.error("Pathfinding task crashed", e);
                failed = true;
                this.lastTelemetry = new PathfindingTelemetry(
                        System.currentTimeMillis(),
                        System.currentTimeMillis(),
                        false,
                        "exception_" + e.getClass().getSimpleName(),
                        0.0,
                        0.0,
                        0,
                        0,
                        0,
                        0,
                        0,
                        false
                );
                stopOnClientThread("Pathfinding task crashed: " + e.getClass().getSimpleName());
            }
            this.pathfinding = false;
            this.skipTick = true;
        });
    }

    /* ------------------------------------------------------------------ */
    /* Rendering (path visualization)                                      */
    /* ------------------------------------------------------------------ */

    public void renderInWorld(LevelRenderContext context) {
        Deque<Path> paths = new LinkedList<>(this.pathExecutor.getPathQueue());
        if (this.pathExecutor.getCurrentPath() != null) {
            paths.add(this.pathExecutor.getCurrentPath());
        }

        if (paths.isEmpty()) {
            return;
        }

        RenderUtil.drawBlock(paths.getFirst().getStart(), new Color(0, 255, 0, 150));

        for (Path path : paths) {
            List<BlockPos> bpath = path.getSmoothedPath();
            for (int i = 1; i < bpath.size(); i++) {
                RenderUtil.drawBlock(bpath.get(i), new Color(0, 255, 0, 150));
                RenderUtil.drawThinLine(
                        new Vec3(bpath.get(i).getX() + 0.5, bpath.get(i).getY() + 1, bpath.get(i).getZ() + 0.5),
                        new Vec3(bpath.get(i - 1).getX() + 0.5, bpath.get(i - 1).getY() + 1, bpath.get(i - 1).getZ() + 0.5),
                        new Color(0, 255, 0, 150),
                        true
                );
            }
        }
    }

    public boolean completedPathTo(BlockPos pos) {
        Path prev = this.pathExecutor.getPreviousPath();
        return prev != null && prev.getGoal().isAtGoal(pos.getX(), pos.getY(), pos.getZ());
    }
}