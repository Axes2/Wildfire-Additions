package com.axes.wildfireadditions.registry;

import com.axes.wildfireadditions.WildfireAdditions;
import com.axes.wildfireadditions.block.PumpBoxBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.DeferredBlock;

public class ModBlocks {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(WildfireAdditions.MODID);

    public static final DeferredBlock<Block> PUMP_BOX = BLOCKS.register("pump_box",
            () -> new PumpBoxBlock(BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(3.0f).requiresCorrectToolForDrops()));
}