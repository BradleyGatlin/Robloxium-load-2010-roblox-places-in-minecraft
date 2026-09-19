package com.robloxium.mixin.client;

import com.robloxium.client.RobloxiumClient;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Disables Minecraft distance fog while a Roblox place is running. */
@Mixin(FogRenderer.class)
public abstract class RobloxFogRendererMixin {
    @Shadow
    private static boolean fogEnabled;

    @Inject(method = "setupFog", at = @At("HEAD"))
    private void robloxium$disableMinecraftFog(
            Camera camera,
            int renderDistanceChunks,
            DeltaTracker deltaTracker,
            float darkenAmount,
            ClientLevel level,
            CallbackInfoReturnable<FogData> cir) {
        fogEnabled = !RobloxiumClient.HOST.game().running();
    }

    @Inject(method = "setupFog", at = @At("RETURN"))
    private void robloxium$pushFogOut(
            Camera camera,
            int renderDistanceChunks,
            DeltaTracker deltaTracker,
            float darkenAmount,
            ClientLevel level,
            CallbackInfoReturnable<FogData> cir) {
        if (!RobloxiumClient.HOST.game().running()) {
            return;
        }
        FogData fog = cir.getReturnValue();
        if (fog == null) {
            return;
        }
        fog.environmentalStart = 1.0e6f;
        fog.renderDistanceStart = 1.0e6f;
        fog.environmentalEnd = 1.0e7f;
        fog.renderDistanceEnd = 1.0e7f;
        fog.skyEnd = 1.0e7f;
        fog.cloudEnd = 1.0e7f;
    }
}
