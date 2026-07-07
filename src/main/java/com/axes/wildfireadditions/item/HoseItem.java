package com.axes.wildfireadditions.item;

import com.axes.wildfireadditions.registry.ModBlocks;
import com.axes.wildfireadditions.util.WaterDouseQueue;
import com.axes.wildfireadditions.util.WaterStream;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.HumanoidArm;
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

    private static final int SERVER_TICK_INTERVAL = 2; // how often the server-side trace/douse pass runs

    // How far the visible nozzle sits from the eye: forward along the aim, out toward the hand holding
    // the hose, and down toward chest height. The droplets fly the same velocity as the server's
    // eye-origin trace, so the visual stream is simply the authoritative arc shifted by this fixed
    // offset - well inside the douse's 3x3 area and the fire-hit tolerance.
    private static final double NOZZLE_FORWARD = 0.4;
    private static final double NOZZLE_SIDE = 0.25;
    private static final double NOZZLE_DOWN = 0.22;

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

        Vec3 eyePos = player.getEyePosition();
        Vec3 lookVec = player.getLookAngle();

        // Client-side, every tick: launch this tick's slug of water from the nozzle. The droplets fly
        // their own ballistic arcs, collide and splash on their own, so no trace is needed here - and
        // emitting every tick (rather than every other) is what keeps the rope of water gapless.
        if (level.isClientSide()) {
            WaterStream.emitHoseStream(level, nozzleOrigin(player, eyePos, lookVec), lookVec, STREAM_SPEED);
            return;
        }

        // Server-side, every 2 ticks: authoritative arc for hit detection. The douse is scheduled with
        // the arc's flight time rather than applied immediately, so the fire only starts going out when
        // the water that just left the nozzle physically gets there.
        if (player.tickCount % SERVER_TICK_INTERVAL != 0) return;
        ServerLevel serverLevel = (ServerLevel) level;
        WaterStream.TrajectoryResult trajectory = WaterStream.traceTrajectory(level, player, eyePos, lookVec, STREAM_SPEED, MAX_RANGE);
        if (trajectory.hitBlock() != null) {
            WaterDouseQueue.schedule(serverLevel, trajectory.hitBlock(), trajectory.flightTime());
        }
    }

    // Where the visible stream leaves from: offset from the eye toward whichever hand holds the hose,
    // so the jet reads as coming out of the held nozzle instead of the player's face.
    private static Vec3 nozzleOrigin(Player player, Vec3 eyePos, Vec3 lookVec) {
        boolean rightSide = (player.getUsedItemHand() == InteractionHand.MAIN_HAND)
                == (player.getMainArm() == HumanoidArm.RIGHT);
        float yawRad = player.getYRot() * Mth.DEG_TO_RAD;
        Vec3 right = new Vec3(-Math.cos(yawRad), 0.0, -Math.sin(yawRad));
        return eyePos
                .add(lookVec.scale(NOZZLE_FORWARD))
                .add(right.scale(rightSide ? NOZZLE_SIDE : -NOZZLE_SIDE))
                .add(0.0, -NOZZLE_DOWN, 0.0);
    }
}
