package com.axes.wildfireadditions.mixin.client;

import com.axes.wildfireadditions.config.WildfireConfig;
import com.axes.wildfireadditions.item.ReducedUseSlowdownItem;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * Softens the "using an item" movement penalty for our held field tools.
 *
 * <p>{@code LocalPlayer.aiStep} multiplies the player's movement input by {@code 0.2F} for as long as
 * {@code isUsingItem()} is true - the shuffle you feel while drawing a bow. Our drip torch, retardant
 * sprayer and fire hose are all "hold to use" tools, so they inherit that same 20%-speed crawl, which
 * makes it hard to reposition around a moving fire.
 *
 * <p>This intercepts that constant and, <i>only</i> when the item currently in use is a
 * {@link ReducedUseSlowdownItem}, returns {@link ReducedUseSlowdownItem#USE_MOVEMENT_MULTIPLIER}
 * (about half speed) instead. The {@code isUsingItem()} guard means the replacement can only ever apply
 * inside the item-use slowdown itself, and every other item keeps the vanilla 0.2. This is purely a
 * client-side movement feel change: the server never tightened the speed cap for item use, so moving at
 * half speed instead of a fifth is well within what it already allows.
 */
@Mixin(LocalPlayer.class)
public class LocalPlayerMixin {

    @ModifyConstant(method = "aiStep", constant = @Constant(floatValue = 0.2F))
    private float wildfireadditions$softenUseSlowdown(float original) {
        LocalPlayer self = (LocalPlayer) (Object) this;
        if (self.isUsingItem() && self.getUseItem().getItem() instanceof ReducedUseSlowdownItem) {
            return WildfireConfig.useMovementMultiplier();
        }
        return original;
    }
}
