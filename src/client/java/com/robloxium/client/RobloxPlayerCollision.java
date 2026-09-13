package com.robloxium.client;

import com.robloxium.math.RobloxCoordinateSpace;
import com.robloxium.math.Vec3;
import com.robloxium.runtime.RobloxGame;
import com.robloxium.runtime.RobloxPart;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

final class RobloxPlayerCollision {
    private static final double MAX_DISTANCE_STUDS = 1096.0;
    private static final double EPS = 0.003;
    private static final double CONTACT_EPS = 0.08;
    private static final double STEP_HEIGHT_STUDS = 1.4;
    private static final double STEP_CLEARANCE = 0.03;
    private static final double MAX_SWEEP_STUDS = 192.0;
    private static final int MAX_SWEEP_PASSES = 6;

    // Roblox Humanoid.MaxSlopeAngle default is 89°. Anything steeper than a
    // wall is walkable and must not convert gravity into a downhill slide.
    private static final double WALKABLE_NORMAL_Y = Math.cos(Math.toRadians(89.0));

    private static final Set<RobloxPart> TOUCHING =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private static final List<RobloxPart> COLLIDERS = new ArrayList<>();
    private static int lastPartCount = -1;
    private static RobloxGame cachedGame;
    private static LocalPlayer trackedPlayer;
    private static boolean previousPositionValid;
    private static double previousX, previousY, previousZ;
    private static double previousHalfX, previousHalfY, previousHalfZ;
    private static boolean previousGrounded;
    private static double lastGroundFeetY;
    private static float lastMinecraftHealth = 20f, lastRobloxHealth = 100f;

    private RobloxPlayerCollision() {}

