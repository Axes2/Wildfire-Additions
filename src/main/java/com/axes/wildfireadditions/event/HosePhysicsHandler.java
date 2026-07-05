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
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.*;

@EventBusSubscriber(modid = WildfireAdditions.MODID)
public class HosePhysicsHandler {

    private static final double MAX_HOSE_LENGTH = 50.0;
    private static final int MAX_NODES = 48; // Safety cap so pathological geometry can't grow the chain forever
    private static final Map<UUID, HoseState> ACTIVE_HOSES = new HashMap<>();

    // The point the hose actually connects to: just above the pump box, outside its solid collision box.
    // Must match the anchor used by HoseRenderHandler so physics and rendering agree on where the hose starts.
    public static Vec3 getPumpAnchor(BlockPos pumpPos) {
        return Vec3.atBottomCenterOf(pumpPos).add(0, 1.0, 0);
    }

    public static class HoseState {
        public BlockPos pumpPos;
        public List<Vec3> nodes = new ArrayList<>();
        public Vec3 lastValidPlayerPos;

        public HoseState(BlockPos pumpPos, Vec3 startPos) {
            this.pumpPos = pumpPos;
            this.nodes.add(getPumpAnchor(pumpPos));
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
        int previousNodeCount = state.nodes.size(); // Track to see if we add/remove a corner

        double totalLength = 0;
        for (int i = 0; i < state.nodes.size() - 1; i++) {
            totalLength += state.nodes.get(i).distanceTo(state.nodes.get(i + 1));
        }
        totalLength += state.nodes.getLast().distanceTo(player.position());

        if (totalLength > MAX_HOSE_LENGTH) {
            player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 20, 4, false, false, false));
            player.displayClientMessage(Component.literal("The hose is pulling taut!").withStyle(net.minecraft.ChatFormatting.RED), true);
            if (totalLength > MAX_HOSE_LENGTH + 6.0) {
                snapHose(player, hoseStack);
                return;
            }
        }

        // UNTANGLE: keep collapsing trailing corners as long as the player can see past them.
        // This can remove several kinks in a single tick, so the hose fully unwraps once it's clear
        // instead of shedding a single corner per tick (which left ghost kinks behind on complex geometry).
        while (state.nodes.size() > 1) {
            Vec3 candidate = state.nodes.get(state.nodes.size() - 2);
            if (hasClearLineOfSight(level, player, playerEye, candidate)) {
                state.nodes.removeLast();
            } else {
                break;
            }
        }

        // MID-CHAIN VALIDATION: the checks above only ever look at the live segment between the
        // player and the last node. They can't catch a wall that gets built between two corners
        // that were already placed, or a corner that turned out unnecessary once a later corner
        // was added. Re-walk the whole established chain every tick to collapse and re-route it.
        collapseRedundantNodes(level, player, state);
        fixIntermediateCollisions(level, player, state);

        Vec3 lastNode = state.nodes.getLast();
        boolean hasLOS = hasClearLineOfSight(level, player, playerEye, lastNode);

