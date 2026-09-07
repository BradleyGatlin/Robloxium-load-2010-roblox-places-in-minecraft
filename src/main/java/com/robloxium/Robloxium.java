package com.robloxium;

import net.fabricmc.api.ModInitializer;

/** Robloxium 3: a clean guest-runtime/host-bridge rewrite. */
public final class Robloxium implements ModInitializer {
    public static final String VERSION = "3.0.0-alpha1";
    public static final String BUILD_ID = "ROBLOXIUM-3-ALPHA1-2010-VULKAN-CULL-FIX";

    @Override public void onInitialize() {
        RobloxiumSounds.initialize();
        System.out.println("[Robloxium] " + BUILD_ID + " | clean 2010 guest runtime");
    }
}
