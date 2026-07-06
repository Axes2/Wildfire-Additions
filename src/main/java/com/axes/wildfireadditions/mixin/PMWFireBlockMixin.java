package com.axes.wildfireadditions.mixin;

import com.axes.wildfireadditions.coating.RetardantCoating;
import com.axes.wildfireadditions.event.RetardantFireHandler;
import dev.protomanly.pmweather.block.PMWFireBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The real fire-blocking hook, and the heart of the Retardant Sprayer's "point 5" integration.
 *
 * <p>{@link PMWFireBlock#canBurnOn(Level, BlockState, BlockPos, int)} is PMWeather's own gatekeeper for
 * "can fire take/sustain on this block?" - every spread and sustain decision in {@code randomTick} calls
 * it with the fire's <i>current</i> intensity, and it already gates fuel by intensity (leaves and logs
 * are refused below intensity 4). We piggyback on exactly that mechanism: a coated block is treated as
 * unburnable until the fire pressing on it reaches {@link RetardantFireHandler#REQUIRED_INTENSITY}.
 *
 * <p>Because {@code pos} here is the position of the <i>fuel</i> block being tested, and the sprayer
 * only ever coats solid (non-air) blocks, this cleanly makes coated structures non-fuel for ordinary
 * fire while still letting a genuine inferno (intensity ≥ 9) break through - "difficult for everything
 * except the most intense infernos", achieved without altering PMWeather or inventing new block states.
 *
 * <p>The check is made nearly free when it doesn't matter: {@link RetardantCoating#isCoated} early-outs
 * on a fast per-chunk membership test, so fire burning anywhere without a nearby coating pays only a
 * set lookup, not a chunk-data read.
 */
@Mixin(value = PMWFireBlock.class, remap = false)
public class PMWFireBlockMixin {

    @Inject(method = "canBurnOn", at = @At("HEAD"), cancellable = true, remap = false)
    private static void wildfireadditions$vetoCoated(Level level, BlockState state, BlockPos pos,
                                                     int intensity, CallbackInfoReturnable<Boolean> cir) {
        if (intensity < RetardantFireHandler.REQUIRED_INTENSITY && RetardantCoating.isCoated(level, pos)) {
            cir.setReturnValue(false);
        }
    }
}
