package com.axes.wildfireadditions.util;

import dev.protomanly.pmweather.block.PMWFireBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.jetbrains.annotations.Nullable;

/**
 * The shared water-stream simulation and extinguishing logic used by both the handheld {@code HoseItem}
 * and the placed fire sprinkler turret. Pulling it out here means the turret sprays with the exact same
 * arc physics, particle look, sizzle/steam feedback and cooling math as a player working a hose - the
 * only things the two callers vary are the nozzle speed and reach (a fixed high-pressure monitor throws
 * further than a handheld line).
 */
public final class WaterStream {

    private WaterStream() {
    }

    // Ballistic constants shared by every water stream. The water is simulated as a gravity-affected
    // projectile rather than an instant ray, so reach depends on aim angle and height and the stream
    // visibly arcs down over its flight.
    public static final double GRAVITY = 16.0; // blocks/second^2 applied to the stream's fall
    private static final double STEP_DT = 0.025; // simulation resolution, in seconds
    private static final int MAX_STEPS = 400; // hard safety cap (10s of flight time), should never actually be hit

    // Visual stream density/volume. The whole arc (nozzle to impact) is populated with droplets every
    // single pass so it always reads as one solid, continuous stream instead of a single clump crawling
    // down the arc.
    private static final int TIME_SAMPLES_PER_PASS = 14; // points spread evenly along the whole arc
    private static final int DROPLETS_PER_SAMPLE = 2; // independently-jittered droplets per point, for thickness
    private static final double SPREAD_NEAR_NOZZLE = 0.05; // blocks of positional jitter right at the nozzle
    private static final double SPREAD_AT_TARGET = 0.4; // blocks of positional jitter by the time it lands

    // Extinguishing strength, shared so the hose and the turret are exactly as effective as each other.
    // Cooling now ticks twice a second (COOL_PERIOD = 10 rather than a full 20-tick second) so even a
    // raging block is fully doused in roughly 1.5-2s of direct spray instead of the old 3-4s.
    public static final int COOL_PERIOD = 10; // ticks between actual intensity reductions
    private static final int INTENSITY_STEP = 3; // how much a PMWFireBlock cools per reduction

    // Where a simulated water arc ends up: either the block it hit, or its last position before
    // dissipating past the max range (hitBlock null in that case), and how long (in simulated seconds)
    // it took to get there.
    public record TrajectoryResult(Vec3 endPosition, @Nullable BlockPos hitBlock, double flightTime) {
    }

    // The position of the stream at flight time `t`, assuming a clear path - only valid for
    // t <= a trace's own flightTime, since that's the only span guaranteed collision-free.
    public static Vec3 positionAtTime(Vec3 origin, Vec3 direction, double speed, double t) {
        Vec3 dir = direction.normalize();
        return new Vec3(
                origin.x + dir.x * speed * t,
                origin.y + dir.y * speed * t - 0.5 * GRAVITY * t * t,
                origin.z + dir.z * speed * t
        );
    }

    // Simulates the water as a gravity-affected projectile down `direction` from `origin`. This is the
    // sole source of truth for where a stream actually lands - the visual particles are a separate,
    // merely approximate depiction of this same arc. `source`, if non-null, is excluded from collision
    // (so a player's own hose stream doesn't clip on the player); the turret passes null.
    public static TrajectoryResult traceTrajectory(Level level, @Nullable Entity source, Vec3 origin, Vec3 direction, double speed, double maxRange) {
        Vec3 pos = origin;
        Vec3 velocity = direction.normalize().scale(speed);
        CollisionContext ctx = source == null ? CollisionContext.empty() : CollisionContext.of(source);

        for (int step = 0; step < MAX_STEPS; step++) {
            double elapsed = step * STEP_DT;
            Vec3 nextPos = pos.add(velocity.scale(STEP_DT));

            if (nextPos.distanceTo(origin) > maxRange) {
                return new TrajectoryResult(pos, null, elapsed);
            }

            HitResult hit = level.clip(new ClipContext(pos, nextPos, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, ctx));
            if (hit.getType() == HitResult.Type.BLOCK) {
                BlockHitResult blockHit = (BlockHitResult) hit;
                return new TrajectoryResult(blockHit.getLocation(), blockHit.getBlockPos(), elapsed + STEP_DT);
            }

            pos = nextPos;
            velocity = velocity.add(0, -GRAVITY * STEP_DT, 0);
        }
        return new TrajectoryResult(pos, null, MAX_STEPS * STEP_DT);
    }

    // How close (in blocks) the simulated arc must pass to a fire block for the turret to consider it
    // reachable. Fire blocks have no collision, so an aimed stream sails straight through them; we detect
    // a hit by proximity of the arc to the fire rather than by where the stream eventually lands.
    public static final double TARGET_TOLERANCE = 1.25;

