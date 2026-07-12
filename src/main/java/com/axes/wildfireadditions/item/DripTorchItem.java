package com.axes.wildfireadditions.item;

import com.axes.wildfireadditions.event.DripTorchFireHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

public class DripTorchItem extends Item implements ReducedUseSlowdownItem {

    private static final double IGNITE_DISTANCE = 2.5; // Blocks in front of the player the ground lights.
    private static final int IGNITE_INTERVAL = 6; // Ticks between ground ignitions while held.
    private static final int MAX_GROUND_DROP = 3; // Don't light ground more than this far below the player.

    public DripTorchItem(Properties properties) {
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

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        player.startUsingItem(hand);
        return InteractionResultHolder.consume(player.getItemInHand(hand));
    }

    @Override
    public void onUseTick(Level level, LivingEntity livingEntity, ItemStack stack, int remainingUseDuration) {
        if (!(livingEntity instanceof Player player)) return;
        if (player.tickCount % 2 != 0) return;

        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getLookAngle();
        // The lit nozzle sits a little in front of and below the player's eyes.
        Vec3 nozzle = eye.add(look.scale(0.6)).add(0, -0.25, 0);

        if (level.isClientSide()) {
            // A small torch flame at the nozzle plus fuel that drips off it and falls, burning.
            level.addParticle(ParticleTypes.FLAME, nozzle.x, nozzle.y, nozzle.z, 0.0, 0.005, 0.0);
            level.addParticle(ParticleTypes.SMOKE, nozzle.x, nozzle.y + 0.1, nozzle.z, 0.0, 0.02, 0.0);
            if (level.random.nextInt(2) == 0) {
                level.addParticle(ParticleTypes.FALLING_LAVA,
                        nozzle.x + (level.random.nextDouble() - 0.5) * 0.1,
                        nozzle.y - 0.05,
                        nozzle.z + (level.random.nextDouble() - 0.5) * 0.1,
                        0.0, -0.05, 0.0);
            }
            return;
        }

        if (player.tickCount % IGNITE_INTERVAL != 0) return;

        ServerLevel serverLevel = (ServerLevel) level;
        Vec3 forward = new Vec3(look.x, 0, look.z);
        if (forward.lengthSqr() < 1.0E-6) return; // Looking straight up/down - no ground direction.
        forward = forward.normalize();

        Vec3 front = player.position().add(forward.scale(IGNITE_DISTANCE));
        BlockPos column = new BlockPos((int) Math.floor(front.x), 0, (int) Math.floor(front.z));
        BlockPos top = serverLevel.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column);

        // Only light ground roughly at the player's own level - not across a chasm or up a wall.
        if (Math.abs(top.getY() - player.blockPosition().getY()) > MAX_GROUND_DROP) return;

        if (DripTorchFireHandler.plantEmber(serverLevel, top)) {
            level.playSound(null, top, SoundEvents.FLINTANDSTEEL_USE, SoundSource.PLAYERS,
                    0.5f, 1.2f + (level.random.nextFloat() * 0.2f));

            // Wear it like a normal tool: one point of fuel per ground ignition, and it breaks (with the
            // vanilla break effect) once spent. hurtAndBreak ignores creative players and only bites on
            // the server, so no instabuild guard is needed here.
            stack.hurtAndBreak(1, player, LivingEntity.getSlotForHand(player.getUsedItemHand()));
        }
    }
}