    static void resolve(Minecraft mc, RobloxGame game) {
        if (mc == null || game == null || !game.running() || mc.player == null) return;
        LocalPlayer player = mc.player;
        AABB playerBox = player.getBoundingBox();

        if (player != trackedPlayer || game != cachedGame) {
            trackedPlayer = player;
            previousPositionValid = false;
            previousGrounded = false;
            TOUCHING.clear();
            COLLIDERS.clear();
            lastPartCount = -1;
        }

        List<RobloxPart> workspaceParts = game.workspace().parts();
        if (game != cachedGame || workspaceParts.size() != lastPartCount) {
            cachedGame = game;
            COLLIDERS.clear();
            COLLIDERS.addAll(workspaceParts);
            lastPartCount = workspaceParts.size();
        }

        double halfX = RobloxCoordinateSpace.toRoblox((playerBox.maxX - playerBox.minX) * 0.5);
        double halfY = RobloxCoordinateSpace.toRoblox((playerBox.maxY - playerBox.minY) * 0.5);
        double halfZ = RobloxCoordinateSpace.toRoblox((playerBox.maxZ - playerBox.minZ) * 0.5);

        // Minecraft pose (stand / sneak / crawl) changes the AABB around the
        // feet. The solver uses the AABB center, so a crouch would otherwise
        // look like a downward sweep into the floor and standing up would
        // look like a jump into the ceiling.
        Vec3 currentFeet = toRoblox(
                (playerBox.minX + playerBox.maxX) * 0.5,
                playerBox.minY,
                (playerBox.minZ + playerBox.maxZ) * 0.5
        );
        Vec3 current = new Vec3(currentFeet.x(), currentFeet.y() + halfY, currentFeet.z());

        if (!previousPositionValid || distanceSquared(current.x(), currentFeet.y(), current.z(),
                previousX, previousY - previousHalfY, previousZ) > MAX_SWEEP_STUDS * MAX_SWEEP_STUDS) {
            previousX = current.x();
            previousY = current.y();
            previousZ = current.z();
            previousHalfX = halfX;
            previousHalfY = halfY;
            previousHalfZ = halfZ;
            previousPositionValid = true;
        }

        double previousFeetY = previousY - previousHalfY;
        if (previousGrounded && previousHalfY > EPS) {
            previousFeetY = lastGroundFeetY;
        }
        // Apply this frame's pose at last tick's feet. Pose itself never
        // becomes a vertical sweep.
        Vec3 start = new Vec3(previousX, previousFeetY + halfY, previousZ);
        Vec3 end = current;
        Vec3 remaining = end.sub(start);
        Vec3 resolved = start;
        boolean grounded = previousGrounded;
        Vec3 collisionNormalA = null, collisionNormalB = null, collisionNormalC = null;
        Set<RobloxPart> nowTouching = Collections.newSetFromMap(new IdentityHashMap<>());

        for (int pass = 0; pass < MAX_SWEEP_PASSES; pass++) {
            SweepHit hit = findEarliestHit(resolved, remaining, halfX, halfY, halfZ, nowTouching);
            if (hit == null) {
                resolved = resolved.add(remaining);
                break;
            }
            nowTouching.add(hit.part);

            Vec3 n = flattenWalkableNormal(hit.normal);
            if (Math.abs(n.y()) < 0.5) {
                Vec3 stepped = tryStepUp(resolved, remaining, halfX, halfY, halfZ, hit.part);
                if (stepped != null) {
                    resolved = stepped;
                    grounded = true;
                    continue;
                }
            }

            if (collisionNormalA == null) collisionNormalA = n;
            else if (!sameAxis(collisionNormalA, n) && collisionNormalB == null) collisionNormalB = n;
            else if (!sameAxis(collisionNormalA, n)
                    && (collisionNormalB == null || !sameAxis(collisionNormalB, n))
                    && collisionNormalC == null) collisionNormalC = n;

            double travel = Math.max(0.0, hit.t - EPS / Math.max(1.0, remainingLength(remaining)));
            resolved = resolved.add(remaining.mul(travel));
            resolved = resolved.add(n.mul(EPS));
            if (isWalkable(n)) grounded = true;

            double left = Math.max(0.0, 1.0 - hit.t);
            Vec3 afterHit = remaining.mul(left);
            afterHit = slideAlong(afterHit, n);
            if (remainingLength(afterHit) < EPS) {
                remaining = new Vec3(0, 0, 0);
                break;
            }
            remaining = afterHit;
        }

        Vec3 slopeResolved = resolveSlopeSupport(resolved, halfX, halfY, halfZ, grounded, nowTouching);
        if (slopeResolved != null) {
            resolved = slopeResolved;
            grounded = true;
        }

        for (int i = 0; i < 3; i++) {
            Penetration penetration = findDeepestPenetration(resolved, halfX, halfY, halfZ, nowTouching);
            if (penetration == null) break;
            Vec3 n = flattenWalkableNormal(penetration.normal);
            resolved = resolved.add(n.mul(penetration.depth + EPS));
            if (isWalkable(n)) grounded = true;
        }

        Vec3 mcResolved = toMinecraftWorld(resolved);
        double feetMcY = mcResolved.y() - RobloxCoordinateSpace.toMinecraft(halfY);
        double currentCenterX = (playerBox.minX + playerBox.maxX) * 0.5;
        double currentFeetMcY = playerBox.minY;
        double currentCenterZ = (playerBox.minZ + playerBox.maxZ) * 0.5;
        double correctedDistanceSq = (mcResolved.x() - currentCenterX) * (mcResolved.x() - currentCenterX)
                + (feetMcY - currentFeetMcY) * (feetMcY - currentFeetMcY)
                + (mcResolved.z() - currentCenterZ) * (mcResolved.z() - currentCenterZ);
        if (correctedDistanceSq > 1.0e-10) {
            player.setPos(mcResolved.x(), feetMcY, mcResolved.z());
        }

        net.minecraft.world.phys.Vec3 velocity = player.getDeltaMovement();
        if (collisionNormalA != null) velocity = applyContactVelocity(velocity, collisionNormalA);
        if (collisionNormalB != null) velocity = applyContactVelocity(velocity, collisionNormalB);
        if (collisionNormalC != null) velocity = applyContactVelocity(velocity, collisionNormalC);

        SweepHit velocityHit = findVelocityCollision(resolved, halfX, halfY, halfZ, nowTouching);
        if (velocityHit != null) {
            Vec3 n = flattenWalkableNormal(velocityHit.normal);
            if (isWalkable(n)) grounded = true;
            velocity = applyContactVelocity(velocity, n);
        }

        if (grounded) {
            velocity = new net.minecraft.world.phys.Vec3(velocity.x, Math.max(0.0, velocity.y), velocity.z);
            player.setOnGround(true);
            player.resetFallDistance();
        }
        player.setDeltaMovement(velocity);

        for (RobloxPart part : nowTouching) {
            if (!TOUCHING.contains(part)) game.firePlayerTouched(part, player);
        }
        TOUCHING.retainAll(nowTouching);
        TOUCHING.addAll(nowTouching);

        previousX = resolved.x();
        previousY = resolved.y();
        previousZ = resolved.z();
        previousHalfX = halfX;
        previousHalfY = halfY;
        previousHalfZ = halfZ;
        previousGrounded = grounded;
        lastGroundFeetY = resolved.y() - halfY;

        game.playersService().updatePosition(toRoblox(player.getX(), player.getY(), player.getZ()));

        float robloxHealth = (float) game.playersService().localPlayer().character().humanoid().health();
        if (Math.abs(robloxHealth - lastRobloxHealth) > 0.001f) {
            player.setHealth(Math.max(0, Math.min(player.getMaxHealth(), robloxHealth)));
        } else if (Math.abs(player.getHealth() - lastMinecraftHealth) > 0.001f) {
            game.playersService().localPlayer().character().humanoid().health(player.getHealth());
            robloxHealth = (float) game.playersService().localPlayer().character().humanoid().health();
        }
        lastMinecraftHealth = player.getHealth();
        lastRobloxHealth = robloxHealth;
    }

