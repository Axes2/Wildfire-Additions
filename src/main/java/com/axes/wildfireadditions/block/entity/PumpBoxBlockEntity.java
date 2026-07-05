package com.axes.wildfireadditions.block.entity;

import com.axes.wildfireadditions.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public class PumpBoxBlockEntity extends BlockEntity {
    public PumpBoxBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.PUMP_BOX_BE.get(), pos, state);
    }
}