package com.robloxium.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import net.minecraft.client.renderer.LevelRenderer;

/** Leaves Minecraft's normal environment sky enabled. Robloxium does not submit a Roblox skybox. */
@Mixin(LevelRenderer.class)
public abstract class RobloxSkyRendererMixin {
}