    private static boolean isWalkable(Vec3 n) {
        return n != null && n.y() > WALKABLE_NORMAL_Y;
    }

    /**
     * Walkable contacts act like a floor. Keep world-up so a 1° tilt cannot
     * turn gravity into a sideways shove that slides the player off the part.
     */
    private static Vec3 flattenWalkableNormal(Vec3 n) {
        if (n == null) return new Vec3(0, 1, 0);
        if (isWalkable(n)) return new Vec3(0, 1, 0);
        double len = remainingLength(n);
        return len < EPS ? new Vec3(0, 1, 0) : n.mul(1.0 / len);
    }

    private static Vec3 slideAlong(Vec3 motion, Vec3 normal) {
        if (isWalkable(normal)) {
            return new Vec3(motion.x(), Math.max(0.0, motion.y()), motion.z());
        }
        double into = motion.dot(normal);
        return into < 0.0 ? motion.sub(normal.mul(into)) : motion;
    }

    private static net.minecraft.world.phys.Vec3 applyContactVelocity(
            net.minecraft.world.phys.Vec3 velocity, Vec3 normal) {
        if (isWalkable(normal)) {
            return new net.minecraft.world.phys.Vec3(velocity.x, Math.max(0.0, velocity.y), velocity.z);
        }
        return projectOutVelocity(velocity, normal);
    }

    private static Vec3 tryStepUp(Vec3 center, Vec3 remaining,
                                   double hx, double hy, double hz,
                                   RobloxPart obstacle) {
        if (remainingLength(remaining) < EPS || remaining.y() > CONTACT_EPS) return null;
        AABB b = partBounds(obstacle);
        double feet = center.y() - hy;
        double rise = b.maxY - feet;
        if (rise < 0.0 || rise > STEP_HEIGHT_STUDS + STEP_CLEARANCE) return null;
        double endX = center.x() + remaining.x();
        double endZ = center.z() + remaining.z();
        double minX = Math.min(center.x(), endX) - hx;
        double maxX = Math.max(center.x(), endX) + hx;
        double minZ = Math.min(center.z(), endZ) - hz;
        double maxZ = Math.max(center.z(), endZ) + hz;
        if (maxX < b.minX || minX > b.maxX || maxZ < b.minZ || minZ > b.maxZ) return null;
        double newCenterY = b.maxY + hy + STEP_CLEARANCE;
        for (RobloxPart part : COLLIDERS) {
            if (!part.canCollide() || part.transparency() >= 1 || part == obstacle) continue;
            if (orientedOverlaps(center.x(), newCenterY, center.z(), hx, hy, hz, part)) return null;
        }
        double testX = center.x() + remaining.x();
        double testZ = center.z() + remaining.z();
        for (RobloxPart part : COLLIDERS) {
            if (!part.canCollide() || part.transparency() >= 1 || part == obstacle) continue;
            if (orientedOverlaps(testX, newCenterY, testZ, hx, hy, hz, part)) return null;
        }
        return new Vec3(center.x(), newCenterY, center.z());
    }

    private static boolean orientedOverlaps(double centerX, double centerY, double centerZ,
                                            double hx, double hy, double hz, RobloxPart part) {
        if (isSlope(part)) {
            return wedgeContains(centerX, centerY, centerZ, hx, hy, hz, part);
        }
        CFrameData cf = CFrameData.of(part);
        Vec3 local = cf.toLocal(new Vec3(centerX, centerY, centerZ));
        Vec3 half = partLocalHalf(part);
        Vec3 ph = playerLocalHalf(cf.rotation(), hx, hy, hz);
        return local.x() + ph.x() > -half.x() + EPS && local.x() - ph.x() < half.x() - EPS
                && local.y() + ph.y() > -half.y() + EPS && local.y() - ph.y() < half.y() - EPS
                && local.z() + ph.z() > -half.z() + EPS && local.z() - ph.z() < half.z() - EPS;
    }

    private static SweepHit findEarliestHit(Vec3 start, Vec3 delta,
                                            double hx, double hy, double hz,
                                            Set<RobloxPart> touching) {
        if (remainingLength(delta) < EPS) return null;
        SweepHit best = null;
        for (RobloxPart part : COLLIDERS) {
            if (!part.canCollide() || part.transparency() >= 1) continue;
            AABB bounds = partBounds(part);
            if (!nearby(start, delta, bounds, hx, hy, hz)) continue;
            SweepHit hit = isSlope(part)
                    ? sweepPointAgainstWedge(start, delta, part, hx, hy, hz)
                    : sweepPointAgainstOrientedBox(start, delta, part, hx, hy, hz);
            if (hit != null && (best == null || hit.t < best.t)) best = hit;
        }
        return best;
    }

    private static boolean isSlope(RobloxPart p) {
        return "WedgePart".equalsIgnoreCase(p.className())
                || "CornerWedgePart".equalsIgnoreCase(p.className());
    }

