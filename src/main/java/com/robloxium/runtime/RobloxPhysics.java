package com.robloxium.runtime;

import com.robloxium.math.CFrame;
import com.robloxium.math.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Legacy Roblox 2010 part physics.
 *
 * Units are deliberately Roblox units:
 *   position/size = studs
 *   velocity      = studs/sec
 *   rotVelocity   = radians/sec (world space)
 *   gravity       = studs/sec^2
 *
 * Anchored parts are immovable. Unanchored parts are integrated every
 * simulation tick and collide with other CanCollide parts AND with solid
 * Minecraft blocks. This is kept separate from the Minecraft player
 * controller so Roblox Parts remain guest-side physics objects.
 *
 * Large welded assemblies (any part with more than {@link #MAX_WELDS_PER_PART}
 * weld endpoints) are classified once when the level opens and skipped for
 * the rest of the session so the graph walk cannot hitch mid-frame.
 */
public final class RobloxPhysics {
    /** Exact legacy Roblox gravity used by the 2010-era engine. */
    public static final double GRAVITY = 196.2;
    private static final int SUBSTEPS = 4;
    private static final double RESTITUTION = 0.0;
    private static final double FRICTION = 0.80;
    private static final double ANGULAR_FRICTION = 0.70;
    private static final double EPS = 1.0e-5;
    /**
     * If a part is welded to more than this many other parts, the entire
     * connected assembly is treated as static.
     */
    private static final int MAX_WELDS_PER_PART = 50;

    private final RobloxGame game;

    /** Solid Minecraft blocks in the loaded level. Null until {@link #onLevelOpened}. */
    private MinecraftBlocks world;
    /** Cached at level-open (and only rebuilt if the weld count changes). */
    private Set<RobloxPart> disabledAssemblies = Collections.emptySet();
    private Set<PartPair> weldedPairs = Collections.emptySet();
    private int cachedWeldCount = -1;
    private boolean levelPrepared;

    public RobloxPhysics(RobloxGame g) {
        game = g;
    }

    /**
     * Call this as soon as the Minecraft level / Roblox place is available.
     * Walks the weld graph once so {@link #step} never pays that cost.
     */
    public void onLevelOpened(MinecraftBlocks blocks) {
        world = blocks;
        rebuildAssemblyCache();
        levelPrepared = true;
    }

    /** Call if scripts add/remove welds after the place has loaded. */
    public void invalidateAssemblies() {
        cachedWeldCount = -1;
        rebuildAssemblyCache();
    }

    public void step(double dt) {
        if (dt <= 0 || game == null) return;
        if (!levelPrepared) {
            // First tick after construction still prepares the cache so a
            // missed onLevelOpened cannot explode the first frame.
            rebuildAssemblyCache();
            levelPrepared = true;
        } else if (weldCountChanged()) {
            rebuildAssemblyCache();
        }

        double h = Math.min(dt, 0.1) / SUBSTEPS;
        for (int sub = 0; sub < SUBSTEPS; sub++) {
            List<RobloxPart> parts = new ArrayList<>(game.workspace().parts());

            // Integrate free parts. Roblox gravity affects every unanchored
            // BasePart, including CanCollide=false parts. Angular velocity is
            // applied in world space, matching legacy Part.RotVelocity.
            for (RobloxPart p : parts) {
                if (p.anchored() || disabledAssemblies.contains(p)) continue;
                Vec3 v = p.velocity();
                v = new Vec3(v.x(), v.y() - GRAVITY * h, v.z());
                p.velocity(v);
                CFrame cf = applyRotation(p.cframe(), p.rotVelocity(), h);
                p.cframe(cf.withPosition(cf.position().add(v.mul(h))));
            }

            solveWelds(parts);

            for (int i = 0; i < parts.size(); i++) {
                RobloxPart a = parts.get(i);
                if (!a.canCollide() || a.transparency() >= 1) continue;
                if (!disabledAssemblies.contains(a)) {
                    resolveMinecraftBlocks(a);
                }
                for (int j = i + 1; j < parts.size(); j++) {
                    RobloxPart b = parts.get(j);
                    if (!b.canCollide() || b.transparency() >= 1) continue;
                    if (a.anchored() && b.anchored()) continue;
                    if (disabledAssemblies.contains(a) || disabledAssemblies.contains(b)) continue;
                    if (weldedPairs.contains(new PartPair(a, b))) continue;
                    resolvePair(a, b);
                }
            }
        }
    }

    /**
     * Keep legacy Weld/WeldConstraint assemblies rigid after integration.
     * This is intentionally transform-based: old places rely on joints to
     * keep loose parts together, and treating each part as an independent
     * rigid body creates visible explosions/jitter.
     */
    private void solveWelds(List<RobloxPart> parts) {
        for (RobloxWeld w : game.dataModel().descendants(RobloxWeld.class)) {
            RobloxPart a = w.part0(), b = w.part1();
            if (a == null || b == null) continue;
            if (disabledAssemblies.contains(a) || disabledAssemblies.contains(b)) continue;
            CFrame desiredB = a.cframe().multiply(w.c0()).multiply(w.c1().inverse());
            boolean am = !a.anchored(), bm = !b.anchored();
            if (!am && !bm) continue;
            if (!am) {
                b.cframe(desiredB);
                b.velocity(Vec3.ZERO);
                b.rotVelocity(Vec3.ZERO);
            } else if (!bm) {
                CFrame desiredA = b.cframe().multiply(w.c1()).multiply(w.c0().inverse());
                a.cframe(desiredA);
                a.velocity(Vec3.ZERO);
                a.rotVelocity(Vec3.ZERO);
            } else {
                b.cframe(desiredB);
                b.velocity(a.velocity());
                b.rotVelocity(a.rotVelocity());
            }
        }
    }

    private boolean weldCountChanged() {
        int n = 0;
        for (RobloxWeld ignored : game.dataModel().descendants(RobloxWeld.class)) n++;
        return n != cachedWeldCount;
    }

    private void rebuildAssemblyCache() {
        List<RobloxPart> parts = game != null && game.workspace() != null
                ? new ArrayList<>(game.workspace().parts())
                : List.of();
        List<RobloxWeld> welds = game != null
                ? new ArrayList<>(game.dataModel().descendants(RobloxWeld.class))
                : List.of();
        cachedWeldCount = welds.size();
        weldedPairs = buildWeldedPairs(welds);
        disabledAssemblies = computeDisabledAssemblies(parts, welds);
    }

    /**
     * Returns every part belonging to a welded assembly containing a part
     * touched by more than MAX_WELDS_PER_PART welds. Such assemblies are
     * deliberately left out of dynamic simulation: they are commonly large
     * models (vehicles, buildings, machinery) and solving hundreds of rigid
     * contacts every tick is both wasteful and unstable. The assembly remains
     * intact because we do not move or split any of its welded parts.
     *
     * Computed once when the level opens.
     */
    private static Set<RobloxPart> computeDisabledAssemblies(List<RobloxPart> parts, List<RobloxWeld> welds) {
        Map<RobloxPart, Integer> counts = new IdentityHashMap<>();
        Map<RobloxPart, List<RobloxPart>> graph = new IdentityHashMap<>();
        for (RobloxWeld w : welds) {
            RobloxPart a = w.part0(), b = w.part1();
            if (a == null || b == null) continue;
            counts.put(a, counts.getOrDefault(a, 0) + 1);
            counts.put(b, counts.getOrDefault(b, 0) + 1);
            graph.computeIfAbsent(a, k -> new ArrayList<>()).add(b);
            graph.computeIfAbsent(b, k -> new ArrayList<>()).add(a);
        }
        Set<RobloxPart> disabled = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<RobloxPart> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (RobloxPart root : parts) {
            if (seen.contains(root)) continue;
            boolean welded = graph.containsKey(root);
            if (!welded) {
                seen.add(root);
                if (counts.getOrDefault(root, 0) > MAX_WELDS_PER_PART) disabled.add(root);
                continue;
            }
            ArrayList<RobloxPart> assembly = new ArrayList<>();
            ArrayList<RobloxPart> stack = new ArrayList<>();
            stack.add(root);
            seen.add(root);
            boolean tooLarge = false;
            while (!stack.isEmpty()) {
                RobloxPart p = stack.remove(stack.size() - 1);
                assembly.add(p);
                if (counts.getOrDefault(p, 0) > MAX_WELDS_PER_PART) tooLarge = true;
                for (RobloxPart n : graph.getOrDefault(p, List.of())) {
                    if (seen.add(n)) stack.add(n);
                }
            }
            if (tooLarge) disabled.addAll(assembly);
        }
        return disabled;
    }

    private static Set<PartPair> buildWeldedPairs(List<RobloxWeld> welds) {
        Set<PartPair> out = new HashSet<>();
        for (RobloxWeld w : welds) {
            if (w.part0() != null && w.part1() != null) out.add(new PartPair(w.part0(), w.part1()));
        }
        return out;
    }

    /**
     * Push an unanchored, simulated part out of any solid Minecraft block its
     * world-space AABB overlaps. Uses the same SAT-on-AABB path as part/part
     * contacts so resting friction and bounce stay consistent.
     */
    private void resolveMinecraftBlocks(RobloxPart p) {
        if (world == null || p.anchored()) return;
        Bounds A = bounds(p);
        double scale = world.studsPerBlock();
        if (scale <= EPS) scale = 1.0;
        Vec3 origin = world.originStuds();
        if (origin == null) origin = Vec3.ZERO;

        int minBX = floor((A.minX - origin.x()) / scale) - 1;
        int minBY = floor((A.minY - origin.y()) / scale) - 1;
        int minBZ = floor((A.minZ - origin.z()) / scale) - 1;
        int maxBX = floor((A.maxX - origin.x()) / scale) + 1;
        int maxBY = floor((A.maxY - origin.y()) / scale) + 1;
        int maxBZ = floor((A.maxZ - origin.z()) / scale) + 1;

        // Hard cap so a huge falling part cannot scan a whole chunk column.
        int spanX = maxBX - minBX + 1, spanY = maxBY - minBY + 1, spanZ = maxBZ - minBZ + 1;
        if (spanX * spanY * spanZ > 4096) return;

        for (int bx = minBX; bx <= maxBX; bx++) {
            for (int by = minBY; by <= maxBY; by++) {
                for (int bz = minBZ; bz <= maxBZ; bz++) {
                    if (!world.isSolid(bx, by, bz)) continue;
                    double x0 = origin.x() + bx * scale;
                    double y0 = origin.y() + by * scale;
                    double z0 = origin.z() + bz * scale;
                    Bounds B = new Bounds(x0, y0, z0, x0 + scale, y0 + scale, z0 + scale);
                    resolveAgainstStatic(p, B);
                }
            }
        }
    }

    private static void resolvePair(RobloxPart a, RobloxPart b) {
        Bounds A = bounds(a), B = bounds(b);
        Vec3 n = overlapNormal(A, B, a.cframe().position(), b.cframe().position());
        if (n == null) return;
        separate(a, b, n);
        applyContactImpulse(a, b, axisOf(n), signOf(n));
    }

    private static void resolveAgainstStatic(RobloxPart a, Bounds B) {
        Bounds A = bounds(a);
        Vec3 ac = a.cframe().position();
        Vec3 bc = new Vec3((B.minX + B.maxX) * 0.5, (B.minY + B.maxY) * 0.5, (B.minZ + B.maxZ) * 0.5);
        Vec3 n = overlapNormal(A, B, ac, bc);
        if (n == null) return;
        if (!a.anchored()) {
            a.cframe(a.cframe().withPosition(a.cframe().position().add(n)));
        }
        applyContactImpulse(a, null, axisOf(n), signOf(n));
    }

    /** Smallest-axis separation, or null if the AABBs do not overlap. */
    private static Vec3 overlapNormal(Bounds A, Bounds B, Vec3 ac, Vec3 bc) {
        if (A.maxX <= B.minX + EPS || A.minX >= B.maxX - EPS ||
                A.maxY <= B.minY + EPS || A.minY >= B.maxY - EPS ||
                A.maxZ <= B.minZ + EPS || A.minZ >= B.maxZ - EPS) return null;

        double px = Math.min(A.maxX - B.minX, B.maxX - A.minX);
        double py = Math.min(A.maxY - B.minY, B.maxY - A.minY);
        double pz = Math.min(A.maxZ - B.minZ, B.maxZ - A.minZ);

        if (py <= px && py <= pz) {
            double sign = ac.y() >= bc.y() ? 1 : -1;
            return new Vec3(0, sign * py, 0);
        }
        if (px <= pz) {
            double sign = ac.x() >= bc.x() ? 1 : -1;
            return new Vec3(sign * px, 0, 0);
        }
        double sign = ac.z() >= bc.z() ? 1 : -1;
        return new Vec3(0, 0, sign * pz);
    }

    private static void separate(RobloxPart a, RobloxPart b, Vec3 correction) {
        boolean am = !a.anchored(), bm = !b.anchored();
        if (am && bm) {
            a.cframe(a.cframe().withPosition(a.cframe().position().add(correction.mul(.5))));
            b.cframe(b.cframe().withPosition(b.cframe().position().sub(correction.mul(.5))));
        } else if (am) {
            a.cframe(a.cframe().withPosition(a.cframe().position().add(correction)));
        } else if (bm) {
            b.cframe(b.cframe().withPosition(b.cframe().position().sub(correction)));
        }
    }

    /**
     * Linear impulse along the contact axis plus the 2010-style rest friction
     * that kills slide and spin while a part is sitting on something.
     * {@code b} may be null for a Minecraft-block contact (treated as anchored).
     */
    private static void applyContactImpulse(RobloxPart a, RobloxPart b, Vec3 axis, double sign) {
        boolean aFree = !a.anchored();
        boolean bFree = b != null && !b.anchored();
        double va = dot(a.velocity(), axis);
        double vb = b == null ? 0 : dot(b.velocity(), axis);
        double rel = va - vb;
        if (rel * sign < 0) {
            double impulse = -(1 + RESTITUTION) * rel;
            if (aFree && !bFree) {
                a.velocity(a.velocity().add(axis.mul(impulse * sign)));
            } else if (!aFree && bFree) {
                b.velocity(b.velocity().sub(axis.mul(impulse * sign)));
            } else if (aFree && bFree) {
                a.velocity(a.velocity().add(axis.mul(impulse * sign * .5)));
                b.velocity(b.velocity().sub(axis.mul(impulse * sign * .5)));
            }
        }

        boolean vertical = Math.abs(axis.y()) > 0.5;
        if (vertical) {
            if (aFree) {
                a.velocity(new Vec3(a.velocity().x() * FRICTION, a.velocity().y(), a.velocity().z() * FRICTION));
                a.rotVelocity(a.rotVelocity().mul(ANGULAR_FRICTION));
            }
            if (bFree) {
                b.velocity(new Vec3(b.velocity().x() * FRICTION, b.velocity().y(), b.velocity().z() * FRICTION));
                b.rotVelocity(b.rotVelocity().mul(ANGULAR_FRICTION));
            }
        }
    }

    /**
     * Integrate world-space angular velocity onto a CFrame.
     * R_new = R_delta(world) * R_old, then the original translation is kept
     * by the caller via {@code withPosition}.
     *
     * Requires {@code CFrame.fromRotation(double[][])} (origin CFrame with
     * only that 3x3) so we can left-multiply without mutating translation.
     */
    static CFrame applyRotation(CFrame cf, Vec3 omega, double dt) {
        if (cf == null || omega == null) return cf;
        double wx = omega.x(), wy = omega.y(), wz = omega.z();
        double speed = Math.sqrt(wx * wx + wy * wy + wz * wz);
        if (speed < EPS) return cf;
        double angle = speed * dt;
        double ax = wx / speed, ay = wy / speed, az = wz / speed;
        double c = Math.cos(angle), s = Math.sin(angle), t = 1.0 - c;
        double[][] d = {
                {t * ax * ax + c, t * ax * ay - s * az, t * ax * az + s * ay},
                {t * ay * ax + s * az, t * ay * ay + c, t * ay * az - s * ax},
                {t * az * ax - s * ay, t * az * ay + s * ax, t * az * az + c}
        };
        CFrame delta = CFrame.fromRotation(d);
        CFrame rotated = delta.multiply(cf);
        return rotated.withPosition(cf.position());
    }

    private static double dot(Vec3 a, Vec3 b) {
        return a.x() * b.x() + a.y() * b.y() + a.z() * b.z();
    }

    private static Vec3 axisOf(Vec3 n) {
        double ax = Math.abs(n.x()), ay = Math.abs(n.y()), az = Math.abs(n.z());
        if (ay >= ax && ay >= az) return new Vec3(0, 1, 0);
        if (ax >= az) return new Vec3(1, 0, 0);
        return new Vec3(0, 0, 1);
    }

    private static double signOf(Vec3 n) {
        double ax = Math.abs(n.x()), ay = Math.abs(n.y()), az = Math.abs(n.z());
        if (ay >= ax && ay >= az) return n.y() >= 0 ? 1 : -1;
        if (ax >= az) return n.x() >= 0 ? 1 : -1;
        return n.z() >= 0 ? 1 : -1;
    }

    private static int floor(double v) {
        int i = (int) v;
        return v < i ? i - 1 : i;
    }

    private static Bounds bounds(RobloxPart p) {
        Vec3 s = p.size().mul(.5);
        double[][] r = p.cframe().rotation();
        double ex = Math.abs(r[0][0]) * s.x() + Math.abs(r[0][1]) * s.y() + Math.abs(r[0][2]) * s.z();
        double ey = Math.abs(r[1][0]) * s.x() + Math.abs(r[1][1]) * s.y() + Math.abs(r[1][2]) * s.z();
        double ez = Math.abs(r[2][0]) * s.x() + Math.abs(r[2][1]) * s.y() + Math.abs(r[2][2]) * s.z();
        Vec3 c = p.cframe().position();
        return new Bounds(c.x() - ex, c.y() - ey, c.z() - ez, c.x() + ex, c.y() + ey, c.z() + ez);
    }

    /**
     * Minecraft collision oracle bound at level-open.
     *
     * {@link #isSolid} is in Minecraft block coordinates.
     * {@link #studsPerBlock} and {@link #originStuds} map those blocks into
     * the Roblox stud space this integrator uses.
     */
    public interface MinecraftBlocks {
        boolean isSolid(int blockX, int blockY, int blockZ);

        default double studsPerBlock() {
            return 1.0;
        }

        default Vec3 originStuds() {
            return Vec3.ZERO;
        }
    }

    private record PartPair(RobloxPart a, RobloxPart b) {
        @Override
        public boolean equals(Object o) {
            return o instanceof PartPair p && ((p.a == a && p.b == b) || (p.a == b && p.b == a));
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(a) ^ System.identityHashCode(b);
        }
    }

    private record Bounds(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {}
}