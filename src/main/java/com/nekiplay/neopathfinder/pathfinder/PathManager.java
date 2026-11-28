package com.nekiplay.neopathfinder.pathfinder;

import kotlin.Pair;
import com.nekiplay.neopathfinder.NeoPathfinder;
import com.nekiplay.neopathfinder.pathfinder.cache.DeadEndCache;
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
    /** Cells around the goal accepted when the exact cell is unreachable. */
    private static final int GOAL_TOLERANCE = 2;
    /** Replans of one segment allowed before the run is failed instead of looping forever. */
    private static final int MAX_REPLANS_PER_SEGMENT = 6;
    /** Distance from the segment goal at which a replan request counts as the goal being reached. */
    private static final double SEGMENT_GOAL_RADIUS = 2.0;
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
    private int replanAttempts = 0;
    /** Shared dead-end cache across search retries for the current segment. */
    private DeadEndCache deadEndCache = new DeadEndCache();
    /** Destination of the segment currently being planned or walked; used to replan it in place. */
    private volatile BlockPos currentSegmentEnd = null;
    /** Minimum Chebyshev distance to the segment end ever observed (for overshoot detection). */
    private volatile double closestToSegmentEnd = Double.MAX_VALUE;
    /** Waypoints queued by the last start(), only used for progress reporting. */
    /** Waypoints queued by the last start(), only used for progress reporting. */
    private int totalWaypoints = 0;
    /** The final destination of the entire run, carried across segments for fallback planning. */
    private BlockPos finalDestination = null;
    /**
     * True while the segment planned for the last queued waypoint is still being walked. Without
     * it the queue looked empty the moment that segment was queued, so the run reported success
     * and stopped the executor before the player had walked it.
     */
    private volatile boolean finalSegmentPending = false;

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
        // Canonicalize the destination the same way start nodes are built: a goal placed on top of
        // a snow layer must resolve to the supporting block, otherwise the path ends one block
        // higher and the follower treats the last step as a jump.
        this.pathQueue.offer(new Pair<>(start, PlayerUtil.canonicalizeStandingPos(end)));
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
        this.replanAttempts = 0;
        this.currentSegmentEnd = null;
        this.closestToSegmentEnd = Double.MAX_VALUE;
        this.deadEndCache = new DeadEndCache();
        this.finalDestination = this.pathQueue.peekLast().getSecond();
        this.finalSegmentPending = false;
        this.totalWaypoints = this.pathQueue.size();
        this.pathExecutor.start();
        Logger.sendLog("Started with " + this.totalWaypoints + " waypoint(s): " + this.getQueuedTargets());
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
        this.finalSegmentPending = false;
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
            try {
                this.finder.requestStop();
            } catch (Exception e) {
                NeoPathfinder.LOGGER.warn("Failed to stop the running search", e);
            }
            this.finder = null;
        }
        try {
            this.pathExecutor.stop();
        } finally {
            // Stuck keys and a stale rendered path are worse than a second release call.
            com.nekiplay.neopathfinder.util.KeyBindUtil.releaseAllExcept();
            com.nekiplay.neopathfinder.handler.RotationHandler.getInstance().stop();
        }
    }

    /**
     * Drops the segment being walked and plans it again from where the player actually is. Raised by
     * the executor when it spends seconds alternating between two moves instead of getting anywhere;
     * after a fall or a push into a fence the fresh plan simply routes around the new position.
     */
    private void replanCurrentSegment() {
        BlockPos segmentEnd = this.currentSegmentEnd;
        if (segmentEnd == null) {
            this.stop("Replan requested without a goal");
            return;
        }
        if (++this.replanAttempts > MAX_REPLANS_PER_SEGMENT) {
            this.failed = true;
            this.stop("Gave up after " + MAX_REPLANS_PER_SEGMENT + " replans of one segment");
            return;
        }
        BlockPos liveStart = PlayerUtil.getPathStartPos();
        BlockPos destination = segmentEnd;
        // Chebyshev, matching Goal.isAtGoal: the goal-tolerance retry can end two cells diagonally
        // away, which is far in Euclidean terms but is an arrived waypoint.
        double goalDistance = Math.max(Math.abs(liveStart.getX() - destination.getX()),
                Math.abs(liveStart.getZ() - destination.getZ()));
        if (goalDistance <= SEGMENT_GOAL_RADIUS) {
            // Already beside the segment goal: it is reached, so move on instead of replanning a
            // segment that has nothing left to walk.
            Logger.sendLog("No progress near " + liveStart + ", but it is within " + goalDistance
                    + " blocks of the segment goal. Treating " + destination + " as reached");
            this.replanAttempts = 0;
            this.currentSegmentEnd = null;
            this.pathExecutor.stop();
            this.pathExecutor.start();
            return;
        }
        Logger.sendLog("No progress near " + liveStart + ", replanning segment to " + destination
                + " (attempt " + this.replanAttempts + "/" + MAX_REPLANS_PER_SEGMENT + ")");
        this.pathExecutor.stop();
        this.pathExecutor.start();
        this.deadEndCache = new DeadEndCache();
        this.pathQueue.addFirst(new Pair<>(liveStart, destination));
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

        if (!this.enabled) {
            // Nothing is running, but the executor keeps its last stop reason and failure flag;
            // ticking it here made every later tick re-report a failure that was already handled.
            return;
        }

        PlayerUtil.tickGroundedPos();

        boolean okToPath = this.pathExecutor.onTick();

        if (this.finalSegmentPending && this.pathExecutor.isIdle()) {
            // The last planned segment has been walked and drained: only now is the run complete.
            this.finalSegmentPending = false;
        }

        // Overshoot detection: if the player was close to the segment end and now goes further
        // away, treat the segment as reached (high-speed overshoot).
        BlockPos currentEnd = this.currentSegmentEnd;
        if (currentEnd != null && mc.player != null) {
            BlockPos playerCell = PlayerUtil.getPathStartPos();
            double chebDist = Math.max(
                    Math.abs(playerCell.getX() - currentEnd.getX()),
                    Math.abs(playerCell.getZ() - currentEnd.getZ())
            );
            if (chebDist < this.closestToSegmentEnd) {
                this.closestToSegmentEnd = chebDist;
            } else if (this.closestToSegmentEnd <= SEGMENT_GOAL_RADIUS
                    && chebDist > SEGMENT_GOAL_RADIUS * 3) {
                // Was within goal radius, now far away — player overshot the segment end.
                Logger.sendLog("Overshot segment end " + currentEnd + " (closest "
                        + this.closestToSegmentEnd + ", now " + chebDist + "), marking reached");
                this.closestToSegmentEnd = Double.MAX_VALUE;
                this.currentSegmentEnd = null;
                this.pathExecutor.stop();
                this.pathExecutor.start();
                return;
            }
        }
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

        if (this.pathExecutor.takeReplanRequest()) {
            this.replanCurrentSegment();
            return;
        }

        if (this.pathQueue.isEmpty() && !this.finalSegmentPending) {
            if (!this.pathfinding && this.pathExecutor.isIdle()) {
                // If the player has not reached the overall final destination, queue a new
                // segment from the current position toward it instead of declaring success.
                if (this.finalDestination != null && mc.player != null) {
                    BlockPos playerCell = PlayerUtil.getPathStartPos();
                    boolean atFinal = Math.max(
                            Math.abs(playerCell.getX() - finalDestination.getX()),
                            Math.abs(playerCell.getZ() - finalDestination.getZ())
                    ) <= SEGMENT_GOAL_RADIUS;
                    if (!atFinal) {
                        Logger.sendLog("Queue empty but final destination " + finalDestination
                                + " not yet reached (player at " + playerCell + "); re-queuing via proxy");
                        this.pathQueue.add(new Pair<>(playerCell, finalDestination));
                        return;
                    }
                }
                Logger.sendMessage("[Pathfinder] Path complete: all " + this.totalWaypoints + " waypoint(s) reached");
                this.succeeded = true;
                this.stop("Completed path queue");
            }
            return;
        }

        if (this.pathfinding) {
            return;
        }

        if (!this.pathExecutor.isIdle()) {
            // The previous segment is still being walked. Planning the next one now would start it
            // wherever the player happens to be, which is far from the segment's own start.
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
                // Waypoints are chained, but the follower never lands exactly on the previous
                // destination (one cell of drift is normal). Planning from the stale chained start
                // builds a path that begins behind the player, and steering at it walks into walls.
                BlockPos requestedStart = startEnd.getFirst();
                BlockPos liveStart = PlayerUtil.getPathStartPos();
                if (!liveStart.equals(requestedStart)) {
                    Logger.sendLog("Chained segment requested from " + requestedStart + ", replanning from " + liveStart);
                }
                BlockPos start = liveStart;
                BlockPos end = startEnd.getSecond();
                this.currentSegmentEnd = end;
                this.closestToSegmentEnd = Double.MAX_VALUE;
                int remaining = this.pathQueue.size();
                Logger.sendMessage("[Pathfinder] Waypoint " + (this.totalWaypoints - remaining) + "/"
                        + this.totalWaypoints + " reached, " + remaining + " more queued. Planning "
                        + start + " -> " + end);
                double walkSpeed = mc.player.getSpeed();
                CalculationContext ctx = new CalculationContext(walkSpeed * 1.3, walkSpeed, walkSpeed * 0.3);
                BlockPos standingCell = start;
                BlockPos escapedStart = com.nekiplay.neopathfinder.pathfinder.util.BlockUtil.INSTANCE.escapeStartCell(ctx, standingCell);
                if (escapedStart != null) {
                    Logger.sendLog("Standing cell " + standingCell + " is partially blocked, escaping via " + escapedStart);
                    start = escapedStart;
                }
                BlockPos snappedEnd = com.nekiplay.neopathfinder.pathfinder.util.BlockUtil.INSTANCE.snapGoal(ctx, end);
                if (!snappedEnd.equals(end)) {
                    Logger.sendLog("Goal " + end + " is not standable, snapping to " + snappedEnd);
                    end = snappedEnd;
                }
                Goal goal = new Goal(end.getX(), end.getY(), end.getZ(), ctx);
                Path path;
                AStarPathFinder.SearchTelemetry searchTelemetry = null;
                double searchMs = 0.0;
                boolean sameHeightSegment = start.getY() == end.getY();
                boolean directWalk = false;
                if (sameHeightSegment
                        && com.nekiplay.neopathfinder.pathfinder.util.BlockUtil.INSTANCE.canWalkBetween(ctx, start, end)
                        && com.nekiplay.neopathfinder.pathfinder.util.BlockUtil.INSTANCE.canWalkStraight(ctx, start, end)) {
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
                    if (path == null) {
                        // The exact goal cell can genuinely be unreachable (a ledge, a cell behind an
                        // obstacle). Retry accepting any node next to it instead of failing outright.
                        for (int tolerance = GOAL_TOLERANCE; tolerance <= GOAL_TOLERANCE * 2 && path == null; tolerance += GOAL_TOLERANCE) {
                            goal.setTolerance(tolerance);
                            finder = new AStarPathFinder(start.getX(), start.getY(), start.getZ(), goal, ctx);
                            path = finder.calculatePath();
                            Logger.sendLog("Retried search with goal tolerance " + tolerance + ": "
                                    + (path != null ? path.getPath().size() + " nodes" : "still nothing"));
                        }
                    }
                    if (path == null) {
                        // Still no path — try a fresh search from a slightly different start
                        // (the dead-end cache inside the finder already tracked chunk dead-ends
                        // during the first pass; a new finder clears that so a reroute may find
                        // an alternate path).
                        BlockPos shiftedStart = com.nekiplay.neopathfinder.pathfinder.util.BlockUtil.INSTANCE
                                .findClosestUnblocked(ctx, start, 3);
                        if (shiftedStart != null && !shiftedStart.equals(start)) {
                            Logger.sendLog("Dead-end, retrying from shifted start " + shiftedStart);
                            goal.setTolerance(0);
                            finder = new AStarPathFinder(
                                    shiftedStart.getX(), shiftedStart.getY(), shiftedStart.getZ(), goal, ctx);
                            path = finder.calculatePath();
                            if (path != null) {
                                // Prepend original start so executor walks to the shifted cell first
                                path.prependStart(start);
                            }
                        }
                    }
                    searchMs = (System.nanoTime() - searchStartNs) / 1_000_000.0;
                    var lastTel = finder != null ? finder.getLastTelemetry() : null;
                    searchTelemetry = lastTel;
                    Logger.sendLog("done pathfinding");
                }
                if (path != null && !start.equals(standingCell)) {
                    // The search started one cell away (the player had to step out of an obstacle),
                    // so keep the real starting cell as the first node of the executed path.
                    path.prependStart(standingCell);
                }
                if (path != null) {
                    List<BlockPos> smoothedPath = path.getSmoothedPath();
                    String violation = com.nekiplay.neopathfinder.pathfinder.util.BlockUtil.INSTANCE.pathViolation(ctx, smoothedPath, true);
                    if (violation == null) {
                        violation = com.nekiplay.neopathfinder.pathfinder.util.BlockUtil.INSTANCE.pathViolation(ctx, path.getPath(), true);
                    }
                    if (violation != null) {
                        Logger.sendWarning("[Pathfinder] Segment " + standingCell + " -> " + end
                                + " rejected by the collision check: " + violation);
                        path = null;
                    }
                }
                if (path != null) {
                    // Snap every node to integer coordinates so that half-slab targets never
                    // produce a fractional step the executor cannot reach.
                    List<BlockPos> rawPath = path.getPath();
                    for (int i = 0; i < rawPath.size(); i++) {
                        BlockPos p = rawPath.get(i);
                        rawPath.set(i, new BlockPos(p.getX(), p.getY(), p.getZ()));
                    }
                    // Re-smooth after snapping.
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
                    // A freshly planned segment gets its own replan budget.
                    this.replanAttempts = 0;
                    this.finalSegmentPending = this.pathQueue.isEmpty();
                    PathExecutor.getInstance().queuePath(path);
                    Logger.sendMessage("[Pathfinder] Walking segment of " + path.getPath().size()
                            + " nodes to " + end + ", " + this.pathQueue.size() + " waypoint(s) left");
                } else {
                    Logger.sendError("[Pathfinder] No path found from " + standingCell + " to " + end
                            + " (the collision check and the search both failed)");
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