    /**
     * WedgePart: triangular prism. High edge is +local Z. Slope plane through
     * the part origin: y * halfZ - z * halfY = 0, outward normal (0, halfZ, -halfY).
     *
     * CornerWedgePart: base at y = -halfY, peak at (+halfX, +halfY, -halfZ)
     * matching RobloxPartRenderer.drawCornerWedge.
     */
    private static SweepHit sweepPointAgainstWedge(Vec3 start, Vec3 delta, RobloxPart part,
                                                    double hx, double hy, double hz) {
        CFrameData cf = CFrameData.of(part);
        Vec3 localStart = cf.toLocal(start);
        Vec3 localDelta = cf.rotateInverse(delta);
        Vec3 half = partLocalHalf(part);
        Vec3 ph = playerLocalHalf(cf.rotation(), hx, hy, hz);
        Plane[] planes = wedgePlanes(part, half, ph);
        if (planes == null) return sweepPointAgainstOrientedBox(start, delta, part, hx, hy, hz);

        double tEnter = 0.0;
        double tExit = 1.0;
        Vec3 enterNormalLocal = new Vec3(0, 0, 0);
        for (Plane plane : planes) {
            double startDot = localStart.dot(plane.normal) - plane.offset;
            double deltaDot = localDelta.dot(plane.normal);
            if (Math.abs(deltaDot) < 1.0e-12) {
                if (startDot > 0.0) return null;
                continue;
            }
            double t = -startDot / deltaDot;
            if (deltaDot < 0.0) {
                if (t > tEnter) {
                    tEnter = t;
                    enterNormalLocal = plane.normal;
                }
            } else if (t < tExit) {
                tExit = t;
            }
            if (tEnter > tExit) return null;
        }
        if (tEnter > tExit || tExit < 0.0 || tEnter > 1.0) return null;
        if (tEnter <= EPS && wedgeContains(start.x(), start.y(), start.z(), hx, hy, hz, part)) {
            return null;
        }
        Vec3 worldN = cf.rotate(enterNormalLocal);
        double len = remainingLength(worldN);
        if (len > EPS) worldN = worldN.mul(1.0 / len);
        return new SweepHit(part, Math.max(0.0, tEnter), worldN);
    }

    private static Plane[] wedgePlanes(RobloxPart part, Vec3 half, Vec3 ph) {
        double hx = half.x() + ph.x();
        double hy = half.y() + ph.y();
        double hz = half.z() + ph.z();
        if ("WedgePart".equalsIgnoreCase(part.className())) {
            Vec3 slope = new Vec3(0, half.z(), -half.y());
            double sl = remainingLength(slope);
            if (sl < EPS) return null;
            slope = slope.mul(1.0 / sl);
            double slopeOffset = Math.abs(slope.x()) * ph.x() + Math.abs(slope.y()) * ph.y() + Math.abs(slope.z()) * ph.z();
            return new Plane[]{
                    new Plane(new Vec3(1, 0, 0), hx),
                    new Plane(new Vec3(-1, 0, 0), hx),
                    new Plane(new Vec3(0, -1, 0), hy),
                    new Plane(new Vec3(0, 0, 1), hz),
                    new Plane(slope, slopeOffset)
            };
        }
        if ("CornerWedgePart".equalsIgnoreCase(part.className())) {
            // Peak at (+hx, +hy, -hz). Convex hull of the base quad + peak.
            Vec3 peak = new Vec3(half.x(), half.y(), -half.z());
            Vec3 a = new Vec3(-half.x(), -half.y(), -half.z());
            Vec3 b = new Vec3(half.x(), -half.y(), -half.z());
            Vec3 c = new Vec3(half.x(), -half.y(), half.z());
            Vec3 d = new Vec3(-half.x(), -half.y(), half.z());
            Plane[] raw = {
                    planeFrom(a, b, c),
                    planeFrom(a, b, peak),
                    planeFrom(b, c, peak),
                    planeFrom(c, d, peak),
                    planeFrom(d, a, peak)
            };
            Plane[] out = new Plane[raw.length];
            for (int i = 0; i < raw.length; i++) {
                Plane p = raw[i];
                if (p == null) return null;
                double expand = Math.abs(p.normal.x()) * ph.x()
                        + Math.abs(p.normal.y()) * ph.y()
                        + Math.abs(p.normal.z()) * ph.z();
                out[i] = new Plane(p.normal, p.offset + expand);
            }
            return out;
        }
        return null;
    }

    private static Plane planeFrom(Vec3 a, Vec3 b, Vec3 c) {
        Vec3 n = b.sub(a).cross(c.sub(a));
        double len = remainingLength(n);
        if (len < EPS) return null;
        n = n.mul(1.0 / len);
        // Keep normals pointing outward from the origin when possible.
        if (n.dot(a.add(b).add(c).mul(1.0 / 3.0)) < 0.0) n = n.mul(-1.0);
        return new Plane(n, n.dot(a));
    }

