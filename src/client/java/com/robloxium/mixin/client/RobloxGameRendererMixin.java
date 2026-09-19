package com.robloxium.mixin.client;

import com.robloxium.client.RobloxPartRenderer;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Releases Robloxium caches when the game renderer shuts down. */
@Mixin(GameRenderer.class)
public abstract class RobloxGameRendererMixin {
    @Inject(method = "close", at = @At("RETURN"))
    private void robloxium$closeBuffers(CallbackInfo ci) {
        RobloxPartRenderer.close();
    }
}
