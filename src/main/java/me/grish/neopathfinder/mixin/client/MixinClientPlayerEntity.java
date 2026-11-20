package me.grish.neopathfinder.mixin.client;

import me.grish.neopathfinder.event.MotionUpdateEvent;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin for ClientPlayerEntity to intercept rotation updates sent to the server.
 */
@Mixin(value = LocalPlayer.class, priority = Integer.MAX_VALUE)
public abstract class MixinClientPlayerEntity {

    @Unique
    private float neopathfinder$serverYaw = 0f;
    @Unique
    private float neopathfinder$serverPitch = 0f;

    /**
     * Fire MotionUpdateEvent before sending position/rotation to server.
     */
    @Inject(method = "sendPosition", at = @At("HEAD"))
    private void onSendMovementPacketsPre(CallbackInfo ci) {
        LocalPlayer self = (LocalPlayer) (Object) this;
        MotionUpdateEvent event = new MotionUpdateEvent(self.getYRot(), self.getXRot());
        MotionUpdateEvent.fire(event);
        this.neopathfinder$serverYaw = event.yaw;
        this.neopathfinder$serverPitch = event.pitch;
    }

    /**
     * Override yaw sent to server.
     */
    @Redirect(method = "sendPosition", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;getYRot()F"))
    private float overrideYaw(LocalPlayer instance) {
        return this.neopathfinder$serverYaw;
    }

    /**
     * Override pitch sent to server.
     */
    @Redirect(method = "sendPosition", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;getXRot()F"))
    private float overridePitch(LocalPlayer instance) {
        return this.neopathfinder$serverPitch;
    }
}