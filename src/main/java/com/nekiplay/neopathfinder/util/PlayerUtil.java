package com.nekiplay.neopathfinder.util;

import com.nekiplay.neopathfinder.pathfinder.movement.MovementHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.entity.projectile.hurtingprojectile.LargeFireball;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

public class PlayerUtil {

    private static final Minecraft mc = Minecraft.getInstance();

    /** Offset keeping a feet position exactly on a surface inside that surface's block. */
    private static final double SURFACE_EPSILON = 1.0E-3;
    /** Safety bound while stepping down through empty-but-colliding covers. */
    private static final int MAX_COVER_LAYERS = 4;
    /**
     * Block the player is effectively standing on. Thin covers that vanilla walks over without a
     * jump (snow layers, carpets, ...) are skipped: their collision top is compared against the
     * live step height, so the returned node is the real supporting block. No elevation numbers
     * are hardcoded here.
     */
    public static BlockPos getBlockStandingOn() {
        Vec3 pos = mc.player.position();
        int x = Mth.floor(pos.x);
        int z = Mth.floor(pos.z);
        int y = Mth.floor(pos.y - SURFACE_EPSILON);

        double stepHeight = MovementHelper.INSTANCE.stepHeight();
        for (int i = 0; i < MAX_COVER_LAYERS; i++) {
            BlockPos candidate = new BlockPos(x, y, z);
            BlockState state = mc.level.getBlockState(candidate);
            if (state.isAir()) {
                break;
            }
            double coverTop = MovementHelper.INSTANCE.collisionMaxY(state, mc.level, candidate);
            if (coverTop <= 0.0 || coverTop > stepHeight) {
                break;
            }
            y--;
        }
        return new BlockPos(x, y, z);
    }

    public static Vec3 getPlayerEyePos() {
        return mc.player.getEyePosition();
    }

    public static BlockPos getBlockStandingOnFloor() {
        return BlockPos.containing(mc.player.getX(), Math.floor(mc.player.getY()) - 1, mc.player.getZ());
    }

    public static Vec3 getNextTickPosition() {
        return mc.player.position().add(mc.player.getDeltaMovement().x, 0, mc.player.getDeltaMovement().z);
    }

    public static Vec3 getNextTickPosition(float mult) {
        return mc.player.position().add(mc.player.getDeltaMovement().x * mult, 0, mc.player.getDeltaMovement().z * mult);
    }

    public static Entity getEntityCuttingOtherEntity(Entity e) {
        return getEntityCuttingOtherEntity(e, entity -> true);
    }

    public static Entity getEntityCuttingOtherEntity(Entity e, Predicate<Entity> predicate) {
        List<Entity> possible = mc.level.getEntities(e, e.getBoundingBox().inflate(0.3D, 2.0D, 0.3D), a -> {
            boolean flag1 = (a.isAlive() && !a.equals(mc.player));
            boolean flag2 = !(a instanceof LargeFireball);
            boolean flag3 = !(a instanceof FishingHook);
            boolean flag4 = predicate.test(a);
            return flag1 && flag2 && flag3 && flag4;
        });
        if (!possible.isEmpty()) {
            return Collections.min(possible, Comparator.comparing(e2 -> e2.distanceTo(e)));
        }
        return null;
    }

    public static boolean isPlayerSuffocating() {
        AABB playerBB = mc.player.getBoundingBox().inflate(-0.15, -0.15, -0.15);
        return mc.level.getBlockCollisions(mc.player, playerBB).iterator().hasNext();
    }

    public static Direction getHorizontalFacing(float yaw) {
        return Direction.fromYRot(yaw);
    }
}