        if (hasLOS) {
            state.lastValidPlayerPos = player.position();
        } else if (state.lastValidPlayerPos.distanceTo(lastNode) > 1.5 && state.nodes.size() < MAX_NODES) {
            // Drop the new corner onto the ground beneath where the hose was last taut,
            // so the hose lays on the terrain instead of floating at the player's eye/waist height.
            state.nodes.add(snapToGround(level, state.lastValidPlayerPos));
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

    // Removes any established corner whose neighbours can already see each other directly, e.g.
    // because a later corner rerouted the hose around the same obstacle, or the obstacle is gone.
    private static void collapseRedundantNodes(Level level, Player player, HoseState state) {
        List<Vec3> nodes = state.nodes;
        for (int i = 1; i < nodes.size() - 1; ) {
            Vec3 prev = nodes.get(i - 1);
            Vec3 next = nodes.get(i + 1);
            if (findBlockingHit(level, player, prev, next) == null) {
                nodes.remove(i);
            } else {
                i++;
            }
        }
    }

    // Re-checks every already-placed segment (not just the live one to the player) and, if
    // something is now in the way, inserts a corner that routes around it. Runs every tick so a
    // single bad segment self-heals over a couple of ticks even if one inserted corner isn't
    // enough to fully clear the obstruction.
    private static void fixIntermediateCollisions(Level level, Player player, HoseState state) {
        List<Vec3> nodes = state.nodes;
        int inserted = 0;
        for (int i = 0; i < nodes.size() - 1 && nodes.size() < MAX_NODES && inserted < 8; i++) {
            Vec3 a = nodes.get(i);
            Vec3 b = nodes.get(i + 1);
            BlockHitResult hit = findBlockingHit(level, player, a, b);
            if (hit == null) continue;

            Vec3 corner = snapToGround(level, findBypassCorner(a, b, hit.getBlockPos()));
            // Bail out if the bypass point collapsed onto an existing endpoint - that would just
            // spin in place instead of making progress towards a valid route.
            if (corner.distanceTo(a) < 0.2 || corner.distanceTo(b) < 0.2) continue;

            nodes.add(i + 1, corner);
            inserted++;
            i--; // Re-examine the new a -> corner segment before moving on
        }
    }

    // Picks the corner of the obstructing block's footprint that best routes around it, pushed
    // out a little so the new node doesn't sit flush against the wall it's wrapping.
    private static Vec3 findBypassCorner(Vec3 a, Vec3 b, BlockPos hitPos) {
        double centerX = hitPos.getX() + 0.5;
        double centerZ = hitPos.getZ() + 0.5;
        double[][] offsets = {{-0.8, -0.8}, {0.8, -0.8}, {0.8, 0.8}, {-0.8, 0.8}};

        Vec3 best = null;
        double bestScore = Double.MAX_VALUE;
        for (double[] offset : offsets) {
            Vec3 candidate = new Vec3(centerX + offset[0], a.y, centerZ + offset[1]);
            double score = horizontalDistance(a, candidate) + horizontalDistance(candidate, b);
            if (score < bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return best;
    }

    private static double horizontalDistance(Vec3 a, Vec3 b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    // Traces from `from` to `to`, pulling both ends back slightly so a point that sits exactly on
    // (or just inside) a block's surface - e.g. a corner resting on the ground - doesn't register
    // as blocking its own segment.
    private static boolean hasClearLineOfSight(Level level, Player player, Vec3 from, Vec3 to) {
        return findBlockingHit(level, player, from, to) == null;
    }

    private static BlockHitResult findBlockingHit(Level level, Player player, Vec3 from, Vec3 to) {
        Vec3 direction = to.subtract(from);
        double distance = direction.length();
        if (distance < 1.0E-4) return null;

        Vec3 unit = direction.normalize();
        double margin = Math.min(0.1, distance / 2.0);
        Vec3 adjustedFrom = from.add(unit.scale(margin));
        Vec3 adjustedTo = to.subtract(unit.scale(margin));

        HitResult result = level.clip(new ClipContext(adjustedFrom, adjustedTo, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        return result.getType() == HitResult.Type.BLOCK ? (BlockHitResult) result : null;
    }

    // Pulls a newly placed corner down onto the nearest solid ground beneath it, so the hose rests
    // on the terrain rather than floating at whatever height the player happened to be standing.
    private static Vec3 snapToGround(Level level, Vec3 pos) {
        BlockPos columnTop = BlockPos.containing(pos.x, pos.y, pos.z);
        for (int i = 0; i < 5; i++) {
            BlockPos check = columnTop.below(i);
            if (!level.getBlockState(check).getCollisionShape(level, check).isEmpty()) {
                return new Vec3(pos.x, check.getY() + 1.0, pos.z);
            }
        }
        return pos;
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