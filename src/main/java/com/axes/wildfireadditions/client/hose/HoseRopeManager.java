package com.axes.wildfireadditions.client.hose;

import com.axes.wildfireadditions.WildfireAdditions;
import com.axes.wildfireadditions.item.HoseItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Owns one {@link HoseRopeSimulation} per player currently holding a connected hose, and steps them
 * once per client tick. Held-item components sync to every client, so this covers other players'
 * hoses in multiplayer too, not just the local player's.
 */
@EventBusSubscriber(modid = WildfireAdditions.MODID, value = Dist.CLIENT)
public final class HoseRopeManager {

    private static final Map<UUID, HoseRopeSimulation> ROPES = new HashMap<>();
    // Live read-only view over ROPES' values; built once instead of wrapping on every render frame.
    private static final Collection<HoseRopeSimulation> ROPES_VIEW = Collections.unmodifiableCollection(ROPES.values());
    // Only ever references the level currently being ticked (or null); used to detect level swaps.
    private static ClientLevel lastTickedLevel;

    private HoseRopeManager() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level != lastTickedLevel) {
            // Dimension change or disconnect: every rope's world-space state is meaningless now.
            ROPES.clear();
            lastTickedLevel = level;
        }
        if (level == null) return;
        if (mc.isPaused()) return; // Freeze the rope along with the rest of the world

        Set<UUID> active = new HashSet<>();
        for (Player player : level.players()) {
            BlockPos pumpPos = connectedPumpPos(player, level);
            if (pumpPos == null) continue;

            Vec3 handAnchor = handAnchor(player);
            HoseRopeSimulation rope = ROPES.get(player.getUUID());
            // A different pump means a different hose - never inherit the old rope's layout.
            if (rope == null || !rope.pumpPos().equals(pumpPos)) {
                rope = new HoseRopeSimulation(pumpPos, handAnchor);
                ROPES.put(player.getUUID(), rope);
            }
            rope.step(level, handAnchor);
            active.add(player.getUUID());
        }

        if (ROPES.size() > active.size()) {
            ROPES.keySet().retainAll(active);
        }
    }

    /** Live rope simulations for the renderer. Do not mutate. */
    public static Collection<HoseRopeSimulation> activeRopes() {
        return ROPES_VIEW;
    }

    /**
     * The pump this player's held hose is plumbed to, or null if they aren't holding a hose that is
     * connected in this dimension. Mirrors the checks the server does in its physics tick.
     */
    @Nullable
    private static BlockPos connectedPumpPos(Player player, ClientLevel level) {
        ItemStack stack = heldHose(player);
        if (stack == null) return null;

        CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
        if (customData == null || !customData.contains("PumpPos")) return null;

        CompoundTag tag = customData.copyTag();
        if (!tag.getString("PumpDimension").equals(level.dimension().location().toString())) return null;
        return BlockPos.of(tag.getLong("PumpPos"));
    }

    @Nullable
    private static ItemStack heldHose(Player player) {
        if (player.getMainHandItem().getItem() instanceof HoseItem) return player.getMainHandItem();
        if (player.getOffhandItem().getItem() instanceof HoseItem) return player.getOffhandItem();
        return null;
    }

    /**
     * Where the rope's player end pins this tick: beside the hip on whichever side actually holds
     * the hose, nudged slightly forward so the rope reads as feeding into the held nozzle. Height
     * rides the eye position so crouching visibly lowers the grip.
     */
    private static Vec3 handAnchor(Player player) {
        boolean holdingMain = player.getMainHandItem().getItem() instanceof HoseItem;
        boolean rightSide = (player.getMainArm() == HumanoidArm.RIGHT) == holdingMain;
        double side = rightSide ? 0.35 : -0.35;

        float yawRad = player.yBodyRot * Mth.DEG_TO_RAD;
        double forwardX = -Math.sin(yawRad);
        double forwardZ = Math.cos(yawRad);
        double rightX = -forwardZ;
        double rightZ = forwardX;

        double gripY = Math.max(player.getY() + 0.2, player.getEyeY() - 0.72);
        return new Vec3(
                player.getX() + forwardX * 0.25 + rightX * side,
                gripY,
                player.getZ() + forwardZ * 0.25 + rightZ * side);
    }
}