    private static boolean wedgeContains(double cx, double cy, double cz,
                                         double hx, double hy, double hz, RobloxPart part) {
        CFrameData cf = CFrameData.of(part);
        Vec3 local = cf.toLocal(new Vec3(cx, cy, cz));
        Vec3 half = partLocalHalf(part);
        Vec3 ph = playerLocalHalf(cf.rotation(), hx, hy, hz);
        Plane[] planes = wedgePlanes(part, half, ph);
        if (planes == null) return false;
        for (Plane plane : planes) {
            if (local.dot(plane.normal) - plane.offset > -EPS) return false;
        }
        return true;
    }

    private static Vec3 resolveSlopeSupport(Vec3 center, double hx, double hy, double hz,
                                            boolean wasGrounded, Set<RobloxPart> touching) {
        Vec3 best = null;
        double bestTop = -Double.MAX_VALUE;
        double catchHeight = wasGrounded
                ? STEP_HEIGHT_STUDS + CONTACT_EPS
                : STEP_HEIGHT_STUDS + CONTACT_EPS;
        for (RobloxPart part : COLLIDERS) {
            if (!part.canCollide() || part.transparency() >= 1 || !isSlope(part)) continue;
            CFrameData cf = CFrameData.of(part);
            Vec3 local = cf.toLocal(center);
            double halfX = Math.abs(part.size().x()) * .5;
            double halfY = Math.abs(part.size().y()) * .5;
            double halfZ = Math.abs(part.size().z()) * .5;
            if (halfX < EPS || halfY < EPS || halfZ < EPS) continue;

            double topLocal = slopeHeightLocal(part, local, hx, hz, halfX, halfY, halfZ);
            if (Double.isNaN(topLocal)) continue;

            Vec3 topWorld = cf.toWorld(new Vec3(local.x(), topLocal, local.z()));
            double feet = center.y() - hy;
            double verticalGap = topWorld.y() - feet;
            // Recover from being inside the ramp as well as standing on it.
            if (verticalGap < -hy - CONTACT_EPS || verticalGap > catchHeight) continue;
            if (!horizontalFootprintOverlapsRamp(center, hx, hz, part)) continue;

            double newCenterY = topWorld.y() + hy + EPS;
            if (best == null || newCenterY > bestTop) {
                bestTop = newCenterY;
                best = new Vec3(center.x(), newCenterY, center.z());
                touching.add(part);
            }
        }
        return best;
    }

    private static double slopeHeightLocal(RobloxPart part, Vec3 local,
                                           double footprintX, double footprintZ,
                                           double halfX, double halfY, double halfZ) {
        if (local.x() + footprintX < -halfX || local.x() - footprintX > halfX
                || local.z() + footprintZ < -halfZ || local.z() - footprintZ > halfZ) {
            return Double.NaN;
        }
        if ("WedgePart".equalsIgnoreCase(part.className())) {
            double z0 = Math.max(-halfZ, local.z() - footprintZ);
            double z1 = Math.min(halfZ, local.z() + footprintZ);
            return Math.max((z0 / halfZ) * halfY, (z1 / halfZ) * halfY);
        }
        // CornerWedge peak is (+X, +Y, -Z) — height rises toward +X and -Z.
        double x0 = Math.max(-halfX, local.x() - footprintX);
        double x1 = Math.min(halfX, local.x() + footprintX);
        double zA = Math.max(-halfZ, local.z() - footprintZ);
        double zB = Math.min(halfZ, local.z() + footprintZ);
        double h00 = cornerWedgeHeight(x0, zA, halfX, halfY, halfZ);
        double h01 = cornerWedgeHeight(x0, zB, halfX, halfY, halfZ);
        double h10 = cornerWedgeHeight(x1, zA, halfX, halfY, halfZ);
        double h11 = cornerWedgeHeight(x1, zB, halfX, halfY, halfZ);
        return Math.max(Math.max(h00, h01), Math.max(h10, h11));
    }

    private static double cornerWedgeHeight(double x, double z, double halfX, double halfY, double halfZ) {
        double tx = (x + halfX) / Math.max(2.0 * halfX, EPS);
        double tz = (halfZ - z) / Math.max(2.0 * halfZ, EPS);
        double t = Math.max(0.0, Math.min(1.0, Math.min(tx, tz)));
        return -halfY + t * (2.0 * halfY);
    }

    private static boolean horizontalFootprintOverlapsRamp(Vec3 center, double hx, double hz, RobloxPart part) {
        CFrameData cf = CFrameData.of(part);
        Vec3[] corners = {
                cf.toLocal(new Vec3(center.x() - hx, center.y(), center.z() - hz)),
                cf.toLocal(new Vec3(center.x() - hx, center.y(), center.z() + hz)),
                cf.toLocal(new Vec3(center.x() + hx, center.y(), center.z() - hz)),
                cf.toLocal(new Vec3(center.x() + hx, center.y(), center.z() + hz))
        };
        double halfX = Math.abs(part.size().x()) * .5;
        double halfZ = Math.abs(part.size().z()) * .5;
        for (Vec3 q : corners) {
            if (q.x() >= -halfX - EPS && q.x() <= halfX + EPS
                    && q.z() >= -halfZ - EPS && q.z() <= halfZ + EPS) return true;
        }
        Vec3 local = cf.toLocal(center);
        return local.x() >= -halfX - hx && local.x() <= halfX + hx
                && local.z() >= -halfZ - hz && local.z() <= halfZ + hz;
    }

