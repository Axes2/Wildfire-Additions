package com.axes.wildfireadditions.util;

import com.axes.wildfireadditions.registry.ModParticles;
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
 *
 * <p>Visually, the stream is no longer painted along a precomputed arc: {@link #emitHoseStream} and
 * {@link #emitTurretStream} launch physics-driven droplet particles from the nozzle each tick and the
 * droplets fly the arc themselves (see {@code WaterJetParticle}), so water genuinely travels from the
 * nozzle to the fire. Gameplay-side, the douse is scheduled through {@code WaterDouseQueue} with the
 * traced flight time, so the fire starts going out when the water visibly arrives.
 */
public final class WaterStream {

    private WaterStream() {
    }

    // Ballistic constants shared by every water stream. The water is simulated as a gravity-affected
    // projectile rather than an instant ray, so reach depends on aim angle and height and the stream
    // visibly arcs down over its flight. GRAVITY is also the exact per-tick acceleration the client's
    // WaterJetParticle integrates (16 / 400 = vanilla's 0.04 blocks/tick^2), keeping the visible
    // droplets and the authoritative server trace on the same arc.
    public static final double GRAVITY = 16.0; // blocks/second^2 applied to the stream's fall
    private static final double STEP_DT = 0.025; // simulation resolution, in seconds
    private static final int MAX_STEPS = 400; // hard safety cap (10s of flight time), should never actually be hit

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

    // --- Continuous nozzle emission (client-side visuals) -------------------------------------------
    // Droplets are spawned only at the nozzle, carrying the stream's real exit velocity, and fly their
    // own ballistic arcs client-side (WaterJetParticle integrates the same GRAVITY as traceTrajectory,
    // collides with the world itself and splashes where it personally lands). Each tick's batch is
    // spread across several sub-tick emission points along the first tick of travel, so consecutive
    // batches join into one unbroken rope of water with no beading. The fan-out of the stream downrange
    // comes from small *angular* jitter at the nozzle - exactly how a real jet spreads - rather than the
    // old positional-jitter-by-distance hack. Speeds are converted from blocks/second (the simulation's
    // unit) to blocks/tick (the particle engine's unit) here.

    // The handheld line: a solid cinematic stream.
    private static final int HOSE_SUBSTEPS = 5; // sub-tick emission points per tick
    private static final int HOSE_DROPLETS_PER_STEP = 3; // droplets at each emission point
    private static final double HOSE_ANGLE_JITTER = 0.036; // ~2 degrees of nozzle spread, for a wider fan
    private static final int HOSE_NOZZLE_MIST = 2; // white pressure-mist wisps per tick at the nozzle

    // The sprinkler monitor: faster, denser and mistier, so it reads as high pressure next to the hose.
    private static final int TURRET_SUBSTEPS = 6;
    private static final int TURRET_DROPLETS_PER_STEP = 4;
    private static final double TURRET_ANGLE_JITTER = 0.026; // still a touch tighter than the handheld line
    private static final int TURRET_NOZZLE_MIST = 3;

    public static void emitHoseStream(Level level, Vec3 origin, Vec3 direction, double speed) {
        emitStream(level, origin, direction, speed, HOSE_SUBSTEPS, HOSE_DROPLETS_PER_STEP, HOSE_ANGLE_JITTER, HOSE_NOZZLE_MIST);
    }

    public static void emitTurretStream(Level level, Vec3 origin, Vec3 direction, double speed) {
        emitStream(level, origin, direction, speed, TURRET_SUBSTEPS, TURRET_DROPLETS_PER_STEP, TURRET_ANGLE_JITTER, TURRET_NOZZLE_MIST);
    }

    private static void emitStream(Level level, Vec3 origin, Vec3 direction, double speed,
                                   int substeps, int dropletsPerStep, double angleJitter, int nozzleMist) {
        RandomSource random = level.random;
        Vec3 dir = direction.normalize();
        double speedPerTick = speed / 20.0;

        // White aerated spray blasting out of the nozzle - the immediate "this is pressurised" read.
        for (int i = 0; i < nozzleMist; i++) {
            Vec3 p = jitterPerpendicular(origin.add(dir.scale(0.1 + random.nextDouble() * 0.35)), dir, random, 0.07);
            double push = (0.30 + random.nextDouble() * 0.25) * speedPerTick;
            level.addParticle(ModParticles.WATER_MIST.get(), p.x, p.y, p.z, dir.x * push, dir.y * push, dir.z * push);
        }

        for (int s = 0; s < substeps; s++) {
            // Fractional position within this tick's advance; randomised inside its slot so the rope of
            // droplets never shows a repeating pattern.
            double frac = (s + random.nextDouble()) / substeps;
            for (int d = 0; d < dropletsPerStep; d++) {
                Vec3 velocity = jitterDirection(dir, random, angleJitter)
                        .scale((0.97 + random.nextDouble() * 0.06) * speedPerTick);
                Vec3 p = jitterPerpendicular(origin.add(velocity.scale(frac)), dir, random, 0.03);
                level.addParticle(ModParticles.WATER_JET.get(), p.x, p.y, p.z, velocity.x, velocity.y, velocity.z);
            }
        }
    }

    // Tilts a unit direction by a small random angle (magnitude ~ radians for small values), by nudging
    // the vector's tip perpendicular to itself and renormalising.
    public static Vec3 jitterDirection(Vec3 direction, RandomSource random, double magnitude) {
        return jitterPerpendicular(direction, direction, random, magnitude).normalize();
    }

    // Server-side: the 3x3x3 douse pass at the point the stream lands. Identical for the hose and the
    // turret - continuous sizzle/steam every pass, a hiss when the stream first bites, actual cooling
    // gated to COOL_PERIOD (so dousing a block takes real, sustained spray), and a heavier steam column
    // plus lower hiss the moment a block finally goes out. `ticker` is the level's game time (passes
    // arrive via WaterDouseQueue): sustained spray delivers a pass every 2 ticks, so accepting a 2-tick
    // window each COOL_PERIOD cools exactly once per period regardless of what parity the water's
    // flight time put the arrivals on.
    public static void extinguishAt(ServerLevel serverLevel, BlockPos center, long ticker) {
        boolean playedHiss = false;
        boolean playedDouseBurst = false;
        boolean coolFireThisPass = ticker % COOL_PERIOD < 2;

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

    // Nudges `point` sideways by a small random amount, perpendicular to `direction`, so emitted
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
