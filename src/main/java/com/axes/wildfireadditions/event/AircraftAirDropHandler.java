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
 * Turns a tank deploy into a long, slow-sinking curtain of coloured particles that is laid down as a
 * <b>stripe along the aircraft's flight path</b> (not a circular splat) and applies its effect column by
 * column as it settles - so the payload reads as a proper aerial drop trailing behind the plane.
 *
 * <p>Both payloads reuse machinery that already exists for the hand tools, which is the whole reason the
 * plane feature is small:
 * <ul>
 *   <li><b>Water</b> knocks {@link PMWFireBlock#INTENSITY} down hard (a one-shot {@link #WATER_INTENSITY_STEP},
 *       far more than the hose's per-tick nibble) and clears vanilla fire - a "massive intensity reduction".</li>
 *   <li><b>Retardant</b> lays a {@link RetardantCoating} note on the ground it settles over, exactly like
 *       the Retardant Sprayer, so the {@code PMWFireBlockMixin} then keeps fire off it.</li>
 * </ul>
 *
 * <p>Active drops are ticked in {@link LevelTickEvent.Post}, mirroring how {@link RetardantFireHandler}
 * runs its coating upkeep, and are matched to their level by dimension key so multiple worlds don't cross.
 */
@EventBusSubscriber(modid = WildfireAdditions.MODID)
public final class AircraftAirDropHandler {

    /** Length of the drop stripe along the flight path, in blocks. */
    private static final int STRIPE_LENGTH = 32;
    /** Half-width of the stripe (perpendicular to the path); 1 gives a 3-block-wide swath. */
    private static final int STRIPE_HALF_WIDTH = 1;
    /** How fast the curtain sinks, in blocks per tick - deliberately slow so the fall is clearly visible. */
    private static final double FALL_SPEED = 0.35;
    /** How far down we scan for fire/ground, and the most the curtain will fall before dissipating. */
    private static final int MAX_DROP = 60;
    /** One-shot intensity cut a water drop inflicts on any fire it settles on (INTENSITY runs 1-10). */
    private static final int WATER_INTENSITY_STEP = 8;

    /** How many particle clusters to scatter along the whole stripe each tick as it falls. */
    private static final int FALL_CLUSTERS_PER_TICK = 34;

    private static final Vector3f BLUE = new Vector3f(0.20f, 0.52f, 1.0f);
    private static final Vector3f RED = new Vector3f(0.90f, 0.13f, 0.10f);
    private static final Vector3f WHITE = new Vector3f(0.95f, 0.97f, 1.0f);

    // Live drops across all dimensions; each tick we process the ones matching the ticking level.
    private static final List<ActiveDrop> DROPS = new ArrayList<>();

    private AircraftAirDropHandler() {
    }

    /**
     * Begins a drop of {@code fluid} laid out as a stripe centred on ({@code x},{@code z}) at height
     * {@code y} (the aircraft's altitude), running along the horizontal heading ({@code dirX},{@code dirZ}).
     * Called from the network handler once a deploy is validated server-side.
     */
    public static void startDrop(ServerLevel level, double x, double y, double z,
                                 double dirX, double dirZ, Fluid fluid) {
        if (fluid == Fluid.NONE) return;

        // Normalise the heading; fall back to +Z if the aircraft is somehow stationary and unrotated.
        double len = Math.sqrt(dirX * dirX + dirZ * dirZ);
        if (len < 1.0e-4) {
            dirX = 0;
            dirZ = 1;
        } else {
            dirX /= len;
            dirZ /= len;
        }
        double perpX = -dirZ;
        double perpZ = dirX;

        int startY = (int) Math.floor(y);
        List<Column> columns = new ArrayList<>();
        LongSet seen = new LongOpenHashSet();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int i = -STRIPE_LENGTH / 2; i <= STRIPE_LENGTH / 2; i++) {
            for (int w = -STRIPE_HALF_WIDTH; w <= STRIPE_HALF_WIDTH; w++) {
                int bx = (int) Math.floor(x + dirX * i + perpX * w);
                int bz = (int) Math.floor(z + dirZ * i + perpZ * w);
                if (seen.add(packXZ(bx, bz))) {
                    columns.add(scanColumn(level, bx, bz, startY, cursor));
                }
            }
        }

        DROPS.add(new ActiveDrop(level.dimension(), fluid, columns, y, y - MAX_DROP));

        // A dense opening burst all along the stripe so the release reads immediately, up at plane height.
        emitFallingCurtain(level, columns, y, fluid, Math.min(columns.size(), 50), 4);
        level.playSound(null, BlockPos.containing(x, startY, z), SoundEvents.PLAYER_SPLASH, SoundSource.PLAYERS,
                0.9f, 0.7f + level.random.nextFloat() * 0.2f);
    }

    // Finds the highest fire block and the highest coatable ground block in this column, scanning down
    // from the aircraft. Either may be absent (dropped over open air / too high up), recorded as NO_HIT.
    private static Column scanColumn(ServerLevel level, int x, int z, int startY, BlockPos.MutableBlockPos cursor) {
        int fireY = Column.NO_HIT;
        int groundY = Column.NO_HIT;
        for (int dy = 0; dy <= MAX_DROP; dy++) {
            int y = startY - dy;
            cursor.set(x, y, z);
            BlockState state = level.getBlockState(cursor);
            boolean isFire = state.getBlock() instanceof PMWFireBlock || state.is(Blocks.FIRE);
            if (fireY == Column.NO_HIT && isFire) {
                fireY = y;
            }
            // Ground is the first solid, coatable block that isn't itself fire, so retardant lands on the
            // surface under a blaze rather than on the flames.
            if (groundY == Column.NO_HIT && !isFire && RetardantSprayerItem.isCoatable(level, cursor, state)) {
                groundY = y;
            }
            if (fireY != Column.NO_HIT && groundY != Column.NO_HIT) break;
        }
        return new Column(x, z, fireY, groundY);
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

    // Forget any in-flight drops for a level as it unloads, so a static-field drop can't outlive its world
    // (e.g. leaving and re-entering a singleplayer world within the same game session).
    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel serverLevel) {
            DROPS.removeIf(drop -> drop.dim.equals(serverLevel.dimension()));
        }
    }

    // Advances one drop by a tick: sinks the curtain, paints a fresh dense particle layer all along the
    // stripe, and fires each column's effect as the curtain reaches it. Returns true once the drop is spent.
    private static boolean tickDrop(ServerLevel level, ActiveDrop drop) {
        drop.y -= FALL_SPEED;
        emitFallingCurtain(level, drop.columns, drop.y, drop.fluid, FALL_CLUSTERS_PER_TICK, 3);

        boolean allApplied = true;
        for (Column column : drop.columns) {
            if (column.applied) continue;
            int target = drop.fluid == Fluid.WATER ? column.effectTargetWater() : column.groundY;
            if (target == Column.NO_HIT) {
                // Nothing to act on in this column; retire it once the curtain has sunk past where it would be.
                if (drop.y <= drop.stopY + 1) column.applied = true;
                else allApplied = false;
                continue;
            }
            if (drop.y <= target + 1.0) {
                applyColumn(level, drop.fluid, column);
                column.applied = true;
            } else {
                allApplied = false;
            }
        }

        return allApplied || drop.y <= drop.stopY;
    }

    // Scatters `clusters` particle clusters at random points along the stripe at height `y`: a heavy dose of
    // the fluid's colour plus a thread of bright white so the falling curtain stands out against any terrain.
    private static void emitFallingCurtain(ServerLevel level, List<Column> columns, double y, Fluid fluid,
                                           int clusters, int coloredPerCluster) {
        int n = columns.size();
        if (n == 0) return;
        RandomSource rng = level.random;
        ParticleOptions colored = dust(fluid);
        for (int c = 0; c < clusters; c++) {
            Column col = columns.get(rng.nextInt(n));
            double px = col.x + rng.nextDouble();
            double pz = col.z + rng.nextDouble();
            double py = y + (rng.nextDouble() - 0.5) * 2.0;
            level.sendParticles(colored, px, py, pz, coloredPerCluster, 0.25, 0.35, 0.25, 0.0);
            if (rng.nextInt(2) == 0) {
                level.sendParticles(WHITE_DUST, px, py, pz, 2, 0.2, 0.3, 0.2, 0.0);
            }
        }
    }

    private static void applyColumn(ServerLevel level, Fluid fluid, Column column) {
        // Only a fraction of the (many) columns spawn a heavy impact burst, so a long stripe still lands as
        // one big visible wall of effect without flooding the network with per-block particle packets.
        boolean showImpact = level.random.nextInt(2) == 0;
        if (fluid == Fluid.WATER) {
            applyWater(level, column.x, column.fireY, column.z, showImpact);
        } else {
            applyRetardant(level, column.x, column.groundY, column.z, showImpact);
        }
    }

    // A hard, one-shot dousing over a small volume around the fire the curtain settled on.
    private static void applyWater(ServerLevel level, int x, int fireY, int z, boolean showImpact) {
        if (fireY == Column.NO_HIT) return;
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
        if (groundY == Column.NO_HIT) return;
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

    private static final DustParticleOptions WHITE_DUST = new DustParticleOptions(WHITE, 1.3f);

    private static ParticleOptions dust(Fluid fluid) {
        return new DustParticleOptions(fluid == Fluid.WATER ? BLUE : RED, 2.0f);
    }

    private static long packXZ(int x, int z) {
        return (x & 0xFFFFFFFFL) | ((long) z << 32);
    }

    // --- drop bookkeeping --------------------------------------------------------------------------

    private static final class ActiveDrop {
        final ResourceKey<Level> dim;
        final Fluid fluid;
        final List<Column> columns;
        double y;
        final double stopY;

        ActiveDrop(ResourceKey<Level> dim, Fluid fluid, List<Column> columns, double startY, double stopY) {
            this.dim = dim;
            this.fluid = fluid;
            this.columns = columns;
            this.y = startY;
            this.stopY = stopY;
        }
    }

    private static final class Column {
        static final int NO_HIT = Integer.MIN_VALUE;

        final int x, z;
        final int fireY;
        final int groundY;
        boolean applied;

        Column(int x, int z, int fireY, int groundY) {
            this.x = x;
            this.z = z;
            this.fireY = fireY;
            this.groundY = groundY;
        }

        /** Where a water drop acts: the fire if there is one, otherwise the ground it splashes onto. */
        int effectTargetWater() {
            return fireY != NO_HIT ? fireY : groundY;
        }
    }
}