    private record CFrameData(Vec3 position, double[][] rotation) {
        static CFrameData of(RobloxPart p) {
            return new CFrameData(p.cframe().position(), p.cframe().rotation());
        }
        Vec3 toLocal(Vec3 world) {
            return rotateInverse(world.sub(position));
        }
        Vec3 toWorld(Vec3 local) {
            return position.add(rotate(local));
        }
        Vec3 rotate(Vec3 local) {
            return new Vec3(
                    rotation[0][0] * local.x() + rotation[0][1] * local.y() + rotation[0][2] * local.z(),
                    rotation[1][0] * local.x() + rotation[1][1] * local.y() + rotation[1][2] * local.z(),
                    rotation[2][0] * local.x() + rotation[2][1] * local.y() + rotation[2][2] * local.z());
        }
        Vec3 rotateInverse(Vec3 world) {
            return new Vec3(
                    rotation[0][0] * world.x() + rotation[1][0] * world.y() + rotation[2][0] * world.z(),
                    rotation[0][1] * world.x() + rotation[1][1] * world.y() + rotation[2][1] * world.z(),
                    rotation[0][2] * world.x() + rotation[1][2] * world.y() + rotation[2][2] * world.z());
        }
    }

    private static SweepHit findVelocityCollision(Vec3 center, double hx, double hy, double hz,
                                                   Set<RobloxPart> touching) {
        Vec3 down = new Vec3(0, -CONTACT_EPS, 0);
        return findEarliestHit(center, down, hx, hy, hz, touching);
    }

    private static Penetration findDeepestPenetration(Vec3 center,
                                                       double hx, double hy, double hz,
                                                       Set<RobloxPart> touching) {
        Penetration best = null;
        for (RobloxPart part : COLLIDERS) {
            if (!part.canCollide() || part.transparency() >= 1) continue;
            if (isSlope(part)) {
                Penetration wedge = wedgePenetration(center, hx, hy, hz, part);
                if (wedge != null) {
                    touching.add(part);
                    if (best == null || wedge.depth < best.depth) best = wedge;
                }
                continue;
            }
            CFrameData cf = CFrameData.of(part);
            Vec3 local = cf.toLocal(center);
            Vec3 half = partLocalHalf(part);
            Vec3 ph = playerLocalHalf(cf.rotation(), hx, hy, hz);
            double minX = -half.x() - ph.x(), maxX = half.x() + ph.x();
            double minY = -half.y() - ph.y(), maxY = half.y() + ph.y();
            double minZ = -half.z() - ph.z(), maxZ = half.z() + ph.z();
            if (local.x() <= minX + EPS || local.x() >= maxX - EPS
                    || local.y() <= minY + EPS || local.y() >= maxY - EPS
                    || local.z() <= minZ + EPS || local.z() >= maxZ - EPS) continue;

            double ox = Math.min(local.x() - minX, maxX - local.x());
            double oy = Math.min(local.y() - minY, maxY - local.y());
            double oz = Math.min(local.z() - minZ, maxZ - local.z());
            if (ox <= EPS || oy <= EPS || oz <= EPS) continue;

            touching.add(part);

            // Prefer popping out the top when the player is clearly standing on
            // the part. The AABB-in-local approximation otherwise reports a
            // tiny sideways overlap on 1° rotations and shoves you off.
            Vec3 localNormal;
            double depth;
            boolean onTop = local.y() >= 0.0 && oy <= ox + CONTACT_EPS && oy <= oz + CONTACT_EPS;
            if (onTop) {
                depth = oy;
                localNormal = new Vec3(0, 1, 0);
            } else {
                depth = ox;
                localNormal = new Vec3(local.x() >= 0.0 ? 1 : -1, 0, 0);
                if (oy < depth) {
                    depth = oy;
                    localNormal = new Vec3(0, local.y() >= 0.0 ? 1 : -1, 0);
                }
                if (oz < depth) {
                    depth = oz;
                    localNormal = new Vec3(0, 0, local.z() >= 0.0 ? 1 : -1);
                }
            }
            Vec3 worldNormal = cf.rotate(localNormal);
            double len = remainingLength(worldNormal);
            if (len > EPS) worldNormal = worldNormal.mul(1.0 / len);
            if (best == null || depth < best.depth) best = new Penetration(part, depth, worldNormal);
        }
        return best;
    }

