package com.robloxium.mixin.client;

import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Adds the small Roblox object surface that classic scripts commonly use on
 * Players.LocalPlayer / a Touched hit, while keeping the object itself the
 * real Minecraft LocalPlayer.
 */
@Mixin(LocalPlayer.class)
public abstract class RobloxMinecraftPlayerMixin {
    public Object getCharacter() {
        return this;
    }

    public Object getParent() {
        return this;
    }

    public Object FindFirstChild(String name) {
        if ("Humanoid".equals(name)) {
            return new RobloxMinecraftHumanoidBridge((LocalPlayer)(Object)this);
        }
        return null;
    }

    public Object findFirstChild(String name) {
        return FindFirstChild(name);
    }
}
