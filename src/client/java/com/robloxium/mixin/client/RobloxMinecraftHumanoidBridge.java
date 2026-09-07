package com.robloxium.mixin.client;

import net.minecraft.client.player.LocalPlayer;

/** Roblox Humanoid-shaped view over the real Minecraft player's health. */
public final class RobloxMinecraftHumanoidBridge {
    private final LocalPlayer player;

    public RobloxMinecraftHumanoidBridge(LocalPlayer player) {
        this.player = player;
    }

    public float getHealth() { return player.getHealth() * 5.0f; }
    public float health() { return getHealth(); }
    public void setHealth(double value) { player.setHealth((float)Math.max(0.0, Math.min(player.getMaxHealth(), value / 5.0))); }
    public void setHealth(float value) { setHealth((double)value); }
    public void health(double value) { setHealth(value); }
    public void TakeDamage(double amount) { setHealth(getHealth() - Math.max(0.0, amount)); }
    public void takeDamage(double amount) { TakeDamage(amount); }
    public double getMaxHealth() { return player.getMaxHealth() * 5.0; }
}
