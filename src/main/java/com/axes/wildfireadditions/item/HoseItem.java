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

import java.util.ArrayList;
import java.util.List;

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
    private static final double PARTICLE_SPREAD_DEGREES = 4.0; // how far an individual droplet stream can drift off-aim
    private static final int PARTICLE_STREAMS = 3; // number of jittered droplet trails drawn per pass

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
        if (player.tickCount % 2 != 0) return;

        Vec3 eyePos = player.getEyePosition();
        Vec3 lookVec = player.getLookAngle();

        // Client-side: draw the water stream as a handful of slightly jittered arcs, each one
        // hand-drawn with particles placed directly along its simulated path. Since we place the
        // particles ourselves (rather than giving vanilla's own particle physics a velocity and
        // letting it fly), the trail always reaches exactly as far as the arc actually goes -
        // stopping right at whatever it hits - instead of despawning early or clipping through walls.
        if (level.isClientSide()) {
            for (int i = 0; i < PARTICLE_STREAMS; i++) {
                Vec3 jittered = jitterDirection(lookVec, level, PARTICLE_SPREAD_DEGREES);
                Trajectory trail = traceTrajectory(level, player, eyePos, jittered);

                for (Vec3 point : trail.points) {
                    level.addParticle(ParticleTypes.FALLING_WATER, point.x, point.y, point.z, 0.0, 0.0, 0.0);
                }

                // A little burst right where this droplet trail actually lands
                if (trail.hitBlock != null) {
                    Vec3 impact = trail.points.get(trail.points.size() - 1);
                    level.addParticle(ParticleTypes.SPLASH, impact.x, impact.y, impact.z, 0.0, 0.1, 0.0);
                }
            }
            return;
        }

        // Server-side: authoritative arc for hit detection - one deterministic trace straight down
        // the player's look direction (the visual jitter above is purely cosmetic).
        ServerLevel serverLevel = (ServerLevel) level;
        Trajectory trajectory = traceTrajectory(level, player, eyePos, lookVec);

        if (trajectory.hitBlock != null) {
            BlockPos center = trajectory.hitBlock;
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

    // Rotates `direction` by a small random angle (up to `maxAngleDegrees` on each axis) so a
    // stream of droplets fired down the same look vector fans out slightly instead of forming a
    // perfectly tight beam.
    private static Vec3 jitterDirection(Vec3 direction, Level level, double maxAngleDegrees) {
        Vec3 up = Math.abs(direction.y) > 0.99 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
        Vec3 right = direction.cross(up).normalize();
        Vec3 trueUp = right.cross(direction).normalize();

        double yaw = Math.toRadians((level.random.nextDouble() - 0.5) * 2.0 * maxAngleDegrees);
        double pitch = Math.toRadians((level.random.nextDouble() - 0.5) * 2.0 * maxAngleDegrees);

        return direction.add(right.scale(Math.tan(yaw))).add(trueUp.scale(Math.tan(pitch))).normalize();
    }

    // The sampled points of a simulated water arc, from the nozzle up to (and including) wherever
    // it lands - either the block it hit, or its last position before dissipating past MAX_RANGE.
    private static final class Trajectory {
        final List<Vec3> points;
        final BlockPos hitBlock; // null if the stream never actually hit anything

        Trajectory(List<Vec3> points, BlockPos hitBlock) {
            this.points = points;
            this.hitBlock = hitBlock;
        }
    }

    // Simulates the water as a gravity-affected projectile instead of an instant straight ray, so
    // it arcs downward over its flight and its effective reach depends on aim angle and height
    // (aiming from up high, or slightly upward, sends it further) rather than always being a fixed
    // distance.
    private static Trajectory traceTrajectory(Level level, Player player, Vec3 origin, Vec3 direction) {
        List<Vec3> points = new ArrayList<>();
        Vec3 pos = origin;
        Vec3 velocity = direction.normalize().scale(STREAM_SPEED);

        for (int step = 0; step < MAX_STEPS; step++) {
            Vec3 nextPos = pos.add(velocity.scale(STEP_DT));

            if (nextPos.distanceTo(origin) > MAX_RANGE) {
                return new Trajectory(points, null);
            }

            HitResult hit = level.clip(new ClipContext(pos, nextPos, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
            if (hit.getType() == HitResult.Type.BLOCK) {
                BlockHitResult blockHit = (BlockHitResult) hit;
                points.add(blockHit.getLocation());
                return new Trajectory(points, blockHit.getBlockPos());
            }

            points.add(nextPos);
            pos = nextPos;
            velocity = velocity.add(0, -GRAVITY * STEP_DT, 0);
        }
        return new Trajectory(points, null);
    }
}
