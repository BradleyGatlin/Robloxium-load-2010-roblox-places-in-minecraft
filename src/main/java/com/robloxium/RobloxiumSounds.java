package com.robloxium;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.core.Registry;

/** Custom 2010-style Robloxium sounds backed by the supplied sound pack. */
public final class RobloxiumSounds {
    private RobloxiumSounds() {}

    public static final SoundEvent FOOTSTEP1 = register("footstep1");
    public static final SoundEvent FOOTSTEP2 = register("footstep2");
    public static final SoundEvent COLLIDE = register("collide");
    public static final SoundEvent BUTTON = register("button");
    public static final SoundEvent CLICK = register("clickfast");
    public static final SoundEvent JUMP = register("swoosh");
    public static final SoundEvent VICTORY = register("victory");

    private static SoundEvent register(String path) {
        Identifier id = Identifier.fromNamespaceAndPath("robloxium", path);
        return Registry.register(BuiltInRegistries.SOUND_EVENT, id,
                SoundEvent.createVariableRangeEvent(id));
    }

    public static void initialize() {
        System.out.println("[Robloxium] Registered legacy sound pack.");
    }
}