    private static Penetration wedgePenetration(Vec3 center, double hx, double hy, double hz, RobloxPart part) {
        if (!wedgeContains(center.x(), center.y(), center.z(), hx, hy, hz, part)) return null;
        CFrameData cf = CFrameData.of(part);
        Vec3 local = cf.toLocal(center);
        Vec3 half = partLocalHalf(part);
        Vec3 ph = playerLocalHalf(cf.rotation(), hx, hy, hz);
        Plane[] planes = wedgePlanes(part, half, ph);
        if (planes == null) return null;
        Plane bestPlane = null;
        double bestDepth = Double.POSITIVE_INFINITY;
        for (Plane plane : planes) {
            double s = plane.offset - local.dot(plane.normal);
            if (s <= EPS) continue;
            if (s < bestDepth) {
                bestDepth = s;
                bestPlane = plane;
            }
        }
        if (bestPlane == null) return null;
        Vec3 worldN = cf.rotate(bestPlane.normal);
        double len = remainingLength(worldN);
        if (len > EPS) worldN = worldN.mul(1.0 / len);
        return new Penetration(part, bestDepth, worldN);
    }

    private static SweepHit sweepPointAgainstOrientedBox(Vec3 start, Vec3 delta, RobloxPart part,
                                                         double hx, double hy, double hz) {
        CFrameData cf = CFrameData.of(part);
        Vec3 localStart = cf.toLocal(start);
        Vec3 localDelta = cf.rotateInverse(delta);
        Vec3 half = partLocalHalf(part);
        Vec3 ph = playerLocalHalf(cf.rotation(), hx, hy, hz);
        double minX = -half.x() - ph.x() - EPS, maxX = half.x() + ph.x() + EPS;
        double minY = -half.y() - ph.y() - EPS, maxY = half.y() + ph.y() + EPS;
        double minZ = -half.z() - ph.z() - EPS, maxZ = half.z() + ph.z() + EPS;

        double tEnter = 0.0;
        double tExit = 1.0;
        Vec3 enterNormalLocal = new Vec3(0, 0, 0);

        double[] result = slab(localStart.x(), localDelta.x(), minX, maxX, tEnter, tExit);
        if (result == null) return null;
        double oldEnter = tEnter;
        tEnter = result[0]; tExit = result[1];
        if (result[2] != 0 && result[3] >= oldEnter - 1.0e-12) enterNormalLocal = new Vec3(result[2], 0, 0);

        result = slab(localStart.y(), localDelta.y(), minY, maxY, tEnter, tExit);
        if (result == null) return null;
        oldEnter = tEnter;
        tEnter = result[0]; tExit = result[1];
        if (result[2] != 0 && result[3] >= oldEnter - 1.0e-12) enterNormalLocal = new Vec3(0, result[2], 0);

        result = slab(localStart.z(), localDelta.z(), minZ, maxZ, tEnter, tExit);
        if (result == null) return null;
        oldEnter = tEnter;
        tEnter = result[0]; tExit = result[1];
        if (result[2] != 0 && result[3] >= oldEnter - 1.0e-12) enterNormalLocal = new Vec3(0, 0, result[2]);

        if (tEnter > tExit || tExit < 0.0 || tEnter > 1.0) return null;
        if (tEnter <= EPS && pointInsideExpanded(localStart, minX, maxX, minY, maxY, minZ, maxZ)) return null;
        return new SweepHit(part, Math.max(0.0, tEnter), cf.rotate(enterNormalLocal));
    }

    private static Vec3 partLocalHalf(RobloxPart p) {
        return new Vec3(Math.abs(p.size().x()) * .5, Math.abs(p.size().y()) * .5, Math.abs(p.size().z()) * .5);
    }

    private static Vec3 playerLocalHalf(double[][] r, double hx, double hy, double hz) {
        return new Vec3(
                Math.abs(r[0][0]) * hx + Math.abs(r[1][0]) * hy + Math.abs(r[2][0]) * hz,
                Math.abs(r[0][1]) * hx + Math.abs(r[1][1]) * hy + Math.abs(r[2][1]) * hz,
                Math.abs(r[0][2]) * hx + Math.abs(r[1][2]) * hy + Math.abs(r[2][2]) * hz
        );
    }

    private static double[] slab(double start, double delta, double min, double max,
                                 double enter, double exit) {
        if (Math.abs(delta) < 1.0e-12) {
            if (start < min || start > max) return null;
            return new double[]{enter, exit, 0, Double.NEGATIVE_INFINITY};
        }
        final double t1;
        final double t2;
        final double normal;
        if (delta > 0.0) {
            t1 = (min - start) / delta;
            t2 = (max - start) / delta;
            normal = -1.0;
        } else {
            t1 = (max - start) / delta;
            t2 = (min - start) / delta;
            normal = 1.0;
        }
        if (t1 > enter) enter = t1;
        if (t2 < exit) exit = t2;
        if (enter > exit) return null;
        return new double[]{enter, exit, normal, t1};
    }

