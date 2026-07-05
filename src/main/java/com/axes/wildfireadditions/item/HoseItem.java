package com.axes.wildfireadditions.item;

import dev.protomanly.pmweather.block.PMWFireBlock;
import dev.protomanly.pmweather.data.DataAttachments;
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
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public class HoseItem extends Item {

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

    // Triggered when the player first clicks right-click
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        player.startUsingItem(hand);
        return InteractionResultHolder.consume(player.getItemInHand(hand));
    }

    // Add this inside HoseItem.java
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
        if (player.tickCount % 2 != 0) return;

        Vec3 eyePos = player.getEyePosition();
        Vec3 lookVec = player.getLookAngle();
        Vec3 targetPos = eyePos.add(lookVec.scale(10.0)); // 10 block range

        // Client-side: Render the water stream
        if (level.isClientSide()) {
            for (int i = 1; i <= 5; i++) {
                Vec3 particlePos = eyePos.add(lookVec.scale(i * 2.0)).add(
                        (level.random.nextDouble() - 0.5) * 0.4,
                        (level.random.nextDouble() - 0.5) * 0.4,
                        (level.random.nextDouble() - 0.5) * 0.4
                );
                level.addParticle(ParticleTypes.SPLASH, particlePos.x, particlePos.y, particlePos.z, lookVec.x * 0.5, lookVec.y * 0.5, lookVec.z * 0.5);
                level.addParticle(ParticleTypes.CLOUD, particlePos.x, particlePos.y, particlePos.z, lookVec.x * 0.2, lookVec.y * 0.2, lookVec.z * 0.2);
            }
            return;
        }

        // Server-side: Physics and Extinguishing
        ServerLevel serverLevel = (ServerLevel) level;
        BlockHitResult hitResult = level.clip(new ClipContext(eyePos, targetPos, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));

        if (hitResult.getType() == HitResult.Type.BLOCK) {
            BlockPos center = hitResult.getBlockPos();
            boolean playedSound = false;
            float totalCoolingApplied = 0.0f;

            // Iterate a 3x3x3 grid around the impact point
            for (int x = -1; x <= 1; x++) {
                for (int y = -1; y <= 1; y++) {
                    for (int z = -1; z <= 1; z++) {
                        BlockPos checkPos = center.offset(x, y, z);
                        BlockState state = level.getBlockState(checkPos);

                        // PMWeather specific fire logic
                        if (state.getBlock() instanceof PMWFireBlock) {
                            int currentIntensity = state.getValue(PMWFireBlock.INTENSITY);
                            int newIntensity = currentIntensity - 2;

                            if (newIntensity <= 0) {
                                level.removeBlock(checkPos, false);
                            } else {
                                level.setBlockAndUpdate(checkPos, state.setValue(PMWFireBlock.INTENSITY, newIntensity));
                            }

                            // Visual & Audio feedback for extinguishing
                            serverLevel.sendParticles(ParticleTypes.LARGE_SMOKE, checkPos.getX() + 0.5, checkPos.getY() + 0.5, checkPos.getZ() + 0.5, 3, 0.2, 0.2, 0.2, 0.0);
                            if (!playedSound) {
                                level.playSound(null, checkPos, SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 0.6f, 1.0f + (level.random.nextFloat() * 0.2f));
                                playedSound = true;
                            }

                            totalCoolingApplied += 2.0f; // High cooling value for hitting actual fire
                        }
                        // Vanilla fire fallback
                        else if (state.is(Blocks.FIRE)) {
                            level.removeBlock(checkPos, false);
                            totalCoolingApplied += 1.0f;
                        }
                        // Spraying normal blocks (Wetting them)
                        else if (!state.isAir()) {
                            // 10% chance per tick to spawn dripping water on a sprayed block
                            if (level.random.nextInt(10) == 0) {
                                serverLevel.sendParticles(ParticleTypes.DRIPPING_WATER, checkPos.getX() + 0.5, checkPos.getY() + 1.0, checkPos.getZ() + 0.5, 2, 0.3, 0.1, 0.3, 0.0);
                            }
                            totalCoolingApplied += 0.1f; // Minor ambient cooling for soaking terrain
                        }
                    }
                }
            }

            // The Thermodynamic Chunk Hook
            if (totalCoolingApplied > 0) {
                ChunkAccess chunk = level.getChunk(center);

                // Spike the chunk moisture (Max limit of 100)
                float currentMoisture = chunk.getData(DataAttachments.MOISTURE);
                float newMoisture = Math.min(100.0f, currentMoisture + (totalCoolingApplied * 2.5f));
                chunk.setData(DataAttachments.MOISTURE, newMoisture);

                // Tank the active fire intensity (Min limit of 0)
                float currentFireIntensity = chunk.getData(DataAttachments.FIRE_INTENSITY);
                float newFireIntensity = Math.max(0.0f, currentFireIntensity - totalCoolingApplied);
                chunk.setData(DataAttachments.FIRE_INTENSITY, newFireIntensity);

                // Tank the stable/ambient fire intensity
                float currentStableFire = chunk.getData(DataAttachments.STABLE_FIRE_INTENSITY);
                float newStableFire = Math.max(0.0f, currentStableFire - totalCoolingApplied);
                chunk.setData(DataAttachments.STABLE_FIRE_INTENSITY, newStableFire);
            }
        }
    }
}