package com.axes.wildfireadditions.block;

import com.axes.wildfireadditions.block.entity.PumpBoxBlockEntity;
import com.axes.wildfireadditions.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

public class PumpBoxBlock extends Block implements EntityBlock {

    public PumpBoxBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        if (!level.isClientSide()) {
            // Require crouch/shift to grab the hose to prevent accidental grabs
            if (player.isShiftKeyDown()) {
                if (isAdjacentToWater(level, pos)) {
                    ItemStack hoseStack = new ItemStack(ModItems.HOSE.get());

                    // Inside useWithoutItem method in PumpBoxBlock.java:

                    CompoundTag tag = new CompoundTag();
                    tag.putLong("PumpPos", pos.asLong());
                    tag.putString("PumpDimension", level.dimension().location().toString());

// Apply it to the item
                    hoseStack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));

                    // Hand hose to player or drop it
                    if (player.getMainHandItem().isEmpty()) {
                        player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, hoseStack);
                    } else if (!player.getInventory().add(hoseStack)) {
                        player.drop(hoseStack, false);
                    }

                    player.displayClientMessage(Component.literal("Hose connected and pressurized."), true);
                    return InteractionResult.SUCCESS;
                } else {
                    player.displayClientMessage(Component.literal("Pump Box requires an adjacent water source!"), true);
                    return InteractionResult.FAIL;
                }
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    public static boolean isAdjacentToWater(Level level, BlockPos pos) {
        for (Direction direction : Direction.values()) {
            FluidState fluidState = level.getFluidState(pos.relative(direction));
            if (fluidState.isSourceOfType(Fluids.WATER) || fluidState.is(Fluids.FLOWING_WATER)) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new PumpBoxBlockEntity(pos, state);
    }
}