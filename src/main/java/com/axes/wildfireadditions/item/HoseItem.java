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

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

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
    private static final double PASS_SECONDS = TICK_INTERVAL / 20.0; // real time between passes
    private static final double DROPLET_TRAIL_SPACING = 0.035; // seconds of flight time between trailing droplets
    private static final int DROPLET_TRAIL_LENGTH = 5; // droplets drawn per pass, including the lead one
    private static final double SPREAD_NEAR_NOZZLE = 0.03; // blocks of positional jitter right at the nozzle
    private static final double SPREAD_AT_TARGET = 0.15; // blocks of positional jitter by the time it lands

    // Client-side only: how far (in seconds of simulated flight time) the visible "lead" droplet
    // has advanced along the current arc for each player, so the stream is drawn as continuously
    // travelling instead of being redrawn from scratch, all at once, every pass.
    private static final Map<UUID, Double> STREAM_PROGRESS = new HashMap<>();

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
        // To avoid redrawing the whole arc at once every pass (which looked like the stream
        // teleported into existence), only a short trailing cluster of droplets is drawn each pass,
        // positioned by how far a "lead" droplet has travelled in simulated flight time so far. That
        // progress advances every pass and loops once it reaches the target, giving the appearance of
        // continuous flow using only deterministic, hand-placed positions.
        if (level.isClientSide()) {
            TrajectoryResult trajectory = traceTrajectory(level, player, eyePos, lookVec);
            double flightTime = Math.max(trajectory.flightTime(), 0.001);

            double progress = STREAM_PROGRESS.merge(player.getUUID(), PASS_SECONDS, Double::sum);
            if (progress > flightTime) {
                progress %= flightTime;
                STREAM_PROGRESS.put(player.getUUID(), progress);
            }

            for (int i = 0; i < DROPLET_TRAIL_LENGTH; i++) {
                double t = progress - i * DROPLET_TRAIL_SPACING;
                if (t < 0) break;

                Vec3 point = positionAtTime(eyePos, lookVec, t);
                double spread = SPREAD_NEAR_NOZZLE + (t / flightTime) * (SPREAD_AT_TARGET - SPREAD_NEAR_NOZZLE);
                point = jitterPerpendicular(point, lookVec, level, spread);

                level.addParticle(ParticleTypes.FALLING_WATER, point.x, point.y, point.z, 0.0, 0.0, 0.0);
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
            boolean playedSound = false;

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
                            if (!coolFireThisPass) continue;

                            int currentIntensity = state.getValue(PMWFireBlock.INTENSITY);
                            int newIntensity = currentIntensity - 3;

                            if (newIntensity <= 0) {
                                level.removeBlock(checkPos, false);
                            } else {
                                level.setBlockAndUpdate(checkPos, state.setValue(PMWFireBlock.INTENSITY, newIntensity));
                            }

                            // Visual & Audio feedback for extinguishing
                            serverLevel.sendParticles(ParticleTypes.LARGE_SMOKE, checkPos.getX() + 0.5, checkPos.getY() + 0.5, checkPos.getZ() + 0.5, 3, 0.2, 0.2, 0.2, 0.0);
                            if (!playedSound) {
                                level.playSound(null, checkPos, SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 0.6f, 1.0f + (level.random.nextFloat() * 0.2f));
                                playedSound = true;
                            }
                        }
                        // Vanilla fire fallback
                        else if (state.is(Blocks.FIRE)) {
                            level.removeBlock(checkPos, false);
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
