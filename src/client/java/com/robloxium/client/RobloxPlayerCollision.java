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

/**
 * Stable Roblox player collision.
 *
 * The old implementation only tested the player's final AABB.  That means a
 * fast-moving player can move completely through a thin Roblox floor between
 * ticks.  This implementation sweeps the player's center from its previous
 * resolved position to its new position and collides against an AABB expanded
 * by the player's half extents.  The collision is therefore continuous for
 * the whole tick instead of being a discrete overlap test.
 *
 * Roblox parts are still represented by conservative world AABBs here.  That
 * keeps collision cheap and predictable while the renderer can use the exact
 * part transform for visuals.
 */
final class RobloxPlayerCollision {
    private static final double MAX_DISTANCE_STUDS = 96.0;
    private static final double EPS = 0.003;
    private static final double CONTACT_EPS = 0.08;
    // Legacy Roblox characters can walk up small Part steps instead of
    // treating every vertical face as an absolute wall.
    private static final double STEP_HEIGHT_STUDS = 1.4;
    private static final double STEP_CLEARANCE = 0.03;
    private static final double MAX_SWEEP_STUDS = 192.0;
    private static final int MAX_SWEEP_PASSES = 6;

    private static final Set<RobloxPart> TOUCHING =
            Collections.newSetFromMap(new IdentityHashMap<>());

    private static final List<RobloxPart> COLLIDERS = new ArrayList<>();
    private static int lastPartCount = -1;
    private static RobloxGame cachedGame;
    private static LocalPlayer trackedPlayer;

    private static boolean previousPositionValid;
    private static double previousX, previousY, previousZ;

    private static float lastMinecraftHealth = 20f, lastRobloxHealth = 100f;

    private RobloxPlayerCollision() {}

