package com.axes.wildfireadditions.event;

import com.axes.wildfireadditions.WildfireAdditions;
import com.axes.wildfireadditions.aircraft.AircraftTankData.Fluid;
import com.axes.wildfireadditions.coating.RetardantCoating;
import com.axes.wildfireadditions.item.RetardantSprayerItem;
import dev.protomanly.pmweather.block.PMWFireBlock;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Turns a tank deploy into a payload <b>run</b> that trails the aircraft: for a couple of seconds after the
 * key is pressed it follows the plane's real position and lays a band of slow-sinking coloured particles
 * <i>beneath</i> it, so the line is drawn only where the aircraft actually passes - never in front of or
 * behind it - and each puff falls and lands on its own, giving a sequential "curtain unrolling along the
 * flight path" look instead of a single instant splat.
 *
 * <p>The band is oriented by the aircraft's <b>facing (yaw)</b>, not its velocity, so a plane being pushed
 * sideways by wind still lays the swath square to where it's pointing. The trail itself follows the true
 * path (wind drift included), interpolated between ticks so a fast plane leaves no gaps.
 *
 * <p>Both payloads reuse machinery that already exists for the hand tools:
 * <ul>
 *   <li><b>Water</b> knocks {@link PMWFireBlock#INTENSITY} down hard (a one-shot {@link #WATER_INTENSITY_STEP})
 *       and clears vanilla fire - a "massive intensity reduction".</li>
 *   <li><b>Retardant</b> lays a {@link RetardantCoating} note on the ground it settles over, so the
 *       {@code PMWFireBlockMixin} then keeps fire off it.</li>
 * </ul>
 *
 * <p>Runs are ticked in {@link LevelTickEvent.Post} and matched to their level by dimension key. Because
 * puffs land in the order they were laid, the heavy per-block work (fire cooling, coating + its sync
 * packets) is spread naturally over the run rather than firing all in one tick.
 */
@EventBusSubscriber(modid = WildfireAdditions.MODID)
public final class AircraftAirDropHandler {

    /** How long the run keeps trailing the aircraft and laying payload, in ticks (~2s). */
    private static final int RUN_TICKS = 40;
    /** Hard cap on trail length in blocks, so a fast plane can't lay an absurdly long line. */
    private static final double MAX_RUN_DISTANCE = 48.0;
    /** Half-width of the swath, perpendicular to facing; 3.0 gives a ~6-block-wide band. */
    private static final double WIDTH_HALF = 3.0;
    /** Sampling step across the width, fine enough that a diagonal swath has no gaps. */
    private static final double WIDTH_STEP = 0.5;
    /** How fast each puff sinks, in blocks per tick - deliberately slow so the fall is clearly visible. */
    private static final double FALL_SPEED = 0.35;
    /** How far down we scan for fire/ground, and the most a puff will fall before dissipating. */
    private static final int MAX_DROP = 60;
    /** One-shot intensity cut a water drop inflicts on any fire it settles on (INTENSITY runs 1-10). */
    private static final int WATER_INTENSITY_STEP = 8;
    /** How many particle clusters to scatter across the live curtain each tick. */
    private static final int FALL_CLUSTERS_PER_TICK = 40;

    private static final Vector3f BLUE = new Vector3f(0.20f, 0.52f, 1.0f);
    private static final Vector3f RED = new Vector3f(0.90f, 0.13f, 0.10f);
    private static final Vector3f WHITE = new Vector3f(0.95f, 0.97f, 1.0f);
    private static final DustParticleOptions WHITE_DUST = new DustParticleOptions(WHITE, 1.3f);

    // Live runs across all dimensions; each tick we process the ones matching the ticking level.
    private static final List<ActiveDrop> DROPS = new ArrayList<>();

    private AircraftAirDropHandler() {
    }

    /**
     * Begins a payload run of {@code fluid} that trails {@code aircraft} for {@link #RUN_TICKS} ticks (or
     * until it has laid {@link #MAX_RUN_DISTANCE} blocks of trail). Called from the network handler once a
     * deploy is validated server-side.
     */
    public static void startDrop(ServerLevel level, Entity aircraft, Fluid fluid) {
        if (fluid == Fluid.NONE) return;
        DROPS.add(new ActiveDrop(level.dimension(), fluid, aircraft));
        level.playSound(null, aircraft.blockPosition(), SoundEvents.PLAYER_SPLASH, SoundSource.PLAYERS,
                0.9f, 0.7f + level.random.nextFloat() * 0.2f);
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (DROPS.isEmpty() || event.getLevel().isClientSide()) return;
        ServerLevel level = (ServerLevel) event.getLevel();

        Iterator<ActiveDrop> it = DROPS.iterator();
        while (it.hasNext()) {
            ActiveDrop drop = it.next();
            if (!drop.dim.equals(level.dimension())) continue;
            if (tickDrop(level, drop)) it.remove();
        }
    }

    // Forget any in-flight runs for a level as it unloads, so a static-field run can't outlive its world.
    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel serverLevel) {
            DROPS.removeIf(drop -> drop.dim.equals(serverLevel.dimension()));
        }
    }

    // One tick of a run: lay a fresh band under the aircraft (while it's still emitting), sink and land any
    // puffs that have reached their target, and paint the falling curtain. Returns true once the run has
    // stopped emitting and every puff has landed.
    private static boolean tickDrop(ServerLevel level, ActiveDrop run) {
        Entity ac = run.aircraft;
        boolean canEmit = run.ticksRemaining > 0 && ac != null && ac.isAlive()
                && ac.level().dimension().equals(run.dim) && run.runDistance < MAX_RUN_DISTANCE;
        if (canEmit) {
            layBand(level, run);
            run.ticksRemaining--;
        } else {
            run.ticksRemaining = 0;
        }

        boolean anyActive = false;
        for (Puff puff : run.puffs) {
            if (puff.applied) continue;
            puff.y -= FALL_SPEED;
            int target = run.fluid == Fluid.WATER ? puff.effectTargetWater() : puff.groundY;
            if (target == Puff.NO_HIT) {
                if (puff.y <= puff.stopY + 1) puff.applied = true;
                else anyActive = true;
                continue;
            }
            if (puff.y <= target + 1.0) {
                applyPuff(level, run.fluid, puff);
                puff.applied = true;
            } else {
                anyActive = true;
            }
        }

        emitFallingCurtain(level, run.puffs, run.fluid);
        return run.ticksRemaining <= 0 && !anyActive;
    }

    // Lays one tick's worth of trail: from the aircraft's previous position to its current one (interpolated
    // so nothing is skipped at speed), stamping a facing-aligned band of puffs the width of the swath.
    private static void layBand(ServerLevel level, ActiveDrop run) {
        Entity ac = run.aircraft;
        double cx = ac.getX(), cy = ac.getY(), cz = ac.getZ();
        int startY = (int) Math.floor(cy);

        // Perpendicular to the way the aircraft is pointing (yaw), in the horizontal plane.
        double yaw = Math.toRadians(ac.getYRot());
        double forwardX = -Math.sin(yaw), forwardZ = Math.cos(yaw);
        double perpX = -forwardZ, perpZ = forwardX;

        double moveX = cx - run.lastX, moveZ = cz - run.lastZ;
        double dist = Math.sqrt(moveX * moveX + moveZ * moveZ);
        int steps = Math.max(1, (int) Math.ceil(dist / 0.5));
        for (int s = 1; s <= steps; s++) {
            double t = (double) s / steps;
            double bx = run.lastX + moveX * t;
            double bz = run.lastZ + moveZ * t;
            for (double w = -WIDTH_HALF; w <= WIDTH_HALF + 1.0e-9; w += WIDTH_STEP) {
                int px = (int) Math.floor(bx + perpX * w);
                int pz = (int) Math.floor(bz + perpZ * w);
                if (run.seen.add(packXZ(px, pz))) {
                    run.puffs.add(scanPuff(level, px, pz, startY, cy));
                }
            }
        }

        run.runDistance += dist;
        run.lastX = cx;
        run.lastZ = cz;
    }

    // Finds the highest fire block and the highest coatable ground block below the emit point, giving the
    // puff its own falling height. Either target may be absent (open air / too high), recorded as NO_HIT.
    private static Puff scanPuff(ServerLevel level, int x, int z, int startY, double emitY) {
        int fireY = Puff.NO_HIT;
        int groundY = Puff.NO_HIT;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dy = 0; dy <= MAX_DROP; dy++) {
            int y = startY - dy;
            cursor.set(x, y, z);
            BlockState state = level.getBlockState(cursor);
            boolean isFire = state.getBlock() instanceof PMWFireBlock || state.is(Blocks.FIRE);
            if (fireY == Puff.NO_HIT && isFire) {
                fireY = y;
            }
            // Ground is the first solid, coatable block that isn't itself fire, so retardant lands on the
            // surface under a blaze rather than on the flames.
            if (groundY == Puff.NO_HIT && !isFire && RetardantSprayerItem.isCoatable(level, cursor, state)) {
                groundY = y;
            }
            if (fireY != Puff.NO_HIT && groundY != Puff.NO_HIT) break;
        }
        return new Puff(x, z, fireY, groundY, emitY);
    }

    // Scatters particle clusters at random points across the still-falling puffs: a heavy dose of the
    // fluid's colour plus a thread of bright white so the curtain stands out against any terrain.
    private static void emitFallingCurtain(ServerLevel level, List<Puff> puffs, Fluid fluid) {
        int n = puffs.size();
        if (n == 0) return;
        RandomSource rng = level.random;
        ParticleOptions colored = dust(fluid);
        for (int c = 0; c < FALL_CLUSTERS_PER_TICK; c++) {
            Puff puff = puffs.get(rng.nextInt(n));
            if (puff.applied) continue;
            double px = puff.x + rng.nextDouble();
            double pz = puff.z + rng.nextDouble();
            double py = puff.y + (rng.nextDouble() - 0.5) * 1.5;
            level.sendParticles(colored, px, py, pz, 3, 0.25, 0.35, 0.25, 0.0);
            if (rng.nextInt(2) == 0) {
                level.sendParticles(WHITE_DUST, px, py, pz, 2, 0.2, 0.3, 0.2, 0.0);
            }
        }
    }

    private static void applyPuff(ServerLevel level, Fluid fluid, Puff puff) {
        // Only a fraction of the (many) puffs spawn a heavy impact burst, so a long trail still lands as one
        // big visible wall of effect without flooding the network with per-block particle packets.
        boolean showImpact = level.random.nextInt(2) == 0;
        if (fluid == Fluid.WATER) {
            applyWater(level, puff.x, puff.fireY, puff.z, showImpact);
        } else {
            applyRetardant(level, puff.x, puff.groundY, puff.z, showImpact);
        }
    }

    // A hard, one-shot dousing over a small volume around the fire the curtain settled on.
    private static void applyWater(ServerLevel level, int x, int fireY, int z, boolean showImpact) {
        if (fireY == Puff.NO_HIT) return;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        boolean doused = false;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = -1; dy <= 2; dy++) {
                    pos.set(x + dx, fireY + dy, z + dz);
                    BlockState state = level.getBlockState(pos);
                    if (state.getBlock() instanceof PMWFireBlock) {
                        int intensity = state.getValue(PMWFireBlock.INTENSITY);
                        int next = intensity - WATER_INTENSITY_STEP;
                        if (next <= 0) {
                            level.removeBlock(pos, false);
                        } else {
                            level.setBlockAndUpdate(pos, state.setValue(PMWFireBlock.INTENSITY, next));
                        }
                        doused = true;
                    } else if (state.is(Blocks.FIRE)) {
                        level.removeBlock(pos, false);
                        doused = true;
                    }
                }
            }
        }
        if (!showImpact) return;
        double px = x + 0.5, py = fireY + 0.5, pz = z + 0.5;
        level.sendParticles(ParticleTypes.CLOUD, px, py, pz, 18, 0.55, 0.45, 0.55, 0.03);
        level.sendParticles(ParticleTypes.LARGE_SMOKE, px, py, pz, 6, 0.4, 0.3, 0.4, 0.02);
        level.sendParticles(dust(Fluid.WATER), px, py + 0.3, pz, 6, 0.5, 0.4, 0.5, 0.0);
        if (doused && level.random.nextInt(6) == 0) {
            level.playSound(null, BlockPos.containing(px, py, pz), SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS,
                    0.7f, 1.0f + level.random.nextFloat() * 0.2f);
        }
    }

    // Lays a retardant note on the ground the curtain settled over, exactly the note the Retardant Sprayer
    // paints - the fire mixin does the rest - then throws up a low puff of settling dust.
    private static void applyRetardant(ServerLevel level, int x, int groundY, int z, boolean showImpact) {
        if (groundY == Puff.NO_HIT) return;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        pos.set(x, groundY, z);
        BlockState state = level.getBlockState(pos);
        if (RetardantSprayerItem.isCoatable(level, pos, state) && !RetardantCoating.isCoated(level, pos)) {
            RetardantCoating.coat(level, pos.immutable());
        }
        if (!showImpact) return;
        double px = x + 0.5, py = groundY + 1.0, pz = z + 0.5;
        level.sendParticles(dust(Fluid.RETARDANT), px, py, pz, 8, 0.5, 0.25, 0.5, 0.0);
        level.sendParticles(WHITE_DUST, px, py, pz, 3, 0.5, 0.2, 0.5, 0.0);
        level.sendParticles(ParticleTypes.CLOUD, px, py, pz, 5, 0.45, 0.15, 0.45, 0.01);
    }

    private static ParticleOptions dust(Fluid fluid) {
        return new DustParticleOptions(fluid == Fluid.WATER ? BLUE : RED, 2.0f);
    }

    private static long packXZ(int x, int z) {
        return (x & 0xFFFFFFFFL) | ((long) z << 32);
    }

    // --- run bookkeeping ---------------------------------------------------------------------------

    private static final class ActiveDrop {
        final ResourceKey<Level> dim;
        final Fluid fluid;
        final Entity aircraft;
        final List<Puff> puffs = new ArrayList<>();
        final LongSet seen = new LongOpenHashSet();
        int ticksRemaining = RUN_TICKS;
        double runDistance;
        double lastX;
        double lastZ;

        ActiveDrop(ResourceKey<Level> dim, Fluid fluid, Entity aircraft) {
            this.dim = dim;
            this.fluid = fluid;
            this.aircraft = aircraft;
            this.lastX = aircraft.getX();
            this.lastZ = aircraft.getZ();
        }
    }

    private static final class Puff {
        static final int NO_HIT = Integer.MIN_VALUE;

        final int x, z;
        final int fireY;
        final int groundY;
        double y;
        final double stopY;
        boolean applied;

        Puff(int x, int z, int fireY, int groundY, double emitY) {
            this.x = x;
            this.z = z;
            this.fireY = fireY;
            this.groundY = groundY;
            this.y = emitY;
            this.stopY = emitY - MAX_DROP;
        }

        /** Where a water drop acts: the fire if there is one, otherwise the ground it splashes onto. */
        int effectTargetWater() {
            return fireY != NO_HIT ? fireY : groundY;
        }
    }
}
