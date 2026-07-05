package com.axes.wildfireadditions.event;

import com.axes.wildfireadditions.WildfireAdditions;
import com.axes.wildfireadditions.registry.ModBlocks;
import com.axes.wildfireadditions.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.*;

@EventBusSubscriber(modid = WildfireAdditions.MODID)
public class HosePhysicsHandler {

    private static final double MAX_HOSE_LENGTH = 50.0;
    private static final Map<UUID, HoseState> ACTIVE_HOSES = new HashMap<>();

    public static class HoseState {
        public BlockPos pumpPos;
        public List<Vec3> nodes = new ArrayList<>();
        public Vec3 lastValidPlayerPos;

        public HoseState(BlockPos pumpPos, Vec3 startPos) {
            this.pumpPos = pumpPos;
            this.nodes.add(Vec3.atCenterOf(pumpPos));
            this.lastValidPlayerPos = startPos;
        }
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        Player player = event.getEntity();
        Level level = player.level();

        if (level.isClientSide()) return; // Only process physics on the server

        ItemStack hoseStack = getActiveHose(player);

        // If the player puts the hose away, clear their state
        if (hoseStack == null) {
            ACTIVE_HOSES.remove(player.getUUID());
            return;
        }

        CustomData customData = hoseStack.get(DataComponents.CUSTOM_DATA);
        if (customData == null || !customData.contains("PumpPos")) return;

        CompoundTag tag = customData.copyTag();
        BlockPos pumpPos = BlockPos.of(tag.getLong("PumpPos"));
        String dimension = tag.getString("PumpDimension");

        // Snap the hose if they teleport dimensions
        if (!dimension.equals(level.dimension().location().toString())) {
            snapHose(player, hoseStack);
            return;
        }

        HoseState state = ACTIVE_HOSES.computeIfAbsent(player.getUUID(), k -> new HoseState(pumpPos, player.position()));
        processHosePhysics(player, level, hoseStack, state);
    }

    private static void processHosePhysics(Player player, Level level, ItemStack hoseStack, HoseState state) {
        Vec3 playerEye = player.getEyePosition();
        Vec3 lastNode = state.nodes.getLast();
        int previousNodeCount = state.nodes.size(); // Track to see if we add/remove a corner

        double totalLength = 0;
        for (int i = 0; i < state.nodes.size() - 1; i++) {
            totalLength += state.nodes.get(i).distanceTo(state.nodes.get(i + 1));
        }
        totalLength += lastNode.distanceTo(player.position());

        if (totalLength > MAX_HOSE_LENGTH) {
            player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 20, 4, false, false, false));
            player.displayClientMessage(Component.literal("The hose is pulling taut!").withStyle(net.minecraft.ChatFormatting.RED), true);
            if (totalLength > MAX_HOSE_LENGTH + 6.0) {
                snapHose(player, hoseStack);
                return;
            }
        }

        HitResult losResult = level.clip(new ClipContext(playerEye, lastNode, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        boolean hasLOS = losResult.getType() == HitResult.Type.MISS;

        if (hasLOS) {
            state.lastValidPlayerPos = player.position();
            if (state.nodes.size() > 1) {
                Vec3 secondToLastNode = state.nodes.get(state.nodes.size() - 2);
                HitResult untangleResult = level.clip(new ClipContext(playerEye, secondToLastNode, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
                if (untangleResult.getType() == HitResult.Type.MISS) {
                    state.nodes.removeLast();
                }
            }
        } else {
            if (state.lastValidPlayerPos.distanceTo(lastNode) > 1.5) {
                state.nodes.add(state.lastValidPlayerPos);
            }
        }

        // IF THE NODES CHANGED, SAVE THEM TO THE ITEM DATA FOR THE RENDERER
        if (state.nodes.size() != previousNodeCount) {
            net.minecraft.world.item.component.CustomData customData = hoseStack.get(DataComponents.CUSTOM_DATA);
            if (customData != null) {
                CompoundTag tag = customData.copyTag();
                net.minecraft.nbt.ListTag nodesList = new net.minecraft.nbt.ListTag();
                for (Vec3 node : state.nodes) {
                    CompoundTag nodeTag = new CompoundTag();
                    nodeTag.putDouble("x", node.x);
                    nodeTag.putDouble("y", node.y);
                    nodeTag.putDouble("z", node.z);
                    nodesList.add(nodeTag);
                }
                tag.put("HoseNodes", nodesList);
                hoseStack.set(DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.of(tag));
            }
        }

        if (player.tickCount % 20 == 0) {
            if (!level.getBlockState(state.pumpPos).is(ModBlocks.PUMP_BOX.get())) {
                snapHose(player, hoseStack);
            }
        }
    }

    private static void snapHose(Player player, ItemStack hoseStack) {
        // Remove from the server physics tracker
        ACTIVE_HOSES.remove(player.getUUID());

        // Consume the item
        hoseStack.shrink(1);

        // Notify the player
        player.displayClientMessage(Component.literal("The hose snapped!").withStyle(net.minecraft.ChatFormatting.RED), true);
    }

    private static ItemStack getActiveHose(Player player) {
        if (player.getMainHandItem().is(ModItems.HOSE.get())) return player.getMainHandItem();
        if (player.getOffhandItem().is(ModItems.HOSE.get())) return player.getOffhandItem();
        return null;
    }
}