    static void resolve(Minecraft mc, RobloxGame game) {
        if (mc == null || game == null || !game.running() || mc.player == null) return;

        LocalPlayer player = mc.player;
        AABB playerBox = player.getBoundingBox();

        // The Minecraft entity position is the player's feet/base, while the
        // sweep operates on the center of the AABB. Reset state whenever the
        // active player or Roblox place changes so an old center is never
        // swept into a new scene.
        if (player != trackedPlayer || game != cachedGame) {
            trackedPlayer = player;
            previousPositionValid = false;
            TOUCHING.clear();
            COLLIDERS.clear();
            lastPartCount = -1;
        }

        // Build the collision list only once per tick.  The old code called
        // workspace().parts() on every solver pass, repeatedly walking the
        // entire Roblox instance tree.
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

        Vec3 current = toRoblox(
                (playerBox.minX + playerBox.maxX) * 0.5,
                (playerBox.minY + playerBox.maxY) * 0.5,
                (playerBox.minZ + playerBox.maxZ) * 0.5
        );

        // The first frame, or a real teleport, must not sweep from a stale
        // position across the whole map.
        if (!previousPositionValid || distanceSquared(current.x(), current.y(), current.z(),
                previousX, previousY, previousZ) > MAX_SWEEP_STUDS * MAX_SWEEP_STUDS) {
            previousX = current.x();
            previousY = current.y();
            previousZ = current.z();
            previousPositionValid = true;
        }

        Vec3 start = new Vec3(previousX, previousY, previousZ);
        Vec3 end = current;
        Vec3 resolved = start;
        Vec3 remaining = end.sub(start);
        boolean grounded = false;
        Vec3 collisionNormalA = null, collisionNormalB = null, collisionNormalC = null;
        Set<RobloxPart> nowTouching = Collections.newSetFromMap(new IdentityHashMap<>());

        // Continuous collision / sliding.  Each pass finds the earliest hit
        // along the remaining movement, moves to the contact point, then
        // removes only the velocity/movement component going into that face.
        for (int pass = 0; pass < MAX_SWEEP_PASSES; pass++) {
            SweepHit hit = findEarliestHit(resolved, remaining, halfX, halfY, halfZ, nowTouching);
            if (hit == null) {
                resolved = resolved.add(remaining);
                break;
            }

            nowTouching.add(hit.part);

            // Step up small Roblox Parts instead of stopping against their
            // vertical face.  We only do this for a horizontal wall hit and
            // only when the top of the Part is within the configured step
            // height above the player's feet.  A head-clearance test prevents
            // the solver from stepping the player into a low ceiling.
            if (Math.abs(hit.normal.y()) < 0.5) {
                Vec3 stepped = tryStepUp(resolved, remaining, halfX, halfY, halfZ, hit.part);
                if (stepped != null) {
                    resolved = stepped;
                    grounded = true;
                    continue;
                }
            }

            if (collisionNormalA == null) collisionNormalA = hit.normal;
            else if (!sameAxis(collisionNormalA, hit.normal) && collisionNormalB == null) collisionNormalB = hit.normal;
            else if (!sameAxis(collisionNormalA, hit.normal) && (collisionNormalB == null || !sameAxis(collisionNormalB, hit.normal)) && collisionNormalC == null) collisionNormalC = hit.normal;

            double travel = Math.max(0.0, hit.t - EPS / Math.max(1.0, remainingLength(remaining)));
            resolved = resolved.add(remaining.mul(travel));

            // Keep the player microscopically outside the surface.  This is
            // enough to prevent re-colliding with the same plane without
            // producing visible jitter.
            resolved = resolved.add(hit.normal.mul(EPS));

            if (hit.normal.y() > 0.5) grounded = true;

            double left = Math.max(0.0, 1.0 - hit.t);
            Vec3 afterHit = remaining.mul(left);
            double into = afterHit.dot(hit.normal);
            if (into < 0.0) {
                afterHit = afterHit.sub(hit.normal.mul(into));
            }

            // If there is effectively no movement left, finish here.
            if (remainingLength(afterHit) < EPS) {
                remaining = new Vec3(0, 0, 0);
                break;
            }
            remaining = afterHit;
        }

        // Sloped parts are convex ramps, not rectangular solids. Resolve the
        // player's feet against their real sloped top after the sweep so the
        // player follows the ramp instead of getting an invisible box wall.
        Vec3 slopeResolved = resolveSlopeSupport(resolved, halfX, halfY, halfZ, grounded, nowTouching);
        if (slopeResolved != null) {
            resolved = slopeResolved;
            grounded = true;
        }

        // Resolve an already-overlapping spawn/teleport without allowing the
        // old solver to accumulate corrections from every touching part.
        Penetration penetration = findDeepestPenetration(resolved, halfX, halfY, halfZ, nowTouching);
        if (penetration != null) {
            resolved = resolved.add(penetration.normal.mul(penetration.depth + EPS));
            if (penetration.normal.y() > 0.5) grounded = true;
        }

        // Convert the resolved Roblox center back to Minecraft coordinates.
        Vec3 mcResolved = toMinecraftWorld(resolved);
        double currentCenterX = (playerBox.minX + playerBox.maxX) * 0.5;
        double currentCenterY = (playerBox.minY + playerBox.maxY) * 0.5;
        double currentCenterZ = (playerBox.minZ + playerBox.maxZ) * 0.5;
        double correctedDistanceSq = (mcResolved.x() - currentCenterX) * (mcResolved.x() - currentCenterX)
                + (mcResolved.y() - currentCenterY) * (mcResolved.y() - currentCenterY)
                + (mcResolved.z() - currentCenterZ) * (mcResolved.z() - currentCenterZ);
        if (correctedDistanceSq > 1.0e-10) {
            // mcResolved is the AABB CENTER. Entity#setPos expects the
            // entity base/feet position, so only Y needs the half-height
            // offset. Passing the center directly here was the source of the
            // bad vertical collision behavior.
            double playerHalfHeightMc = (playerBox.maxY - playerBox.minY) * 0.5;
            player.setPos(mcResolved.x(), mcResolved.y() - playerHalfHeightMc, mcResolved.z());
        }

        net.minecraft.world.phys.Vec3 velocity = player.getDeltaMovement();

        // Use the actual collision normals rather than blindly zeroing a
        // whole velocity vector. This lets the player slide along walls and
        // prevents the next vanilla movement tick from pushing back into a
        // wall/floor and causing visible jitter.
        if (collisionNormalA != null) velocity = projectOutVelocity(velocity, collisionNormalA);
        if (collisionNormalB != null) velocity = projectOutVelocity(velocity, collisionNormalB);
        if (collisionNormalC != null) velocity = projectOutVelocity(velocity, collisionNormalC);

        // A tiny downward probe handles the resting-contact case where the
        // player did not move this tick but is exactly on top of a Roblox part.
        SweepHit velocityHit = findVelocityCollision(resolved, halfX, halfY, halfZ, nowTouching);
        if (velocityHit != null && velocityHit.normal.y() > 0.5) grounded = true;
        if (velocityHit != null) velocity = projectOutVelocity(velocity, velocityHit.normal);

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

        // First make sure the raised player has room above the step.
        for (RobloxPart part : COLLIDERS) {
            if (!part.canCollide() || part.transparency() >= 1 || part == obstacle) continue;
            if (aabbOverlaps(center.x(), newCenterY, center.z(), hx, hy, hz, partBounds(part))) return null;
        }

        // Then make sure the intended horizontal movement is clear at the
        // raised height. This prevents tall blocks from becoming climbable
        // merely because their bottom face is near the player's feet.
        double testX = center.x() + remaining.x();
        double testZ = center.z() + remaining.z();
        for (RobloxPart part : COLLIDERS) {
            if (!part.canCollide() || part.transparency() >= 1 || part == obstacle) continue;
            if (aabbOverlaps(testX, newCenterY, testZ, hx, hy, hz, partBounds(part))) return null;
        }

        // Lift to the top and preserve the horizontal remainder.
        return new Vec3(center.x(), newCenterY, center.z());
    }