    private static boolean pointInsideExpanded(Vec3 p,
                                                double minX, double maxX,
                                                double minY, double maxY,
                                                double minZ, double maxZ) {
        return p.x() > minX && p.x() < maxX
                && p.y() > minY && p.y() < maxY
                && p.z() > minZ && p.z() < maxZ;
    }

    private static boolean nearby(Vec3 start, Vec3 delta, AABB b,
                                  double hx, double hy, double hz) {
        double endX = start.x() + delta.x();
        double endY = start.y() + delta.y();
        double endZ = start.z() + delta.z();
        double minX = Math.min(start.x(), endX) - hx;
        double maxX = Math.max(start.x(), endX) + hx;
        double minY = Math.min(start.y(), endY) - hy;
        double maxY = Math.max(start.y(), endY) + hy;
        double minZ = Math.min(start.z(), endZ) - hz;
        double maxZ = Math.max(start.z(), endZ) + hz;
        return maxX >= b.minX && minX <= b.maxX
                && maxY >= b.minY && minY <= b.maxY
                && maxZ >= b.minZ && minZ <= b.maxZ
                && Math.abs(start.x() - (b.minX + b.maxX) * .5) <= MAX_DISTANCE_STUDS + hx
                && Math.abs(start.y() - (b.minY + b.maxY) * .5) <= MAX_DISTANCE_STUDS + hy
                && Math.abs(start.z() - (b.minZ + b.maxZ) * .5) <= MAX_DISTANCE_STUDS + hz;
    }

    private static Vec3 toRoblox(double x, double y, double z) {
        return new Vec3(
                RobloxCoordinateSpace.toRoblox(x - RobloxPartRenderer.sceneOriginX()),
                RobloxCoordinateSpace.toRoblox(y - RobloxPartRenderer.sceneOriginY()),
                RobloxCoordinateSpace.toRoblox(z - RobloxPartRenderer.sceneOriginZ())
        );
    }

    private static Vec3 toMinecraftWorld(Vec3 roblox) {
        return new Vec3(
                RobloxPartRenderer.sceneOriginX() + RobloxCoordinateSpace.toMinecraft(roblox.x()),
                RobloxPartRenderer.sceneOriginY() + RobloxCoordinateSpace.toMinecraft(roblox.y()),
                RobloxPartRenderer.sceneOriginZ() + RobloxCoordinateSpace.toMinecraft(roblox.z())
        );
    }

    private static AABB partBounds(RobloxPart p) {
        Vec3 s = p.size().mul(.5);
        double[][] r = p.cframe().rotation();
        double ex = Math.abs(r[0][0]) * s.x() + Math.abs(r[0][1]) * s.y() + Math.abs(r[0][2]) * s.z();
        double ey = Math.abs(r[1][0]) * s.x() + Math.abs(r[1][1]) * s.y() + Math.abs(r[1][2]) * s.z();
        double ez = Math.abs(r[2][0]) * s.x() + Math.abs(r[2][1]) * s.y() + Math.abs(r[2][2]) * s.z();
        Vec3 c = p.cframe().position();
        return new AABB(c.x() - ex, c.y() - ey, c.z() - ez,
                c.x() + ex, c.y() + ey, c.z() + ez);
    }

    private static net.minecraft.world.phys.Vec3 projectOutVelocity(net.minecraft.world.phys.Vec3 velocity, Vec3 normal) {
        double vn = velocity.x * normal.x() + velocity.y * normal.y() + velocity.z * normal.z();
        if (vn >= 0.0) return velocity;
        return new net.minecraft.world.phys.Vec3(
                velocity.x - normal.x() * vn,
                velocity.y - normal.y() * vn,
                velocity.z - normal.z() * vn
        );
    }

    private static boolean sameAxis(Vec3 a, Vec3 b) {
        return Math.abs(a.x()) > 0.5 && Math.abs(b.x()) > 0.5
                || Math.abs(a.y()) > 0.5 && Math.abs(b.y()) > 0.5
                || Math.abs(a.z()) > 0.5 && Math.abs(b.z()) > 0.5;
    }

    private static double remainingLength(Vec3 v) {
        return Math.sqrt(v.x() * v.x() + v.y() * v.y() + v.z() * v.z());
    }

    private static double distanceSquared(double ax, double ay, double az,
                                          double bx, double by, double bz) {
        double x = ax - bx, y = ay - by, z = az - bz;
        return x * x + y * y + z * z;
    }

    private record Plane(Vec3 normal, double offset) {}

    private static final class SweepHit {
        final RobloxPart part;
        final double t;
        final Vec3 normal;
        SweepHit(RobloxPart part, double t, Vec3 normal) {
            this.part = part;
            this.t = t;
            this.normal = normal;
        }
    }

    private static final class Penetration {
        final RobloxPart part;
        final double depth;
        final Vec3 normal;
        Penetration(RobloxPart part, double depth, Vec3 normal) {
            this.part = part;
            this.depth = depth;
            this.normal = normal;
        }
    }
}
