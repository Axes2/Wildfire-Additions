package com.axes.wildfireadditions.block.entity;

import com.axes.wildfireadditions.block.FireSprinklerBlock;
import com.axes.wildfireadditions.config.WildfireConfig;
import com.axes.wildfireadditions.registry.ModBlockEntities;
import com.axes.wildfireadditions.util.WaterDouseQueue;
import com.axes.wildfireadditions.util.WaterStream;
import dev.protomanly.pmweather.block.PMWFireBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Drives a placed fire sprinkler turret. When switched on (see {@link FireSprinklerBlock}) and sitting
 * on a powered pump box, it locates the nearest reachable wildfire block within range, aims a ballistic
 * water stream at it, and douses it with the exact same {@link WaterStream} logic a player's hose uses -
 * it behaves as if a player were standing here working a hose, just automated and higher-pressure.
 *
 * <p>Only the sprayer head rotates (yaw) to face the target; the water always leaves from the centre of
 * the head, so the arc is produced by {@link WaterStream#solveBallisticAim} independently of the cosmetic
 * rotation. Detection mirrors the drip torch's nearest-fire scan; a candidate is only accepted if a
 * simulated stream actually reaches it, so the turret never wastes itself hosing a wall between it and a
 * fire it can "see" but not hit.
 */
public class FireSprinklerBlockEntity extends BlockEntity {

    // A fixed high-pressure monitor: faster and longer-reaching than the handheld hose so it can service
    // its full detection radius. The nozzle throws with a pronounced arc; ballistic range on flat ground
    // is v^2/g = 21^2/16 ~= 27.5 blocks, so the detection radius is kept just inside that (SCAN_RANGE)
    // to make sure every fire it targets is actually reachable.
    private static final double STREAM_SPEED = 21.0;

    // The nozzle sits at the centre of the head, 24px (1.5 blocks) up from the bottom of the model.
    private static final double NOZZLE_HEIGHT = 24.0 / 16.0;

    // Default horizontal detection radius, in blocks (inside ballistic reach); live value is
    // WildfireConfig.sprinklerScanRange().
    private static final int SCAN_RANGE = 26;
    private static final int VERTICAL_RANGE = 12; // vertical detection reach, in blocks

    private static final int RETARGET_PERIOD = 20; // reacquire the closest fire twice a second, so the turret always tracks the nearest flame
    private static final int SPRAY_PERIOD = 2; // run the server trace/douse-scheduling pass every 2 ticks, matching the hose

    private static final float YAW_STEP = 12.0f; // max degrees the head swivels per tick toward its target

    // Server-authoritative aim target (also synced to clients so they can draw the matching stream and
    // rotate the head). Null when there's nothing to shoot at.
    @Nullable
    private BlockPos target;

    private long ticker;

    // Client-only smooth rotation state for the head renderer.
    public float renderYaw;
    public float prevRenderYaw;

    public FireSprinklerBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.FIRE_SPRINKLER_BE.get(), pos, state);
    }

    private Vec3 nozzleOrigin() {
        return new Vec3(worldPosition.getX() + 0.5, worldPosition.getY() + NOZZLE_HEIGHT, worldPosition.getZ() + 0.5);
    }

    // The head model reaches ~2 blocks tall, so expand the render box upward or the swivelling head can
    // be culled when the base block itself is just off the edge of the screen.
    public AABB getRenderBoundingBox() {
        return new AABB(worldPosition).expandTowards(0.0, 1.0, 0.0);
    }

    @Nullable
    public BlockPos getTarget() {
        return target;
    }

    // Updates the target and, server-side, pushes the change to watching clients (only when it actually
    // changes, so an idle or steadily-firing turret costs no packets).
    public void setTarget(@Nullable BlockPos newTarget) {
        if (Objects.equals(this.target, newTarget)) return;
        this.target = newTarget == null ? null : newTarget.immutable();
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, FireSprinklerBlockEntity be) {
        if (!state.getValue(FireSprinklerBlock.ACTIVE)) return;
        be.ticker++;
        ServerLevel serverLevel = (ServerLevel) level;

        // Periodically re-validate power and reacquire the nearest reachable fire.
        if (be.ticker % RETARGET_PERIOD == 0) {
            if (!FireSprinklerBlock.isPowered(level, pos)) {
                // Lost its pump/water: shut down quietly (no message spam) and stop tracking.
                level.setBlock(pos, state.setValue(FireSprinklerBlock.ACTIVE, false), Block.UPDATE_ALL);
                be.setTarget(null);
                return;
            }
            be.setTarget(be.findTarget(serverLevel));
        }

        if (be.target == null) return;
        if (be.ticker % SPRAY_PERIOD != 0) return;

        // The fire we were aiming at may already be out; if so, drop it and wait for the next retarget
        // rather than hosing empty air.
        BlockState targetState = serverLevel.getBlockState(be.target);
        if (!(targetState.getBlock() instanceof PMWFireBlock) && !targetState.is(Blocks.FIRE)) {
            be.setTarget(null);
            return;
        }

        Vec3 origin = be.nozzleOrigin();
        Vec3 targetCenter = Vec3.atCenterOf(be.target);
        Vec3 aim = WaterStream.solveBallisticAim(origin, targetCenter, STREAM_SPEED);
        if (aim == null) {
            be.setTarget(null); // drifted out of ballistic range; wait for the next retarget
            return;
        }

        // Only douse if the arc can still actually reach the fire (nothing moved in to block it). The
        // douse happens at the fire block itself, not where the stream would eventually land - fire has
        // no collision, so the stream passes through it and lands well beyond. It's scheduled with the
        // arc's flight time so the fire starts going out when the water physically arrives, in step
        // with the visible droplets.
        WaterStream.TargetTrace trace = WaterStream.traceToTarget(level, origin, aim, STREAM_SPEED, targetCenter, WaterStream.TARGET_TOLERANCE);
        if (trace.reached()) {
            WaterDouseQueue.schedule(serverLevel, be.target, trace.flightTime());
        }
    }

    public static void clientTick(Level level, BlockPos pos, BlockState state, FireSprinklerBlockEntity be) {
        be.prevRenderYaw = be.renderYaw;

        // Swivel the head smoothly toward the current target (purely cosmetic - the water exits the
        // centre of the head regardless of which way it points).
        if (be.target != null) {
            double dx = (be.target.getX() + 0.5) - (pos.getX() + 0.5);
            double dz = (be.target.getZ() + 0.5) - (pos.getZ() + 0.5);
            float desired = (float) (-Mth.atan2(dx, dz) * Mth.RAD_TO_DEG);
            float diff = Mth.degreesDifference(be.renderYaw, desired);
            be.renderYaw += Mth.clamp(diff, -YAW_STEP, YAW_STEP);
        }

        if (!state.getValue(FireSprinklerBlock.ACTIVE) || be.target == null) return;

        Vec3 origin = be.nozzleOrigin();
        Vec3 aim = WaterStream.solveBallisticAim(origin, Vec3.atCenterOf(be.target), STREAM_SPEED);
        if (aim == null) return;

        // Launch this tick's slug of water from the nozzle (every tick, so the jet is gapless). The
        // droplets fly the same ballistic arc the aim solver computed, pass through the non-colliding
        // flames they're aimed at and splash on the terrain at/behind the fire on their own - no trace
        // or precomputed impact point needed here.
        WaterStream.emitTurretStream(level, origin, aim, STREAM_SPEED);
    }

    // Scans a radius around the turret for fire blocks (PMWeather fire, plus vanilla fire as a fallback),
    // closest first, and returns the first one a simulated stream can actually land on. This is a wide
    // scan run only once every RETARGET_PERIOD ticks; between retargets the turret keeps hosing the
    // target it already found.
    @Nullable
    private BlockPos findTarget(ServerLevel level) {
        Vec3 origin = nozzleOrigin();
        List<BlockPos> candidates = new ArrayList<>();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int scanRange = WildfireConfig.sprinklerScanRange();
        long rangeSq = (long) scanRange * scanRange;

        for (int dx = -scanRange; dx <= scanRange; dx++) {
            for (int dz = -scanRange; dz <= scanRange; dz++) {
                if ((long) dx * dx + (long) dz * dz > rangeSq) continue; // keep the horizontal reach circular
                for (int dy = -VERTICAL_RANGE; dy <= VERTICAL_RANGE; dy++) {
                    m.set(worldPosition.getX() + dx, worldPosition.getY() + dy, worldPosition.getZ() + dz);
                    BlockState state = level.getBlockState(m);
                    if (state.getBlock() instanceof PMWFireBlock || state.is(Blocks.FIRE)) {
                        candidates.add(m.immutable());
                    }
                }
            }
        }

        if (candidates.isEmpty()) return null;
        candidates.sort(Comparator.comparingDouble((BlockPos b) -> b.distToCenterSqr(origin)));

        for (BlockPos candidate : candidates) {
            Vec3 targetCenter = Vec3.atCenterOf(candidate);
            Vec3 aim = WaterStream.solveBallisticAim(origin, targetCenter, STREAM_SPEED);
            if (aim == null) continue; // out of ballistic range

            // Reachable if the arc passes through/near the fire before any solid block blocks it.
            if (WaterStream.traceToTarget(level, origin, aim, STREAM_SPEED, targetCenter, WaterStream.TARGET_TOLERANCE).reached()) {
                return candidate;
            }
        }
        return null; // everything in range is blocked behind cover
    }

    // --- Client sync: only the target position needs syncing; ACTIVE lives in the blockstate. ---

    private void writeTarget(CompoundTag tag) {
        if (target != null) {
            tag.putLong("Target", target.asLong());
        }
    }

    private void readTarget(CompoundTag tag) {
        this.target = tag.contains("Target") ? BlockPos.of(tag.getLong("Target")) : null;
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        writeTarget(tag);
        return tag;
    }

    @Override
    public void handleUpdateTag(CompoundTag tag, HolderLookup.Provider registries) {
        readTarget(tag);
    }

    @Nullable
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void onDataPacket(Connection net, ClientboundBlockEntityDataPacket pkt, HolderLookup.Provider registries) {
        CompoundTag tag = pkt.getTag();
        if (tag != null) {
            readTarget(tag);
        }
    }
}