    private static boolean aabbOverlaps(double centerX, double centerY, double centerZ,
                                        double hx, double hy, double hz, AABB b) {
        return centerX + hx > b.minX + EPS && centerX - hx < b.maxX - EPS
                && centerY + hy > b.minY + EPS && centerY - hy < b.maxY - EPS
                && centerZ + hz > b.minZ + EPS && centerZ - hz < b.maxZ - EPS;
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

            // Wedges are handled by the sloped-support solver below. Using
            // their enclosing box here would turn the low end of every ramp
            // into a solid vertical wall.
            if (isSlope(part)) continue;

            SweepHit hit = sweepPointAgainstExpandedBox(start, delta, bounds, hx, hy, hz, part);
            if (hit != null && (best == null || hit.t < best.t)) best = hit;
        }
        return best;
    }

    private static boolean isSlope(RobloxPart p) {
        return "WedgePart".equalsIgnoreCase(p.className())
                || "CornerWedgePart".equalsIgnoreCase(p.className());
    }

    /**
     * Resolves the player's feet against the actual sloped surface of a
     * WedgePart.  The part is evaluated in its own CFrame, so rotated ramps
     * follow their Roblox orientation instead of using a world AABB slope.
     *
     * WedgePart is the triangular prism used by old Roblox: after the renderer
     * flip, its high edge is +local Z and its top is
     * localY = (localZ/localHalfZ) * halfY.
     */
    private static Vec3 resolveSlopeSupport(Vec3 center, double hx, double hy, double hz,
                                            boolean wasGrounded, Set<RobloxPart> touching) {
        Vec3 best = null;
        double bestTop = -Double.MAX_VALUE;

        for (RobloxPart part : COLLIDERS) {
            if (!part.canCollide() || part.transparency() >= 1 || !isSlope(part)) continue;

            CFrameData cf = CFrameData.of(part);
            Vec3 local = cf.toLocal(center);

            double halfX = Math.abs(part.size().x()) * .5;
            double halfY = Math.abs(part.size().y()) * .5;
            double halfZ = Math.abs(part.size().z()) * .5;
            if (halfX < EPS || halfY < EPS || halfZ < EPS) continue;

            // Check the player's footprint in the ramp's local X/Z plane.
            double footprintX = hx;
            double footprintZ = hz;
            if (local.x() + footprintX < -halfX || local.x() - footprintX > halfX
                    || local.z() + footprintZ < -halfZ || local.z() - footprintZ > halfZ) continue;

            double z0 = Math.max(-halfZ, local.z() - footprintZ);
            double z1 = Math.min(halfZ, local.z() + footprintZ);

            double topLocal;
            if ("WedgePart".equalsIgnoreCase(part.className())) {
                // High edge is +Z after the requested flip.
                double top0 = (z0 / halfZ) * halfY;
                double top1 = (z1 / halfZ) * halfY;
                topLocal = Math.max(top0, top1);
            } else {
                // CornerWedgePart slopes upward toward +X,+Z. Use the highest
                // point covered by the player's footprint.
                double x0 = Math.max(-halfX, local.x() - footprintX);
                double x1 = Math.min(halfX, local.x() + footprintX);
                double zA = Math.max(-halfZ, local.z() - footprintZ);
                double zB = Math.min(halfZ, local.z() + footprintZ);
                double topA = Math.min(x1 / halfX, zB / halfZ) * halfY;
                double topB = Math.min(x1 / halfX, zA / halfZ) * halfY;
                double topC = Math.min(x0 / halfX, zB / halfZ) * halfY;
                double topD = Math.min(x0 / halfX, zA / halfZ) * halfY;
                topLocal = Math.max(Math.max(topA, topB), Math.max(topC, topD));
            }

            Vec3 topWorld = cf.toWorld(new Vec3(local.x(), topLocal, local.z()));
            double feet = center.y() - hy;

            // Only support from above/near the surface. Do not teleport a player
            // through a ramp from underneath it.
            double verticalGap = topWorld.y() - feet;
            if (verticalGap < -CONTACT_EPS || verticalGap > STEP_HEIGHT_STUDS + CONTACT_EPS) continue;

            // Require the player's footprint to actually overlap the ramp's
            // horizontal projection in world space.
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
        return false;
    }

    private record CFrameData(Vec3 position, double[][] rotation) {
        static CFrameData of(RobloxPart p) {
            return new CFrameData(p.cframe().position(), p.cframe().rotation());
        }
        Vec3 toLocal(Vec3 world) {
            Vec3 d = world.sub(position);
            return new Vec3(
                    rotation[0][0] * d.x() + rotation[1][0] * d.y() + rotation[2][0] * d.z(),
                    rotation[0][1] * d.x() + rotation[1][1] * d.y() + rotation[2][1] * d.z(),
                    rotation[0][2] * d.x() + rotation[1][2] * d.y() + rotation[2][2] * d.z());
        }
        Vec3 toWorld(Vec3 local) {
            return position.add(new Vec3(
                    rotation[0][0] * local.x() + rotation[0][1] * local.y() + rotation[0][2] * local.z(),
                    rotation[1][0] * local.x() + rotation[1][1] * local.y() + rotation[1][2] * local.z(),
                    rotation[2][0] * local.x() + rotation[2][1] * local.y() + rotation[2][2] * local.z()));
        }
    }

    private static SweepHit findVelocityCollision(Vec3 center, double hx, double hy, double hz,
                                                   Set<RobloxPart> touching) {
        // A tiny probe catches a player that is standing exactly on a floor
        // after the sweep has finished, without treating arbitrary nearby
        // walls as a collision.
        Vec3 down = new Vec3(0, -CONTACT_EPS, 0);
        return findEarliestHit(center, down, hx, hy, hz, touching);
    }

    private static Penetration findDeepestPenetration(Vec3 center,
                                                       double hx, double hy, double hz,
                                                       Set<RobloxPart> touching) {
        Penetration best = null;
        for (RobloxPart part : COLLIDERS) {
            if (!part.canCollide() || part.transparency() >= 1 || isSlope(part)) continue;
            AABB b = partBounds(part);
            double minX = b.minX - hx, maxX = b.maxX + hx;
            double minY = b.minY - hy, maxY = b.maxY + hy;
            double minZ = b.minZ - hz, maxZ = b.maxZ + hz;

            double ox = Math.min(center.x(), maxX) - Math.max(center.x(), minX);
            double oy = Math.min(center.y(), maxY) - Math.max(center.y(), minY);
            double oz = Math.min(center.z(), maxZ) - Math.max(center.z(), minZ);
            if (ox <= EPS || oy <= EPS || oz <= EPS) continue;

            double depth = ox;
            Vec3 normal = new Vec3(center.x() >= (minX + maxX) * .5 ? 1 : -1, 0, 0);
            if (oy < depth) {
                depth = oy;
                normal = new Vec3(0, center.y() >= (minY + maxY) * .5 ? 1 : -1, 0);
            }
            if (oz < depth) {
                depth = oz;
                normal = new Vec3(0, 0, center.z() >= (minZ + maxZ) * .5 ? 1 : -1);
            }

            touching.add(part);
            if (best == null || depth < best.depth) best = new Penetration(part, depth, normal);
        }
        return best;
    }

    private static SweepHit sweepPointAgainstExpandedBox(Vec3 start, Vec3 delta, AABB bounds,
                                                          double hx, double hy, double hz,
                                                          RobloxPart part) {
        double minX = bounds.minX - hx - EPS, maxX = bounds.maxX + hx + EPS;
        double minY = bounds.minY - hy - EPS, maxY = bounds.maxY + hy + EPS;
        double minZ = bounds.minZ - hz - EPS, maxZ = bounds.maxZ + hz + EPS;

        double tEnter = 0.0;
        double tExit = 1.0;
        Vec3 enterNormal = new Vec3(0, 0, 0);

        double[] result = slab(start.x(), delta.x(), minX, maxX, tEnter, tExit);
        if (result == null) return null;
        double oldEnter = tEnter;
        tEnter = result[0]; tExit = result[1];
        if (result[2] != 0 && result[3] >= oldEnter - 1.0e-12) enterNormal = new Vec3(result[2], 0, 0);

        result = slab(start.y(), delta.y(), minY, maxY, tEnter, tExit);
        if (result == null) return null;
        oldEnter = tEnter;
        tEnter = result[0]; tExit = result[1];
        if (result[2] != 0 && result[3] >= oldEnter - 1.0e-12) enterNormal = new Vec3(0, result[2], 0);

        result = slab(start.z(), delta.z(), minZ, maxZ, tEnter, tExit);
        if (result == null) return null;
        oldEnter = tEnter;
        tEnter = result[0]; tExit = result[1];
        if (result[2] != 0 && result[3] >= oldEnter - 1.0e-12) enterNormal = new Vec3(0, 0, result[2]);

        if (tEnter > tExit || tExit < 0.0 || tEnter > 1.0) return null;

        // If already inside, don't use a zero-length sweep hit.  The
        // penetration pass below gives this case a stable minimal correction.
        if (tEnter <= EPS && pointInsideExpanded(start, minX, maxX, minY, maxY, minZ, maxZ)) return null;

        return new SweepHit(part, Math.max(0.0, tEnter), enterNormal);
    }

    /** Returns {enter, exit, normalSign}. */
    private static double[] slab(double start, double delta, double min, double max,
                                 double enter, double exit) {
        if (Math.abs(delta) < 1.0e-12) {
            if (start < min || start > max) return null;
            return new double[]{enter, exit, 0, Double.NEGATIVE_INFINITY};
        }

        // The entry face depends on the direction of travel.  Do not flip
        // the normal after swapping the intersection times: for negative
        // motion the entry face is MAX (normal +1), and for positive motion
        // it is MIN (normal -1).  The old code flipped the negative-motion
        // normal to -1, which made falling through floors and walking into
        // walls push the player farther INTO the part instead of out of it.
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