    // The result of tracing an arc toward a specific target point: whether the arc passed within
    // TARGET_TOLERANCE of it before hitting any solid block, where it got to, and the flight time there.
    public record TargetTrace(boolean reached, Vec3 endPosition, double flightTime) {
    }

    // Traces the arc from `origin` down `direction` and reports whether it reaches `targetCenter` (passes
    // within `tolerance`) before a solid block blocks it. This is how the turret does both line-of-sight
    // ("can the water actually get to this fire, or is there a wall in the way?") and visual truncation -
    // fire blocks don't collide, so a plain landing-point trace would sail through the fire and stop far
    // beyond it, which is exactly the trap the old code fell into.
    public static TargetTrace traceToTarget(Level level, Vec3 origin, Vec3 direction, double speed, double maxRange, Vec3 targetCenter, double tolerance) {
        Vec3 pos = origin;
        Vec3 velocity = direction.normalize().scale(speed);
        double tolSq = tolerance * tolerance;

        for (int step = 0; step < MAX_STEPS; step++) {
            double elapsed = step * STEP_DT;

            if (pos.distanceToSqr(targetCenter) <= tolSq) {
                return new TargetTrace(true, pos, elapsed); // arc reached the fire
            }

            Vec3 nextPos = pos.add(velocity.scale(STEP_DT));
            if (nextPos.distanceTo(origin) > maxRange) {
                return new TargetTrace(false, pos, elapsed); // dissipated short of the fire
            }

            HitResult hit = level.clip(new ClipContext(pos, nextPos, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()));
            if (hit.getType() == HitResult.Type.BLOCK) {
                return new TargetTrace(false, ((BlockHitResult) hit).getLocation(), elapsed + STEP_DT); // blocked by cover
            }

            pos = nextPos;
            velocity = velocity.add(0, -GRAVITY * STEP_DT, 0);
        }
        return new TargetTrace(false, pos, MAX_STEPS * STEP_DT);
    }

    // Client-side: hand-place FALLING_WATER particles along the simulated arc, from the nozzle up to
    // `flightTime` (the caller passes the time at which the arc reaches its impact/target, so the visible
    // stream stops there instead of overshooting). The whole drawn span is populated every pass so it
    // reads as one solid stream; a small `animTick`-driven phase offset keeps it looking alive. A SPLASH
    // burst is placed at `impact` (the fire, or the surface the stream lands on) when non-null.
    public static void spawnStreamParticles(Level level, Vec3 origin, Vec3 direction, double speed, double flightTime, @Nullable Vec3 impact, long animTick) {
        flightTime = Math.max(flightTime, 0.001);
        double spacing = flightTime / TIME_SAMPLES_PER_PASS;
        double phase = (animTick / 20.0) % spacing;

        for (int i = 0; i < TIME_SAMPLES_PER_PASS; i++) {
            double t = i * spacing + phase;
            if (t > flightTime) continue;

            Vec3 point = positionAtTime(origin, direction, speed, t);
            double spread = SPREAD_NEAR_NOZZLE + (t / flightTime) * (SPREAD_AT_TARGET - SPREAD_NEAR_NOZZLE);

            for (int d = 0; d < DROPLETS_PER_SAMPLE; d++) {
                Vec3 droplet = jitterPerpendicular(point, direction, level.random, spread);
                level.addParticle(ParticleTypes.FALLING_WATER, droplet.x, droplet.y, droplet.z, 0.0, 0.0, 0.0);
            }
        }

        if (impact != null) {
            level.addParticle(ParticleTypes.SPLASH, impact.x, impact.y, impact.z, 0.0, 0.1, 0.0);
        }
    }

