package com.axes.wildfireadditions.event;

import com.axes.wildfireadditions.WildfireAdditions;
import com.axes.wildfireadditions.aircraft.AircraftTankData.Fluid;
import com.axes.wildfireadditions.coating.RetardantCoating;
import com.axes.wildfireadditions.item.RetardantSprayerItem;
import dev.protomanly.pmweather.block.PMWFireBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
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
 * Turns a tank deploy into a slow cloud of coloured particles that sinks toward the ground and, as it
 * arrives, applies the payload's effect column by column - so the effect visibly "lands" with the cloud
 * rather than snapping into place the instant the key is pressed.
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

    /** Horizontal radius of the drop footprint, in blocks. */
    private static final int RADIUS = 3;
    /** How fast the cloud sinks, in blocks per tick. */
    private static final double FALL_SPEED = 0.6;
    /** How far down we scan for fire/ground, and the most the cloud will fall before dissipating. */
    private static final int MAX_DROP = 48;
    /** One-shot intensity cut a water drop inflicts on any fire it settles on (INTENSITY runs 1-10). */
    private static final int WATER_INTENSITY_STEP = 8;

    private static final Vector3f BLUE = new Vector3f(0.25f, 0.55f, 1.0f);
    private static final Vector3f RED = new Vector3f(0.85f, 0.15f, 0.12f);

    // Live drops across all dimensions; each tick we process the ones matching the ticking level.
    private static final List<ActiveDrop> DROPS = new ArrayList<>();

    private AircraftAirDropHandler() {
    }

    /**
     * Begins a drop of {@code fluid} centred on ({@code x},{@code z}) starting at height {@code y} (the
     * aircraft's altitude). Called from the network handler once a deploy is validated server-side.
     */
    public static void startDrop(ServerLevel level, double x, double y, double z, Fluid fluid) {
        if (fluid == Fluid.NONE) return;

        int startY = (int) Math.floor(y);
        int cx = (int) Math.floor(x);
        int cz = (int) Math.floor(z);

        List<Column> columns = new ArrayList<>();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                if (dx * dx + dz * dz > RADIUS * RADIUS + 1) continue; // trim the square to a rough disc
                columns.add(scanColumn(level, cx + dx, cz + dz, startY, cursor));
            }
        }

        DROPS.add(new ActiveDrop(level.dimension(), fluid, columns, y, y - MAX_DROP));

        // An opening puff at the aircraft so the release reads immediately, before the cloud has fallen.
        ParticleOptions dust = dust(fluid);
        level.sendParticles(dust, x, y - 0.3, z, 40, RADIUS * 0.7, 0.3, RADIUS * 0.7, 0.0);
        level.playSound(null, cx, startY, cz, SoundEvents.PLAYER_SPLASH, SoundSource.PLAYERS,
                0.7f, 0.7f + level.random.nextFloat() * 0.2f);
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

    // Advances one drop by a tick: sinks the cloud, paints a fresh particle layer, and fires each column's
    // effect as the cloud reaches it. Returns true once the drop is spent (all columns done or fully sunk).
    private static boolean tickDrop(ServerLevel level, ActiveDrop drop) {
        drop.y -= FALL_SPEED;

        double cx = drop.centerX();
        double cz = drop.centerZ();
        level.sendParticles(dust(drop.fluid), cx, drop.y, cz, 14, RADIUS * 0.6, 0.15, RADIUS * 0.6, 0.0);

        boolean allApplied = true;
        for (Column column : drop.columns) {
            if (column.applied) continue;
            int target = drop.fluid == Fluid.WATER ? column.effectTargetWater() : column.groundY;
            if (target == Column.NO_HIT) {
                // Nothing to act on in this column; retire it once the cloud has sunk past where it would be.
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

    private static void applyColumn(ServerLevel level, Fluid fluid, Column column) {
        if (fluid == Fluid.WATER) {
            applyWater(level, column.x, column.fireY, column.z);
        } else {
            applyRetardant(level, column.x, column.groundY, column.z);
        }
    }

    // A hard, one-shot dousing over a small volume around the fire the cloud settled on.
    private static void applyWater(ServerLevel level, int x, int fireY, int z) {
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
        double px = x + 0.5, py = fireY + 0.5, pz = z + 0.5;
        level.sendParticles(ParticleTypes.CLOUD, px, py, pz, 12, 0.4, 0.3, 0.4, 0.02);
        level.sendParticles(ParticleTypes.LARGE_SMOKE, px, py, pz, 3, 0.3, 0.2, 0.3, 0.01);
        if (doused) {
            level.playSound(null, BlockPos.containing(px, py, pz), SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS,
                    0.6f, 1.0f + level.random.nextFloat() * 0.2f);
        }
    }

    // Lays a retardant note on the ground the cloud settled over plus its four horizontal neighbours,
    // exactly the note the Retardant Sprayer paints - the fire mixin does the rest.
    private static void applyRetardant(ServerLevel level, int x, int groundY, int z) {
        if (groundY == Column.NO_HIT) return;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int[][] offsets = {{0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] o : offsets) {
            pos.set(x + o[0], groundY, z + o[1]);
            BlockState state = level.getBlockState(pos);
            if (RetardantSprayerItem.isCoatable(level, pos, state) && !RetardantCoating.isCoated(level, pos)) {
                RetardantCoating.coat(level, pos.immutable());
            }
        }
        double px = x + 0.5, py = groundY + 1.0, pz = z + 0.5;
        level.sendParticles(ParticleTypes.CLOUD, px, py, pz, 5, 0.4, 0.1, 0.4, 0.01);
    }

    private static ParticleOptions dust(Fluid fluid) {
        return new DustParticleOptions(fluid == Fluid.WATER ? BLUE : RED, 1.6f);
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

        double centerX() {
            return columns.isEmpty() ? 0 : columns.get(columns.size() / 2).x + 0.5;
        }

        double centerZ() {
            return columns.isEmpty() ? 0 : columns.get(columns.size() / 2).z + 0.5;
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
