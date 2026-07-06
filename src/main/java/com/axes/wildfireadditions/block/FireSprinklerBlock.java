package com.axes.wildfireadditions.block;

import com.axes.wildfireadditions.block.entity.FireSprinklerBlockEntity;
import com.axes.wildfireadditions.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

/**
 * The fire sprinkler turret. Right-click toggles it on and off; it only turns on when it sits directly
 * on top of a pump box that has an adjacent water source. While active it runs a
 * {@link FireSprinklerBlockEntity} ticker that hunts and douses nearby wildfire.
 */
public class FireSprinklerBlock extends Block implements EntityBlock {

    public static final BooleanProperty ACTIVE = BooleanProperty.create("active");

    public FireSprinklerBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(ACTIVE, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(ACTIVE);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        if (!level.isClientSide) {
            if (state.getValue(ACTIVE)) {
                level.setBlock(pos, state.setValue(ACTIVE, false), Block.UPDATE_ALL);
                if (level.getBlockEntity(pos) instanceof FireSprinklerBlockEntity be) {
                    be.setTarget(null);
                }
                player.displayClientMessage(Component.literal("Fire Sprinkler deactivated."), true);
            } else {
                if (!isPowered(level, pos)) {
                    player.displayClientMessage(Component.literal("Fire Sprinkler needs a pump box with an adjacent water source beneath it!"), true);
                    return InteractionResult.FAIL;
                }
                level.setBlock(pos, state.setValue(ACTIVE, true), Block.UPDATE_ALL);
                player.displayClientMessage(Component.literal("Fire Sprinkler activated."), true);
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    /**
     * True when the block directly below is a pump box that is itself adjacent to a water source - the
     * turret's supply requirement. Reuses the pump box's own water check so the rule stays identical.
     */
    public static boolean isPowered(Level level, BlockPos pos) {
        BlockPos below = pos.below();
        return level.getBlockState(below).getBlock() instanceof PumpBoxBlock
                && PumpBoxBlock.isAdjacentToWater(level, below);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new FireSprinklerBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (type != ModBlockEntities.FIRE_SPRINKLER_BE.get()) return null;
        if (level.isClientSide) {
            return (l, p, s, be) -> FireSprinklerBlockEntity.clientTick(l, p, s, (FireSprinklerBlockEntity) be);
        }
        return (l, p, s, be) -> FireSprinklerBlockEntity.serverTick(l, p, s, (FireSprinklerBlockEntity) be);
    }
}
