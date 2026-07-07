package com.axes.wildfireadditions.item;

import com.axes.wildfireadditions.registry.ModBlocks;
import com.axes.wildfireadditions.util.WaterStream;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

public class HoseItem extends Item implements ReducedUseSlowdownItem {

    // A handheld line: modest nozzle speed and reach. A level shot from head height reaches roughly
    // 10-12 blocks, a shot angled slightly up reaches close to the ~20 block cap, and firing from up
    // high extends it further - all emergent from simulating the arc (see WaterStream) rather than a
    // straight ray. The placed sprinkler turret uses the same simulation but a higher-pressure nozzle.
    private static final double STREAM_SPEED = 22.0; // blocks/second, nozzle exit speed
    private static final double MAX_RANGE = 20.0; // straight-line distance from the nozzle before the stream dissipates

    private static final int TICK_INTERVAL = 2; // how often onUseTick's logic actually runs

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

    // Shift-right-clicking the Pump Box with the hose "stows" it back into the box - the reverse of
    // grabbing it out. This runs before use()/onUseTick, so the hose is put away instead of spraying.
    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        Level level = context.getLevel();
        if (player != null && player.isSecondaryUseActive()
                && level.getBlockState(context.getClickedPos()).is(ModBlocks.PUMP_BOX.get())) {
            if (!level.isClientSide()) {
                context.getItemInHand().shrink(1);
                player.displayClientMessage(Component.literal("Hose stowed back in the pump box."), true);
            }
            return InteractionResult.sidedSuccess(level.isClientSide());
        }
        return super.useOn(context);
    }

    // Triggered when the player first clicks right-click
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        player.startUsingItem(hand);
        return InteractionResultHolder.consume(player.getItemInHand(hand));
    }

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

        // Client-side: draw the stream along the real simulated arc, stopping where it lands.
        if (level.isClientSide()) {
            WaterStream.TrajectoryResult trajectory = WaterStream.traceTrajectory(level, player, eyePos, lookVec, STREAM_SPEED, MAX_RANGE);
            Vec3 impact = trajectory.hitBlock() != null ? trajectory.endPosition() : null;
            WaterStream.spawnStreamParticles(level, eyePos, lookVec, STREAM_SPEED, trajectory.flightTime(), impact, player.tickCount);
            return;
        }

        // Server-side: authoritative arc for hit detection, then douse whatever it lands on.
        ServerLevel serverLevel = (ServerLevel) level;
        WaterStream.TrajectoryResult trajectory = WaterStream.traceTrajectory(level, player, eyePos, lookVec, STREAM_SPEED, MAX_RANGE);
        if (trajectory.hitBlock() != null) {
            WaterStream.extinguishAt(serverLevel, trajectory.hitBlock(), player.tickCount);
        }
    }
}
