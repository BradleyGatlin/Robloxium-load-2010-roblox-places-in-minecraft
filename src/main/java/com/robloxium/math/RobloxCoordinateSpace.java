package com.robloxium.math;

/**
 * The sole host/guest unit boundary.
 * Guest state is Roblox studs. Host state is Minecraft blocks.
 */
public final class RobloxCoordinateSpace {
    private RobloxCoordinateSpace() {}
    public static final double STUD_TO_BLOCK = 0.4; // 5 studs = 2 blocks
    public static final double BLOCK_TO_STUD = 2.5;
    public static Vec3 toMinecraft(Vec3 studs) { return studs.mul(STUD_TO_BLOCK); }
    public static Vec3 toRoblox(Vec3 blocks) { return blocks.mul(BLOCK_TO_STUD); }
    public static double toMinecraft(double studs) { return studs*STUD_TO_BLOCK; }
    public static double toRoblox(double blocks) { return blocks*BLOCK_TO_STUD; }
}