    // Server-side: the 3x3x3 douse pass at the point the stream lands. Identical for the hose and the
    // turret - continuous sizzle/steam every pass, a hiss when the stream first bites, actual cooling
    // gated to COOL_PERIOD (so dousing a block takes real, sustained spray), and a heavier steam column
    // plus lower hiss the moment a block finally goes out. `ticker` is the caller's own tick counter and
    // is what paces the cooling cadence.
    public static void extinguishAt(ServerLevel serverLevel, BlockPos center, long ticker) {
        boolean playedHiss = false;
        boolean playedDouseBurst = false;
        boolean coolFireThisPass = ticker % COOL_PERIOD == 0;

        for (int x = -1; x <= 1; x++) {
            for (int y = -1; y <= 1; y++) {
                for (int z = -1; z <= 1; z++) {
                    BlockPos checkPos = center.offset(x, y, z);
                    BlockState state = serverLevel.getBlockState(checkPos);

                    // PMWeather specific fire logic
                    if (state.getBlock() instanceof PMWFireBlock) {
                        int currentIntensity = state.getValue(PMWFireBlock.INTENSITY);
                        double px = checkPos.getX() + 0.5, py = checkPos.getY() + 0.5, pz = checkPos.getZ() + 0.5;

                        int steamCount = 2 + currentIntensity / 2;
                        serverLevel.sendParticles(ParticleTypes.CLOUD, px, py, pz, steamCount, 0.2, 0.15, 0.2, 0.03);
                        serverLevel.sendParticles(ParticleTypes.LARGE_SMOKE, px, py, pz, 1, 0.15, 0.2, 0.15, 0.01);
                        if (!playedHiss) {
                            serverLevel.playSound(null, checkPos, SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 0.5f, 1.4f + (serverLevel.random.nextFloat() * 0.2f));
                            playedHiss = true;
                        }

                        if (!coolFireThisPass) continue;

                        int newIntensity = currentIntensity - INTENSITY_STEP;
                        if (newIntensity <= 0) {
                            serverLevel.removeBlock(checkPos, false);

                            serverLevel.sendParticles(ParticleTypes.CLOUD, px, py, pz, 25, 0.3, 0.25, 0.3, 0.08);
                            serverLevel.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, px, py + 0.2, pz, 7, 0.2, 0.1, 0.2, 0.02);
                            if (!playedDouseBurst) {
                                serverLevel.playSound(null, checkPos, SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 1.0f, 0.6f + (serverLevel.random.nextFloat() * 0.2f));
                                playedDouseBurst = true;
                            }
                        } else {
                            serverLevel.setBlockAndUpdate(checkPos, state.setValue(PMWFireBlock.INTENSITY, newIntensity));
                        }
                    }
                    // Vanilla fire fallback
                    else if (state.is(Blocks.FIRE)) {
                        serverLevel.removeBlock(checkPos, false);
                        double px = checkPos.getX() + 0.5, py = checkPos.getY() + 0.5, pz = checkPos.getZ() + 0.5;
                        serverLevel.sendParticles(ParticleTypes.CLOUD, px, py, pz, 12, 0.25, 0.2, 0.25, 0.05);
                        if (!playedDouseBurst) {
                            serverLevel.playSound(null, checkPos, SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 1.0f, 0.6f + (serverLevel.random.nextFloat() * 0.2f));
                            playedDouseBurst = true;
                        }
                    }
                    // Spraying normal blocks (wetting them)
                    else if (!state.isAir()) {
                        if (serverLevel.random.nextInt(10) == 0) {
                            serverLevel.sendParticles(ParticleTypes.DRIPPING_WATER, checkPos.getX() + 0.5, checkPos.getY() + 1.0, checkPos.getZ() + 0.5, 2, 0.3, 0.1, 0.3, 0.0);
                        }
                    }
                }
            }
        }
    }

    // Nudges `point` sideways by a small random amount, perpendicular to `direction`, so the hand-placed
    // droplets read as a bit of a spray rather than a perfectly straight line of dots.
    public static Vec3 jitterPerpendicular(Vec3 point, Vec3 direction, RandomSource random, double magnitude) {
        Vec3 up = Math.abs(direction.y) > 0.99 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
        Vec3 right = direction.cross(up).normalize();
        Vec3 trueUp = right.cross(direction).normalize();

        double rx = (random.nextDouble() - 0.5) * 2.0 * magnitude;
        double ry = (random.nextDouble() - 0.5) * 2.0 * magnitude;

        return point.add(right.scale(rx)).add(trueUp.scale(ry));
    }

    /**
     * Solves for the (unit) launch direction that lands a ballistic stream of the given nozzle
     * {@code speed} on {@code target}, firing from {@code from}. Returns the low-arc solution, or
     * {@code null} if the target is simply out of ballistic range. Used by the turret to aim; the hose
     * doesn't need this since a player aims it directly.
     */
    @Nullable
    public static Vec3 solveBallisticAim(Vec3 from, Vec3 target, double speed) {
        double dx = target.x - from.x;
        double dy = target.y - from.y;
        double dz = target.z - from.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);

        // Target essentially straight up/down: just fire vertically toward it.
        if (horiz < 1.0e-4) {
            return new Vec3(0, dy >= 0 ? 1 : -1, 0);
        }

        double v2 = speed * speed;
        double disc = v2 * v2 - GRAVITY * (GRAVITY * horiz * horiz + 2.0 * dy * v2);
        if (disc < 0) return null; // beyond the reach of this nozzle

        double tanTheta = (v2 - Math.sqrt(disc)) / (GRAVITY * horiz); // low, flat arc
        double theta = Math.atan(tanTheta);
        double cos = Math.cos(theta);
        double sin = Math.sin(theta);

        double hx = dx / horiz;
        double hz = dz / horiz;
        // Already unit length: (hx*cos)^2 + (hz*cos)^2 + sin^2 = cos^2 + sin^2 = 1.
        return new Vec3(hx * cos, sin, hz * cos);
    }
}
