package com.axes.wildfireadditions.item;

import dev.protomanly.pmweather.block.PMWFireBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public class HoseItem extends Item {

    // Ballistic constants for the water stream. Tuned so a level shot from head height reaches
    // roughly 10-12 blocks before it falls to the ground, a shot angled up ~20-30 degrees (the
    // "correct" way to aim a hose for distance) reaches close to the ~20 block cap, and firing
    // from up high extends the reach further since the water has longer to fall before it lands -
    // all an emergent result of simulating the arc rather than a straight ray.
    private static final double STREAM_SPEED = 22.0; // blocks/second, nozzle exit speed
    private static final double GRAVITY = 16.0; // blocks/second^2 applied to the stream's fall
    private static final double MAX_RANGE = 20.0; // straight-line distance from the nozzle before the stream dissipates
    private static final double STEP_DT = 0.025; // simulation resolution, in seconds
    private static final int MAX_STEPS = 400; // hard safety cap (10s of flight time), should never actually be hit

    private static final int TICK_INTERVAL = 2; // how often onUseTick's logic actually runs

    // Visual stream density/volume. The whole arc (nozzle to impact) is populated with droplets
    // every single pass - rather than only revealing a small crawling segment of it - so it always
    // reads as one solid, continuous stream instead of a single clump travelling down the arc.
    private static final int TIME_SAMPLES_PER_PASS = 14; // points spread evenly along the whole arc
    private static final int DROPLETS_PER_SAMPLE = 2; // independently-jittered droplets per point, for thickness
    private static final double SPREAD_NEAR_NOZZLE = 0.05; // blocks of positional jitter right at the nozzle
    private static final double SPREAD_AT_TARGET = 0.4; // blocks of positional jitter by the time it lands

    public HoseItem(Properties properties) {
        super(properties);
    }

    @Override
    public UseAnim getUseAnimation(ItemStack stack) {
        return UseAnim.BOW;
    }

    @Override
    public int getUseDuration(ItemStack stack, LivingEntity entity) {
        return 72000;
    }

    // Triggered when the player first clicks right-click
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        player.startUsingItem(hand);
        return InteractionResultHolder.consume(player.getItemInHand(hand));
    }

    // Add this inside HoseItem.java
    @Override
    public boolean shouldCauseReequipAnimation(ItemStack oldStack, ItemStack newStack, boolean slotChanged) {
        // Prevents the item from bobbing when we update the physics nodes in the background
        return oldStack.getItem() != newStack.getItem();
    }

    // Triggered continuously while the player holds right-click
    @Override
    public void onUseTick(Level level, LivingEntity livingEntity, ItemStack stack, int remainingUseDuration) {
        if (!(livingEntity instanceof Player player)) return;

        // Run logic every 2 ticks to balance performance and responsiveness
        if (player.tickCount % TICK_INTERVAL != 0) return;

        Vec3 eyePos = player.getEyePosition();
        Vec3 lookVec = player.getLookAngle();

        // Client-side: hand-place FALLING_WATER particles directly along the real simulated arc -
        // this is fully deterministic and always visible, unlike giving vanilla's own particle
        // physics a velocity and letting it fly (SPLASH turned out not to render as a travelling
        // droplet at all - it's built for a quick impact flash, not sustained flight).
        //
        // The whole arc, nozzle to impact, gets populated with droplets every single pass, so it
        // always reads as one solid, continuous stream rather than a single clump crawling down the
        // arc. A small phase offset - tied to the player's tick count, so it continuously drifts
        // rather than resetting - shifts exactly where along the arc each pass's sample points fall,
        // which is what keeps the (otherwise static) full-length stream looking alive rather than
        // like a frozen, teleported-in image.
        if (level.isClientSide()) {
            TrajectoryResult trajectory = traceTrajectory(level, player, eyePos, lookVec);
            double flightTime = Math.max(trajectory.flightTime(), 0.001);
            double spacing = flightTime / TIME_SAMPLES_PER_PASS;
            double phase = (player.tickCount / 20.0) % spacing;

            for (int i = 0; i < TIME_SAMPLES_PER_PASS; i++) {
                double t = i * spacing + phase;
                if (t > flightTime) continue;

                Vec3 point = positionAtTime(eyePos, lookVec, t);
                double spread = SPREAD_NEAR_NOZZLE + (t / flightTime) * (SPREAD_AT_TARGET - SPREAD_NEAR_NOZZLE);

                for (int d = 0; d < DROPLETS_PER_SAMPLE; d++) {
                    Vec3 droplet = jitterPerpendicular(point, lookVec, level, spread);
                    level.addParticle(ParticleTypes.FALLING_WATER, droplet.x, droplet.y, droplet.z, 0.0, 0.0, 0.0);
                }
            }

            // A little burst right where the stream actually lands, so this piece of feedback stays
            // in sync with where the fire is really being cooled (see the server branch below).
            if (trajectory.hitBlock() != null) {
                Vec3 impact = trajectory.endPosition();
                level.addParticle(ParticleTypes.SPLASH, impact.x, impact.y, impact.z, 0.0, 0.1, 0.0);
            }
            return;
        }

        // Server-side: authoritative arc for hit detection - one deterministic, entirely invisible
        // trace straight down the player's look direction.
        ServerLevel serverLevel = (ServerLevel) level;
        TrajectoryResult trajectory = traceTrajectory(level, player, eyePos, lookVec);

        if (trajectory.hitBlock() != null) {
            BlockPos center = trajectory.hitBlock();
            boolean playedHiss = false;
            boolean playedDouseBurst = false;

            // This is personal firefighting equipment, not a wildfire suppression tool - it only
            // ever cools the handful of PMWFireBlocks directly in the stream, and only once a
            // second (INTENSITY runs 1-10), so fully dousing even a raging block takes several
            // seconds of continuous, direct spray. It never touches the chunk-wide fire/moisture
            // simulation, so it can't smother a wildfire on its own the way it used to.
            boolean coolFireThisPass = player.tickCount % 20 == 0;

            // Iterate a 3x3x3 grid around the impact point
            for (int x = -1; x <= 1; x++) {
                for (int y = -1; y <= 1; y++) {
                    for (int z = -1; z <= 1; z++) {
                        BlockPos checkPos = center.offset(x, y, z);
                        BlockState state = level.getBlockState(checkPos);

                        // PMWeather specific fire logic
                        if (state.getBlock() instanceof PMWFireBlock) {
                            int currentIntensity = state.getValue(PMWFireBlock.INTENSITY);
                            double px = checkPos.getX() + 0.5, py = checkPos.getY() + 0.5, pz = checkPos.getZ() + 0.5;

                            // Continuous sizzle: every pass the stream is on the flames, water is
                            // flashing to steam. Scaled by how fierce the fire still is, so a raging
                            // block throws off a thick billowing plume while a dying one just wisps.
                            int steamCount = 2 + currentIntensity / 2;
                            serverLevel.sendParticles(ParticleTypes.CLOUD, px, py, pz, steamCount, 0.2, 0.15, 0.2, 0.03);
                            serverLevel.sendParticles(ParticleTypes.LARGE_SMOKE, px, py, pz, 1, 0.15, 0.2, 0.15, 0.01);
                            if (!playedHiss) {
                                level.playSound(null, checkPos, SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 0.5f, 1.4f + (level.random.nextFloat() * 0.2f));
                                playedHiss = true;
                            }

                            // Actual cooling only ticks once a second (see note above).
                            if (!coolFireThisPass) continue;

                            int newIntensity = currentIntensity - 3;
                            if (newIntensity <= 0) {
                                level.removeBlock(checkPos, false);

                                // The payoff: as the flames finally die, a rising column of steam
                                // erupts and a heavier, lower-pitched hiss cracks off.
                                serverLevel.sendParticles(ParticleTypes.CLOUD, px, py, pz, 25, 0.3, 0.25, 0.3, 0.08);
                                serverLevel.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, px, py + 0.2, pz, 7, 0.2, 0.1, 0.2, 0.02);
                                if (!playedDouseBurst) {
                                    level.playSound(null, checkPos, SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 1.0f, 0.6f + (level.random.nextFloat() * 0.2f));
                                    playedDouseBurst = true;
                                }
                            } else {
                                level.setBlockAndUpdate(checkPos, state.setValue(PMWFireBlock.INTENSITY, newIntensity));
                            }
                        }
                        // Vanilla fire fallback
                        else if (state.is(Blocks.FIRE)) {
                            level.removeBlock(checkPos, false);
                            double px = checkPos.getX() + 0.5, py = checkPos.getY() + 0.5, pz = checkPos.getZ() + 0.5;
                            serverLevel.sendParticles(ParticleTypes.CLOUD, px, py, pz, 12, 0.25, 0.2, 0.25, 0.05);
                            if (!playedDouseBurst) {
                                level.playSound(null, checkPos, SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 1.0f, 0.6f + (level.random.nextFloat() * 0.2f));
                                playedDouseBurst = true;
                            }
                        }
                        // Spraying normal blocks (Wetting them)
                        else if (!state.isAir()) {
                            // 10% chance per tick to spawn dripping water on a sprayed block
                            if (level.random.nextInt(10) == 0) {
                                serverLevel.sendParticles(ParticleTypes.DRIPPING_WATER, checkPos.getX() + 0.5, checkPos.getY() + 1.0, checkPos.getZ() + 0.5, 2, 0.3, 0.1, 0.3, 0.0);
                            }
                        }
                    }
                }
            }
        }
    }

    // Nudges `point` sideways by a small random amount, perpendicular to `direction`, so the
    // hand-placed droplets read as a bit of a spray rather than a perfectly straight line of dots.
    private static Vec3 jitterPerpendicular(Vec3 point, Vec3 direction, Level level, double magnitude) {
        Vec3 up = Math.abs(direction.y) > 0.99 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
        Vec3 right = direction.cross(up).normalize();
        Vec3 trueUp = right.cross(direction).normalize();

        double rx = (level.random.nextDouble() - 0.5) * 2.0 * magnitude;
        double ry = (level.random.nextDouble() - 0.5) * 2.0 * magnitude;

        return point.add(right.scale(rx)).add(trueUp.scale(ry));
    }

    // Where a simulated water arc ends up: either the block it hit, or its last position before
    // dissipating past MAX_RANGE (hitBlock null in that case), and how long (in simulated seconds)
    // it took to get there.
    private record TrajectoryResult(Vec3 endPosition, BlockPos hitBlock, double flightTime) {
    }

    // The position of the stream at flight time `t`, assuming a clear path - only valid for
    // t <= a trace's own flightTime, since that's the only span guaranteed collision-free.
    private static Vec3 positionAtTime(Vec3 origin, Vec3 direction, double t) {
        Vec3 dir = direction.normalize();
        return new Vec3(
                origin.x + dir.x * STREAM_SPEED * t,
                origin.y + dir.y * STREAM_SPEED * t - 0.5 * GRAVITY * t * t,
                origin.z + dir.z * STREAM_SPEED * t
        );
    }

    // Simulates the water as a gravity-affected projectile instead of an instant straight ray, so
    // it arcs downward over its flight and its effective reach depends on aim angle and height
    // (aiming from up high, or slightly upward, sends it further) rather than always being a fixed
    // distance. This is the sole source of truth for where the stream actually lands - the visual
    // particles spawned client-side are a separate, merely approximate depiction of this same arc.
    private static TrajectoryResult traceTrajectory(Level level, Player player, Vec3 origin, Vec3 direction) {
        Vec3 pos = origin;
        Vec3 velocity = direction.normalize().scale(STREAM_SPEED);

        for (int step = 0; step < MAX_STEPS; step++) {
            double elapsed = step * STEP_DT;
            Vec3 nextPos = pos.add(velocity.scale(STEP_DT));

            if (nextPos.distanceTo(origin) > MAX_RANGE) {
                return new TrajectoryResult(pos, null, elapsed);
            }

            HitResult hit = level.clip(new ClipContext(pos, nextPos, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
            if (hit.getType() == HitResult.Type.BLOCK) {
                BlockHitResult blockHit = (BlockHitResult) hit;
                return new TrajectoryResult(blockHit.getLocation(), blockHit.getBlockPos(), elapsed + STEP_DT);
            }

            pos = nextPos;
            velocity = velocity.add(0, -GRAVITY * STEP_DT, 0);
        }
        return new TrajectoryResult(pos, null, MAX_STEPS * STEP_DT);
    }
}
