package com.axes.wildfireadditions.registry;

import com.axes.wildfireadditions.WildfireAdditions;
import com.axes.wildfireadditions.block.entity.PumpBoxBlockEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredRegister;
import java.util.function.Supplier;

public class ModBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, WildfireAdditions.MODID);

    public static final Supplier<BlockEntityType<PumpBoxBlockEntity>> PUMP_BOX_BE = BLOCK_ENTITIES.register("pump_box",
            () -> BlockEntityType.Builder.of(PumpBoxBlockEntity::new, ModBlocks.PUMP_BOX.get()).build(null));
}