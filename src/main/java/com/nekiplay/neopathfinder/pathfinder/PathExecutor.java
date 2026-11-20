package com.nekiplay.neopathfinder.pathfinder;

import kotlin.Pair;
import lombok.Getter;
import lombok.Setter;
import com.nekiplay.neopathfinder.handler.RotationHandler;
import com.nekiplay.neopathfinder.pathfinder.calculate.Path;
import com.nekiplay.neopathfinder.pathfinder.movement.CalculationContext;
import com.nekiplay.neopathfinder.pathfinder.movement.MovementHelper;
import com.nekiplay.neopathfinder.pathfinder.util.BlockUtil;
import com.nekiplay.neopathfinder.util.*;
import com.nekiplay.neopathfinder.util.helper.Angle;
import com.nekiplay.neopathfinder.util.helper.Clock;
import com.nekiplay.neopathfinder.util.helper.RotationConfiguration;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class PathExecutor {

    // Constants for anti-cheat/human-like behavior
    private static final double FORWARD_KEY_RELEASE_PROBABILITY = 0.02;
    private static final int MAX_YAW_DIFF_FOR_SPRINT = 40;
    private static final int JUMP_DELAY_MIN = 80;
    private static final int JUMP_DELAY_RANDOM = 120;
    private static final double ROTATION_HUMAN_ERROR_FACTOR = 1.2;
    private static final double COLLISION_CHECK_DISTANCE = 0.7;
    private static final int SEGMENT_TIMEOUT_MS = 30000; // 30 seconds per path segment
    private static final double MIN_FORWARD_RISE = -0.05;
    private static final double MAX_JUMPABLE_RISE = 1.25;
    private static final double MAX_JUMP_PROBE_DISTANCE = 2.2;
    private static final double NODE_REACHED_HORIZONTAL_DIST = 0.7;
    private static final double NODE_REACHED_VERTICAL_TOLERANCE = 1.35;
    /**
     * Radius around the final node that retires a segment. Landing beside a waypoint is normal, and
     * demanding node-precision left the segment running so the next waypoint was never planned.
     */
    private static final double SEGMENT_COMPLETE_RADIUS = 2.0;
    private static final double SEGMENT_PROGRESS_SWITCH_THRESHOLD = 0.65;
    private static final double STUCK_SPEED_THRESHOLD = 0.05;
    private static final int STUCK_DETECTION_MS = 1000;
    private static final int TARGET_LOOKAHEAD = 3;
    /** A chained segment may start this far (blocks) from the previous goal and still be accepted. */
    private static final int STUCK_RECOVERY_TIMEOUT_MS = 700;
    /**
     * Ticks allowed inside a small area before the segment is planned again. Back-and-forth pacing
     * covers more than a tick's worth of distance, so the anchor is only moved by real progress.
     */
    private static final int NO_PROGRESS_TICKS_BEFORE_REPLAN = 50;
    /** Net displacement that counts as real progress; pacing in place never leaves this radius. */
    private static final double PROGRESS_RADIUS = 1.5;
    /** Falling this far below the next node means the rest of the path is stale. */
    private static final double FALL_OFF_PATH_DISTANCE = 1.6;
    /** How long the chosen steering node is kept, which stops the follower flipping between two. */
    private static final int STEERING_HOLD_TICKS = 5;
    /** How long a step back stays engaged once it starts. */
    private static final int RETREAT_HOLD_TICKS = 15;
    private static PathExecutor instance;
    private final Minecraft mc = Minecraft.getInstance();
    @Getter
    private final Deque<Path> pathQueue = new LinkedList<>();
    private final Map<Long, List<Long>> map = new HashMap<>();
    private final List<BlockPos> blockPath = new ArrayList<>();
    private final Clock stuckTimer = new Clock();
    private final Clock stuckRecoveryWindow = new Clock();
    private final Clock nodeSwitchDelay = new Clock();
    private final Clock jumpDelay = new Clock();
    private final Clock segmentTimeout = new Clock();
    private final List<Runnable> onFinishCallbacks = new ArrayList<>();
    private final List<Runnable> onFailCallbacks = new ArrayList<>();
    private final Random random = new Random();
    private final Clock dynamicPitch = new Clock();
    @Getter
    private boolean enabled = false;
    @Getter
    private String stopReason = "Not started";
    private Path prev;
    private Path curr;
    private boolean failed = false;
    private boolean succeeded = false;
    private boolean pastTarget = false;
    private boolean attemptedStuckRecovery = false;
    private boolean pendingStuckRecoveryJump = false;
    private double lastPitch = 10 + (15 - 10) * random.nextDouble();
    private int target = 0;
    private int moveTargetIndex = -1;
    private int retreatIndex = -1;
    private int steeringHoldTicks = 0;
    private int retreatHoldTicks = 0;
    private Vec3 progressSample = null;
    private int noProgressTicks = 0;
    private final AtomicBoolean replanRequested = new AtomicBoolean();
    private int previous = -1;
    private long nodeChangeTime = 0;

    private boolean interpolated = true;
    private float interpolYawDiff = 0f;

    @Getter
    private State state = State.STARTING_PATH;

    @Setter
    private boolean allowSprint = true;
    @Setter
    private boolean allowInterpolation = false;

    // Rotation easing profile (formerly debug config values).
    @Setter
    private boolean useFixedRotation = false;
    @Setter
    private long fixedRotationTime = 500;
    @Setter
    private float rotationDistanceMultiplier = 2f;

    public static PathExecutor getInstance() {
        if (instance == null) {
            instance = new PathExecutor();
        }
        return instance;
    }

    public void onFinish(Runnable callback) {
        onFinishCallbacks.add(callback);
    }

    public void onFail(Runnable callback) {
        onFailCallbacks.add(callback);
    }

    public void queuePath(Path path) {
        if (path.getPath().isEmpty()) {
            this.stopReason = "Rejected empty path segment";
            error("Path is empty");
            return;
        }

        BlockPos start = path.getStart();
        Path lastPath = (this.curr != null) ? this.curr : this.pathQueue.peekLast();

        if (lastPath != null && !lastPath.getGoal().isAtGoal(start.getX(), start.getY(), start.getZ())) {
            // Chained segments are planned from the player's live cell and validated against real
            // collisions by the manager, so a start that is not the previous goal cell is expected.
            // Rejecting it dropped the waypoint the manager had already taken off its queue.
            log("Segment starts at " + start + " instead of the previous goal " + lastPath.getGoal()
                    + "; chaining from the live cell");
        }

        this.pathQueue.offer(path);
    }

    public void start() {
        this.state = State.STARTING_PATH;
        this.enabled = true;
        this.stopReason = "Running";
        // A stale failure flag from a previous run turns every later stop() into a "failed" stop.
        this.failed = false;
        this.succeeded = false;
        this.replanRequested.set(false);
        this.progressSample = null;
        this.noProgressTicks = 0;
        this.steeringHoldTicks = 0;
        this.retreatHoldTicks = 0;
        this.moveTargetIndex = -1;
        this.retreatIndex = -1;
    }

    public void stop() {
        stop(this.stopReason);
    }

    public void stop(String reason) {
        if (reason != null && !reason.trim().isEmpty()) {
            this.stopReason = reason.trim();
        }

        this.enabled = false;
        this.pathQueue.clear();
        this.blockPath.clear();
        this.map.clear();
        this.curr = null;
        this.prev = null;
        this.target = 0;
        this.previous = -1;
        this.pastTarget = false;
        this.state = State.END;
        this.interpolYawDiff = 0f;
        this.allowSprint = true;
        this.allowInterpolation = false;
        this.nodeChangeTime = 0;
        this.interpolated = true;
        this.segmentTimeout.reset();
        this.jumpDelay.reset();
        this.stuckTimer.reset();
        this.stuckRecoveryWindow.reset();
        this.attemptedStuckRecovery = false;
        this.pendingStuckRecoveryJump = false;
        StrafeUtil.enabled = false;
        RotationHandler.getInstance().stop();
        KeyBindUtil.releaseAllExcept();
        log("stopped. reason: " + this.stopReason);

        // Execute callbacks
        if (this.succeeded) {
            onFinishCallbacks.forEach(Runnable::run);
        } else if (this.failed) {
            onFailCallbacks.forEach(Runnable::run);
        }
        onFinishCallbacks.clear();
        onFailCallbacks.clear();
    }

    public void clearQueue() {
        this.pathQueue.clear();
        this.curr = null;
        this.succeeded = true;
        this.failed = false;
        this.interpolated = false;
        this.target = 0;
        this.previous = -1;
    }

    public void clearQueuedPaths() {
        this.pathQueue.clear();
        this.succeeded = false;
        this.failed = false;
    }

    public boolean onTick() {
        if (!enabled) {
            return false;
        }

        double horizontalSpeed = Math.hypot(mc.player.getDeltaMovement().x, mc.player.getDeltaMovement().z);
        if (this.attemptedStuckRecovery && this.stuckRecoveryWindow.isScheduled() && this.stuckRecoveryWindow.passed()) {
            if (horizontalSpeed < STUCK_SPEED_THRESHOLD) {
                this.failed = true;
                this.succeeded = false;
                this.stop("Stuck recovery jump failed near " + PlayerUtil.getBlockStandingOn() + " (target index " + this.target + ")");
                return false;
            }
            this.attemptedStuckRecovery = false;
            this.pendingStuckRecoveryJump = false;
            this.stuckRecoveryWindow.reset();
        }

        if (this.stuckTimer.isScheduled() && this.stuckTimer.passed()) {
            if (!this.attemptedStuckRecovery) {
                this.attemptedStuckRecovery = true;
                this.pendingStuckRecoveryJump = true;
                this.stuckRecoveryWindow.schedule(STUCK_RECOVERY_TIMEOUT_MS);
                this.stuckTimer.reset();
                log("Was stuck for a second. Attempting one recovery jump.");
            } else {
                log("Was Stuck For a Second.");
                this.failed = true;
                this.succeeded = false;
                this.stop("Stuck for " + STUCK_DETECTION_MS + "ms near " + PlayerUtil.getBlockStandingOn() + " (target index " + this.target + ")");
                return false;
            }
        }

        if (this.segmentTimeout.isScheduled() && this.segmentTimeout.passed()) {
            error("Path segment timed out after " + SEGMENT_TIMEOUT_MS + "ms");
            this.failed = true;
            this.succeeded = false;
            this.stop("Path segment timeout after " + SEGMENT_TIMEOUT_MS + "ms (target index " + this.target + ")");
        }

        BlockPos playerPos = PlayerUtil.getBlockStandingOn();
        if (this.curr != null) {
            // this is utterly useless but im useless as well
            List<Long> blockHashes = this.map.get(this.pack(playerPos.getX(), playerPos.getZ()));
            int current = -1;
            if (blockHashes != null && !blockHashes.isEmpty()) {
                int bestY = -1;
                double playerY = mc.player.getY();
                for (Long blockHash : blockHashes) {
                    Pair<Integer, Integer> block = this.unpack(blockHash);
                    int blockY = block.getFirst();
                    int blockTarget = block.getSecond();
                    if (blockTarget > this.previous) {
                        if (bestY == -1 || (blockY < playerY && blockY > bestY) || (blockY >= playerY && blockY < bestY)) {
                            bestY = block.getFirst();
                            current = blockTarget;
                        }
                    }
                }
            }

            if (current != -1 && current > previous) {
                this.previous = current;
                this.target = current + 1;
                this.state = State.TRAVERSING;
                this.pastTarget = false;
                this.interpolated = false;
                this.interpolYawDiff = 0;
                this.nodeChangeTime = System.currentTimeMillis();
                log("changed target from " + this.previous + " to " + this.target);
                RotationHandler.getInstance().stop();
            }

            if (horizontalSpeed < STUCK_SPEED_THRESHOLD) {
                if (!this.stuckTimer.isScheduled()) {
                    this.stuckTimer.schedule(STUCK_DETECTION_MS);
                }
            } else {
                this.stuckTimer.reset();
                this.attemptedStuckRecovery = false;
                this.pendingStuckRecoveryJump = false;
                this.stuckRecoveryWindow.reset();
            }
        } else {
            if (this.stuckTimer.isScheduled()) {
                this.stuckTimer.reset();
            }
            this.attemptedStuckRecovery = false;
            this.pendingStuckRecoveryJump = false;
            this.stuckRecoveryWindow.reset();
            if (this.pathQueue.isEmpty()) {
                return true;
            }
        }

        if (this.curr == null || this.target == this.blockPath.size()) {
            log("Path traversed");
            if (this.pathQueue.isEmpty()) {
                log("Pathqueue is empty");
                if (this.curr != null) {
                    this.curr = null;
                    this.target = 0;
                    this.previous = -1;
                }
                this.state = State.WAITING;
                this.stopReason = "Path queue drained; waiting";
                this.failed = false;
                return true;
            }
            this.succeeded = true;
            this.failed = false;
            this.prev = this.curr;
            this.target = 1;
            this.previous = 0;
            loadPath(this.pathQueue.poll());
            if (this.target == this.blockPath.size()) {
                return true;
            }
            log("loaded new path target: " + this.target + ", prev: " + this.previous);
        }

        BlockPos target = this.blockPath.get(this.target);
        Vec3 playerPosVec = mc.player.position();
        double horizontalDistToCurrent = Math.hypot(playerPosVec.x - target.getX() - 0.5, playerPosVec.z - target.getZ() - 0.5);
        double verticalDistToCurrent = Math.abs(mc.player.getY() - target.getY());
        boolean closeToCurrentNode = horizontalDistToCurrent <= NODE_REACHED_HORIZONTAL_DIST
                && verticalDistToCurrent <= NODE_REACHED_VERTICAL_TOLERANCE;

        if (this.target < this.blockPath.size() - 1) {
            BlockPos nextTarget = this.blockPath.get(this.target + 1);
            Vec3 currentCenter = new Vec3(target.getX() + 0.5, 0.0, target.getZ() + 0.5);
            Vec3 nextCenter = new Vec3(nextTarget.getX() + 0.5, 0.0, nextTarget.getZ() + 0.5);
            Vec3 segment = nextCenter.subtract(currentCenter);
            double segmentLengthSqr = segment.x * segment.x + segment.z * segment.z;
            boolean passedCurrentNode = false;
            if (segmentLengthSqr > 1.0E-6) {
                Vec3 playerFromCurrent = new Vec3(playerPosVec.x, 0.0, playerPosVec.z).subtract(currentCenter);
                double projectedProgress = (playerFromCurrent.x * segment.x + playerFromCurrent.z * segment.z) / segmentLengthSqr;
                passedCurrentNode = projectedProgress >= SEGMENT_PROGRESS_SWITCH_THRESHOLD;
            }
            this.pastTarget = passedCurrentNode;

            if ((closeToCurrentNode || passedCurrentNode) && nodeSwitchDelay.passed()) {
                this.previous = this.target;
                this.target++;
                nodeSwitchDelay.schedule(50 + random.nextInt(70));
                target = this.blockPath.get(this.target);
                log("advanced to next target (" + (closeToCurrentNode ? "close" : "segment progress") + ")");
            }
        }

        // Final node: the segment is finished once the player is close to the goal. Without this the
        // last node is only ever retired by the block-heuristic scan above, which requires the player
        // to stand exactly inside the goal cell, and a near miss leaves the segment running forever.
        // Height is not required to match: the follower often lands beside or just below the goal,
        // and the next segment is planned from wherever the player really is. The radius is
        // Chebyshev, the same metric Goal.isAtGoal uses, so a path that ended two cells diagonally
        // away (the goal-tolerance retry) still counts as arriving.
        BlockPos segmentGoal = this.blockPath.get(this.blockPath.size() - 1);
        double goalDx = Math.abs(mc.player.getX() - (segmentGoal.getX() + 0.5));
        double goalDz = Math.abs(mc.player.getZ() - (segmentGoal.getZ() + 0.5));
        if (this.target >= this.blockPath.size() - 1
                && mc.player.onGround()
                && Math.max(goalDx, goalDz) <= SEGMENT_COMPLETE_RADIUS) {
            this.previous = this.target;
            this.target = this.blockPath.size();
            log("reached final node " + horizontalDistToCurrent + " blocks from the segment goal, segment complete");
        }

        // Two moves alternating for seconds still covers distance every tick, so progress is measured
        // as the net displacement from an anchor that only moves when the player really gets
        // somewhere. A fresh plan from here also gets the run going again after being pushed into a
        // fence, stuck on a step it cannot climb, or after falling off the route.
        Vec3 nowPos = mc.player.position();
        boolean fellOffPath = this.target < this.blockPath.size()
                && nowPos.y < this.blockPath.get(this.target).getY() - FALL_OFF_PATH_DISTANCE;
        if (this.progressSample == null || nowPos.distanceTo(this.progressSample) > PROGRESS_RADIUS) {
            this.progressSample = nowPos;
            this.noProgressTicks = 0;
        } else if (fellOffPath || ++this.noProgressTicks >= NO_PROGRESS_TICKS_BEFORE_REPLAN) {
            this.noProgressTicks = 0;
            this.progressSample = nowPos;
            this.steeringHoldTicks = 0;
            this.retreatHoldTicks = 0;
            this.retreatIndex = -1;
            this.moveTargetIndex = -1;
            log((fellOffPath ? "Dropped below the path at " + nowPos.y + ", " : "No progress, ")
                    + "asking for a fresh plan from here");
            this.replanRequested.set(true);
        }

        boolean onGround = mc.player.onGround();

        // Only steer at a node the player can actually walk to in a straight line; otherwise aim at
        // the furthest visible node ahead. Without this the follower pushes straight into fences and
        // wall corners that the path itself goes around.
        CalculationContext ctx = this.curr.getCtx();
        Vec3 playerFeet = mc.player.position();
        int chosenIndex = Math.min(this.target, this.blockPath.size() - 1);
        boolean previousChoiceUsable = this.moveTargetIndex > this.target
                && this.moveTargetIndex < this.blockPath.size()
                && BlockUtil.INSTANCE.canWalkStraight(ctx, playerFeet, playerFeet.y, this.blockPath.get(this.moveTargetIndex));
        if (this.steeringHoldTicks > 0) {
            // Re-evaluating the lookahead every tick made the follower alternate between two equally
            // valid nodes, which produced seconds of left/back shuffling at a fixed rhythm.
            this.steeringHoldTicks--;
            if (previousChoiceUsable) {
                chosenIndex = this.moveTargetIndex;
            }
        } else {
            int lookaheadEnd = Math.min(this.target + TARGET_LOOKAHEAD, this.blockPath.size() - 1);
            for (int i = lookaheadEnd; i > this.target; i--) {
                BlockPos candidate = this.blockPath.get(i);
                if (BlockUtil.INSTANCE.canWalkStraight(ctx, playerFeet, playerFeet.y, candidate)) {
                    chosenIndex = i;
                    break;
                }
            }
            if (previousChoiceUsable) {
                chosenIndex = this.moveTargetIndex;
            }
            this.steeringHoldTicks = STEERING_HOLD_TICKS;
        }
        if (this.target >= this.blockPath.size()) {
            chosenIndex = this.blockPath.size() - 1;
        }
        this.moveTargetIndex = chosenIndex;
        BlockPos movementTarget = this.blockPath.get(chosenIndex);
        // Stepping back is only for the case where the player is embedded in a thin obstacle (a fence
        // post, a wall corner) and therefore sees no direct line to the next node. Standing next to
        // one must never trigger it.
        boolean embedded = BlockUtil.INSTANCE.isStandingCellBlocked(ctx, PlayerUtil.getBlockStandingOn());
        // Hold the step back for a moment so it cannot be re-decided on every tick, which is the other
        // way this follower used to shuffle back and forth in place.
        if (this.retreatHoldTicks > 0) {
            this.retreatHoldTicks--;
        } else if (!embedded) {
            this.retreatIndex = -1;
        }
        boolean retreatActive = embedded || this.retreatHoldTicks > 0 || this.retreatIndex >= 0;
        if (retreatActive && chosenIndex == this.target && !BlockUtil.INSTANCE.canWalkStraight(ctx, playerFeet, playerFeet.y, target)) {
            // Nothing ahead is reachable in a straight line: step back into the node behind us (this
            // is the cell the path started from, e.g. after escaping a fence cell) and stay on it
            // until it is reached, so the follower does not jitter between the two.
            if (this.retreatIndex < 0) {
                for (int i = 0; i < this.target; i++) {
                    if (BlockUtil.INSTANCE.canWalkStraight(ctx, playerFeet, playerFeet.y, this.blockPath.get(i))) {
                        this.retreatIndex = i;
                        break;
                    }
                }
            }
            if (this.retreatIndex >= 0 && this.retreatIndex < this.blockPath.size()) {
                this.retreatHoldTicks = RETREAT_HOLD_TICKS;
                BlockPos retreat = this.blockPath.get(this.retreatIndex);
                double retreatDist = Math.hypot(mc.player.getX() - retreat.getX() - 0.5, mc.player.getZ() - retreat.getZ() - 0.5);
                if (retreatDist <= NODE_REACHED_HORIZONTAL_DIST + 0.15
                        || !BlockUtil.INSTANCE.canWalkStraight(ctx, playerFeet, playerFeet.y, retreat)) {
                    this.retreatIndex = -1;
                } else {
                    movementTarget = retreat;
                    this.moveTargetIndex = this.retreatIndex;
                    this.retreatHoldTicks = RETREAT_HOLD_TICKS;
                }
            }
        } else {
            this.retreatIndex = -1;
        }

        int targetX = movementTarget.getX();
        int targetZ = movementTarget.getZ();
        double horizontalDistToTarget = Math.hypot(mc.player.getX() - targetX - 0.5, mc.player.getZ() - targetZ - 0.5);
        float yaw = AngleUtil.getRotationYaw360(mc.player.position(), new Vec3(targetX + 0.5, 0.0, targetZ + 0.5));
        float yawDiff = Math.abs(AngleUtil.get360RotationYaw() - yaw);

        if (this.interpolYawDiff == 0) {
            this.interpolYawDiff = yaw - AngleUtil.get360RotationYaw();
        }
        // Disable StrafeUtil for realistic client-side movement
        StrafeUtil.enabled = false;

        // Rotate player to face target direction
        if (yawDiff > 3 && !RotationHandler.getInstance().isEnabled()) {
            float rotYaw = yaw;

            // look at a block thats at least 5 blocks away instead of looking at the target which helps reduce buggy rotation
            for (int i = this.target; i < this.blockPath.size(); i++) {
                BlockPos rotationTarget = this.blockPath.get(i);
                if (Math.hypot(mc.player.getX() - rotationTarget.getX(), mc.player.getZ() - rotationTarget.getZ()) > 5) {
                    rotYaw = AngleUtil.getRotation(rotationTarget).getYaw(); // Fixed getYaw
                    float humanError = (float) (random.nextGaussian() * ROTATION_HUMAN_ERROR_FACTOR);
                    rotYaw += humanError;
                    break;
                }
            }

            float time = this.useFixedRotation ? this.fixedRotationTime : Math.max(220, (long) (360 - horizontalDistToTarget * this.rotationDistanceMultiplier));

            if (!dynamicPitch.isScheduled() || dynamicPitch.passed()) {
                lastPitch = 10 + (15 - 10) * random.nextDouble();
                dynamicPitch.schedule(1000);
            }

            // TODO: Implement back route miner
            RotationHandler.getInstance().easeTo(
                    new RotationConfiguration(
                            new Angle(rotYaw, (float) lastPitch),
                            (long) time, null
                    )
            );
        }

        // Calculate which WASD keys to press based on current player rotation (not target direction)
        // This makes movement purely client-side and realistic
        Vec3 targetVec = new Vec3(targetX + 0.5, mc.player.getY(), targetZ + 0.5);
        List<KeyMapping> neededKeys = KeyBindUtil.getNeededKeyPresses(mc.player.position(), targetVec);
        List<KeyMapping> keyBindings = new ArrayList<>(neededKeys);

        // Preserve attack/use item state
        if (mc.options.keyUse.isDown()) {
            keyBindings.add(mc.options.keyUse);
        }
        if (mc.options.keyAttack.isDown()) {
            keyBindings.add(mc.options.keyAttack);
        }

        // Jump only when path requires a one-block step-up and landing space is valid.
        boolean shouldJump = shouldJumpOneBlock(playerPos, movementTarget, horizontalDistToTarget);
        boolean recoveryJumping = false;
        if (shouldJump && onGround && (!this.jumpDelay.isScheduled() || this.jumpDelay.passed())) {
            keyBindings.add(mc.options.keyJump);
            this.jumpDelay.schedule(JUMP_DELAY_MIN + random.nextInt(JUMP_DELAY_RANDOM));
            this.state = State.JUMPING;
        }
        if (this.pendingStuckRecoveryJump && onGround && (!this.jumpDelay.isScheduled() || this.jumpDelay.passed())) {
            keyBindings.add(mc.options.keyJump);
            this.jumpDelay.schedule(JUMP_DELAY_MIN + random.nextInt(JUMP_DELAY_RANDOM));
            this.pendingStuckRecoveryJump = false;
            recoveryJumping = true;
            this.state = State.JUMPING;
            log("Issued stuck recovery jump.");
        }

        // Apply all the calculated key presses
        KeyBindUtil.holdThese(keyBindings.toArray(new KeyMapping[0]));

        // Handle sprinting - only sprint when moving relatively straight
        KeyBindUtil.setKeyBindState(mc.options.keySprint, this.allowSprint && yawDiff < MAX_YAW_DIFF_FOR_SPRINT && !shouldJump && !recoveryJumping);

        return mc.player.position().distanceToSqr(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5) < 100;
    }

    /**
     * Consumes the pending "plan this segment again" request raised by the progress watchdog.
     */
    public boolean takeReplanRequest() {
        return this.replanRequested.getAndSet(false);
    }

    public void requestReplan() {
        this.replanRequested.set(true);
    }

    /**
     * True when the executor has no path loaded and nothing pre-queued, i.e. the next queued
     * waypoint segment may be planned from the player's current position.
     */
    public boolean isIdle() {
        return this.curr == null && this.pathQueue.isEmpty();
    }

    public void loadPath(Path path) {
        this.blockPath.clear();
        this.map.clear();

        this.curr = path;
        this.blockPath.addAll(this.curr.getSmoothedPath());
        for (int i = 0; i < this.blockPath.size(); i++) {
            BlockPos pos = this.blockPath.get(i);
            this.map.computeIfAbsent(this.pack(pos.getX(), pos.getZ()), k -> new ArrayList<>()).add(this.pack(pos.getY(), i));
        }
    }

    public void onRender() {
        if (this.target != -1 && this.target < this.blockPath.size()) {
            BlockPos playerPos = PlayerUtil.getBlockStandingOn();
            BlockPos target = this.blockPath.get(this.target);
            int targetX = target.getX();
            int targetZ = target.getZ();
            Vec3 playerPosVec = mc.player.position();
            float yaw = AngleUtil.getRotationYaw360(playerPosVec, new Vec3(targetX + 0.5, 0.0, targetZ + 0.5));
            Vec3 pos = new Vec3(playerPosVec.x, playerPos.getY() + 0.5, playerPosVec.z);
            Vec3 vec4Rot = AngleUtil.getVectorForRotation(yaw);
            // Keep marker just above the player's feet; this is a delta, not an absolute Y.
            Vec3 newV = pos.add(vec4Rot.x, +1, vec4Rot.z);
            RenderUtil.drawBlock(new BlockPos((int) newV.x, (int) newV.y, (int) newV.z), // BlockPos.ofFloored check?
                    new Color(255, 0, 0, 255));
            RenderUtil.drawBlock(playerPos, new Color(255, 255, 0, 100));
        }
    }

    public Path getPreviousPath() {
        return this.prev;
    }

    public Path getCurrentPath() {
        return this.curr;
    }

    public boolean failed() {
        return !this.enabled && this.failed;
    }

    public boolean ended() {
        return !this.enabled && this.succeeded;
    }

    private boolean shouldJumpOneBlock(BlockPos playerPos, BlockPos targetPos, double horizontalDistToTarget) {
        if (this.curr == null) {
            return false;
        }

        CalculationContext ctx = this.curr.getCtx();
        if (shouldJumpTowardTarget(ctx, playerPos, targetPos, horizontalDistToTarget)) {
            return true;
        }

        if (this.target < this.blockPath.size() - 1) {
            BlockPos nextTarget = this.blockPath.get(this.target + 1);
            double horizontalDistToNext = Math.hypot(mc.player.getX() - nextTarget.getX() - 0.5, mc.player.getZ() - nextTarget.getZ() - 0.5);
            return shouldJumpTowardTarget(ctx, playerPos, nextTarget, horizontalDistToNext);
        }

        return false;
    }

    private boolean shouldJumpTowardTarget(CalculationContext ctx, BlockPos playerPos, BlockPos desiredTarget, double horizontalDist) {
        if (horizontalDist > MAX_JUMP_PROBE_DISTANCE) {
            return false;
        }

        int stepX = Integer.compare(desiredTarget.getX(), playerPos.getX());
        int stepZ = Integer.compare(desiredTarget.getZ(), playerPos.getZ());
        if (stepX == 0 && stepZ == 0) {
            return false;
        }

        return shouldJumpForDirection(ctx, playerPos, stepX, stepZ);
    }

    private boolean shouldJumpForDirection(CalculationContext ctx, BlockPos playerPos, int stepX, int stepZ) {
        if (stepX == 0 && stepZ == 0) {
            return false;
        }

        int landingX = playerPos.getX() + stepX;
        int landingZ = playerPos.getZ() + stepZ;
        int playerY = playerPos.getY();
        double sourceSurfaceY = getSurfaceY(ctx, playerPos.getX(), playerY, playerPos.getZ(), stepX, stepZ);

        double bestRise = Double.POSITIVE_INFINITY;
        for (int y = playerY - 1; y <= playerY + 2; y++) {
            if (!isStandableWithHeadroom(ctx, landingX, y, landingZ)) {
                continue;
            }

            double destSurfaceY = getSurfaceY(ctx, landingX, y, landingZ, stepX, stepZ);
            double rise = destSurfaceY - sourceSurfaceY;
            if (rise < MIN_FORWARD_RISE || rise > MAX_JUMPABLE_RISE) {
                continue;
            }
            bestRise = Math.min(bestRise, rise);
        }

        if (bestRise == Double.POSITIVE_INFINITY) {
            return false;
        }
        // Compare against the live step allowance instead of a fixed number: anything the player
        // can step onto in vanilla (snow layers, carpets, ...) must not schedule a jump here.
        return bestRise > MovementHelper.INSTANCE.stepHeight();
    }

    private boolean isStandableWithHeadroom(CalculationContext ctx, int x, int y, int z) {
        if (!MovementHelper.INSTANCE.canStandOn(ctx.getBsa(), x, y, z, ctx.get(x, y, z))) {
            return false;
        }
        if (!MovementHelper.INSTANCE.canWalkThrough(ctx.getBsa(), x, y + 1, z, ctx.get(x, y + 1, z))) {
            return false;
        }
        return MovementHelper.INSTANCE.canWalkThrough(ctx.getBsa(), x, y + 2, z, ctx.get(x, y + 2, z));
    }

    private double getSurfaceY(CalculationContext ctx, int x, int y, int z, int stepX, int stepZ) {
        BlockState state = ctx.get(x, y, z);
        double surface = y + getSurfaceOffset(ctx, state, x, y, z, stepX, stepZ);
        // Add the height of a cover the player steps over without jumping (snow layer, carpet, ...).
        // Without it the rise of such a step is overestimated and the follower schedules a jump.
        BlockPos coverPos = new BlockPos(x, y + 1, z);
        BlockState coverState = ctx.get(x, y + 1, z);
        double coverTop = MovementHelper.INSTANCE.collisionMaxY(coverState, ctx.getWorld(), coverPos);
        if (coverTop > 0.0 && coverTop <= MovementHelper.INSTANCE.stepHeight()) {
            surface = Math.max(surface, y + 1 + coverTop);
        }
        return surface;
    }

    private double getSurfaceOffset(CalculationContext ctx, BlockState state, int x, int y, int z, int stepX, int stepZ) {
        if (state.getBlock() instanceof SlabBlock || state.getBlock() instanceof StairBlock) {
            return MovementHelper.INSTANCE.hasTop(state, stepX, stepZ) ? 1.0 : 0.5;
        }

        double maxY = MovementHelper.INSTANCE.collisionMaxY(state, ctx.getWorld(), new BlockPos(x, y, z));
        return Math.max(0.0, maxY);
    }

    private long pack(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    public Pair<Integer, Integer> unpack(long packed) {
        return new Pair<>((int) (packed >> 32), (int) packed);
    }

    void log(String message) {
        Logger.sendLog(getMessage(message));
    }

    void send(String message) {
        Logger.sendMessage(getMessage(message));
    }

    void error(String message) {
        Logger.sendError(getMessage(message));
    }

    void note(String message) {
        Logger.sendNote(getMessage(message));
    }

    String getMessage(String message) {
        return "[PathExecutor] " + message;
    }

    public enum State {
        STARTING_PATH, TRAVERSING, JUMPING, WAITING, END
    }
}
