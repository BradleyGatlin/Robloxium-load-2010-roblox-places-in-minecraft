package com.robloxium.mixin.client;

import com.robloxium.client.RobloxiumClient;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Disables Minecraft's distance/atmospheric fog while a Roblox level is active. */
@Mixin(FogRenderer.class)
public abstract class RobloxFogRendererMixin {
    @Shadow private static boolean fogEnabled;

    @Inject(method = "applyFog", at = @At("HEAD"))
    private void robloxium$disableMinecraftFog(CallbackInfo ci) {
        fogEnabled = !RobloxiumClient.HOST.game().running();
    }
}
