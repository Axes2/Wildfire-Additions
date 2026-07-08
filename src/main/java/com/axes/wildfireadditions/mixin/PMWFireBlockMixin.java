package com.axes.wildfireadditions.mixin;

import com.axes.wildfireadditions.coating.RetardantCoating;
import com.axes.wildfireadditions.config.WildfireConfig;
import com.axes.wildfireadditions.event.DripTorchFireHandler;
import com.axes.wildfireadditions.event.RetardantFireHandler;
import dev.protomanly.pmweather.block.PMWFireBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
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
 *
 * <p>This mixin also carries the airtight half of the drip torch's wildfire-prevention guarantee (see
 * {@link DripTorchFireHandler} class doc): {@link #wildfireadditions$capEmberIntensity} clamps the
 * intensity our own tracked embers' {@code randomTick} reads, so their fire can never climb into
 * PMWeather's native spread/scorch/whirl thresholds even when the same block is random-ticked more than
 * once in a single game tick.
 */
@Mixin(value = PMWFireBlock.class, remap = false)
public class PMWFireBlockMixin {

    @Inject(method = "canBurnOn", at = @At("HEAD"), cancellable = true, remap = false)
    private static void wildfireadditions$vetoCoated(Level level, BlockState state, BlockPos pos,
                                                     int intensity, CallbackInfoReturnable<Boolean> cir) {
        if (intensity < WildfireConfig.coatingBreakthroughIntensity() && RetardantCoating.isCoated(level, pos)) {
            cir.setReturnValue(false);
        }
    }

    /**
     * Caps the intensity that {@code randomTick} reads for one of our drip-torch embers.
     *
     * <p>This is the load-bearing fix for the "rare runaway wildfire" bug. {@code randomTick} reads the
     * block's stored intensity once at the top, bumps it, writes it back, and - critically, in the same
     * call - checks {@code intensity > 2} to decide whether to run native {@code trySpreadFireBlock}.
     * Our {@link DripTorchFireHandler} clamps embers back to {@link DripTorchFireHandler#CAP} only at
     * {@code LevelTickEvent.Post}, which cannot help if the same block is random-ticked twice within one
     * game tick: the second tick would read the un-clamped 2, reach 3-4, and satisfy the spread check
     * before Post ever runs - spawning an untracked, uncapped fire, i.e. a real wildfire.
     *
     * <p>By redirecting that read and clamping it to the cap <i>every</i> tick for our tracked embers,
     * every {@code randomTick} computes from a base of at most the cap regardless of the stored value.
     * The bump can then reach at most {@code cap + 1 = 2} in any single call, {@code intensity > 2} is
     * unreachable, and intensity can never accumulate across ticks - closing the window at the source
     * for any number of ticks in a single game tick. Non-ember (wildfire) fire is left untouched: the
     * redirect returns the real stored value unless the position is a tracked ember.
     */
    @Redirect(
            method = "randomTick(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Lnet/minecraft/util/RandomSource;)V",
            at = @At(
                    value = "INVOKE",
                    ordinal = 0,
                    target = "Lnet/minecraft/world/level/block/state/BlockState;getValue(Lnet/minecraft/world/level/block/state/properties/Property;)Ljava/lang/Comparable;"
            ),
            remap = false
    )
    private Comparable<?> wildfireadditions$capEmberIntensity(BlockState receiver, Property<?> property,
                                                              BlockState state, ServerLevel level, BlockPos pos,
                                                              RandomSource random) {
        Comparable<?> value = receiver.getValue(property);
        if (property == PMWFireBlock.INTENSITY
                && value instanceof Integer intensity
                && intensity > DripTorchFireHandler.CAP
                && DripTorchFireHandler.isTrackedEmber(level, pos)) {
            return DripTorchFireHandler.CAP;
        }
        return value;
    }
}
