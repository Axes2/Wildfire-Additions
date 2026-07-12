package com.axes.wildfireadditions.client.sound;

import com.axes.wildfireadditions.WildfireAdditions;
import com.axes.wildfireadditions.block.FireSprinklerBlock;
import com.axes.wildfireadditions.block.entity.FireSprinklerBlockEntity;
import com.axes.wildfireadditions.item.HoseItem;
import com.axes.wildfireadditions.registry.ModSounds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Starts and reaps the looping spray sounds each client tick. Every spraying player gets a hose loop
 * (so other players' hoses are audible in multiplayer too, matching how their hoses render), and every
 * nearby active turret gets its own loop. The {@link SpraySoundInstance}s handle their own fade in/out
 * and self-stop; this class only makes sure exactly one exists per active source and drops the dead ones.
 *
 * <p>Turrets have no global list, so we sweep the block entities in the handful of chunks around the
 * player a couple of times a second - cheap, and well inside the loop's audible range.
 */
@EventBusSubscriber(modid = WildfireAdditions.MODID, value = Dist.CLIENT)
public final class SpraySoundManager {

    private static final Map<UUID, SpraySoundInstance> HOSE_SOUNDS = new HashMap<>();
    private static final Map<BlockPos, SpraySoundInstance> SPRINKLER_SOUNDS = new HashMap<>();

    private static final int SPRINKLER_SCAN_PERIOD = 10;      // sweep for turrets ~twice a second
    private static final int SPRINKLER_SCAN_CHUNK_RADIUS = 2;  // covers the turret loop's audible range
    private static final double NOZZLE_HEIGHT = 24.0 / 16.0;   // matches FireSprinklerBlockEntity's nozzle

    private SpraySoundManager() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            HOSE_SOUNDS.clear();
            SPRINKLER_SOUNDS.clear();
            return;
        }
        if (mc.isPaused()) return;

        updateHoseSounds(mc, level);
        if (level.getGameTime() % SPRINKLER_SCAN_PERIOD == 0) {
            updateSprinklerSounds(mc, level);
        }

        HOSE_SOUNDS.values().removeIf(SpraySoundInstance::isStopped);
        SPRINKLER_SOUNDS.values().removeIf(SpraySoundInstance::isStopped);
    }

    private static void updateHoseSounds(Minecraft mc, ClientLevel level) {
        for (Player player : level.players()) {
            if (!isSprayingHose(player)) continue;
            HOSE_SOUNDS.computeIfAbsent(player.getUUID(), id -> {
                SpraySoundInstance sound = new SpraySoundInstance(
                        ModSounds.HOSE_SPRAY.get(), SoundSource.PLAYERS, 0.8f, 1.0f,
                        () -> player.isAlive() && isSprayingHose(player),
                        () -> hoseNozzle(player));
                mc.getSoundManager().play(sound);
                return sound;
            });
        }
    }

    private static void updateSprinklerSounds(Minecraft mc, ClientLevel level) {
        Player viewer = mc.player;
        if (viewer == null) return;

        int centerX = viewer.chunkPosition().x;
        int centerZ = viewer.chunkPosition().z;
        for (int dx = -SPRINKLER_SCAN_CHUNK_RADIUS; dx <= SPRINKLER_SCAN_CHUNK_RADIUS; dx++) {
            for (int dz = -SPRINKLER_SCAN_CHUNK_RADIUS; dz <= SPRINKLER_SCAN_CHUNK_RADIUS; dz++) {
                LevelChunk chunk = level.getChunk(centerX + dx, centerZ + dz);
                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    if (!(be instanceof FireSprinklerBlockEntity sprinkler) || !isSpraying(sprinkler)) continue;
                    SPRINKLER_SOUNDS.computeIfAbsent(sprinkler.getBlockPos().immutable(), pos -> {
                        SpraySoundInstance sound = new SpraySoundInstance(
                                ModSounds.SPRINKLER_SPRAY.get(), SoundSource.BLOCKS, 1.0f, 0.9f,
                                () -> !sprinkler.isRemoved() && isSpraying(sprinkler),
                                () -> nozzlePos(sprinkler));
                        mc.getSoundManager().play(sound);
                        return sound;
                    });
                }
            }
        }
    }

    private static boolean isSprayingHose(Player player) {
        return player.isUsingItem() && player.getUseItem().getItem() instanceof HoseItem;
    }

    private static boolean isSpraying(FireSprinklerBlockEntity sprinkler) {
        BlockState state = sprinkler.getBlockState();
        return state.hasProperty(FireSprinklerBlock.ACTIVE)
                && state.getValue(FireSprinklerBlock.ACTIVE)
                && sprinkler.getTarget() != null;
    }

    // Roughly where the stream leaves the hose: just ahead of the eyes, so the sound sits at the nozzle.
    private static Vec3 hoseNozzle(Player player) {
        return player.getEyePosition().add(player.getLookAngle().scale(0.4));
    }

    private static Vec3 nozzlePos(FireSprinklerBlockEntity sprinkler) {
        BlockPos pos = sprinkler.getBlockPos();
        return new Vec3(pos.getX() + 0.5, pos.getY() + NOZZLE_HEIGHT, pos.getZ() + 0.5);
    }
}
