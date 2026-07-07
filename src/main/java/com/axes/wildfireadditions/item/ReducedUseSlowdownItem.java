package com.axes.wildfireadditions.item;

/**
 * Marker for our held, hold-to-use field tools - the drip torch, retardant sprayer and fire hose.
 *
 * <p>All three lean on vanilla's "use item" mechanic ({@code startUsingItem} + a long use duration) to
 * run their per-tick logic while right-click is held. A side effect of that mechanic is that vanilla
 * slows the player to {@code 0.2} (20% of walking speed) for as long as <i>any</i> item is being used -
 * the same clamp that keeps you shuffling while drawing a bow or eating. For a firefighting tool that's
 * the wrong feel: you constantly need to reposition around a fire that's moving, and 20% speed makes
 * that a slog.
 *
 * <p>{@link com.axes.wildfireadditions.mixin.client.LocalPlayerMixin} reads this marker and, only for
 * tools that carry it, softens that clamp to {@link #USE_MOVEMENT_MULTIPLIER} (about half speed)
 * instead of the vanilla 0.2. Every other item - bows, food, shields - keeps vanilla behaviour.
 */
public interface ReducedUseSlowdownItem {

    /**
     * Movement-input multiplier applied while one of these tools is in use, replacing vanilla's 0.2.
     * 0.5 = roughly half normal walking speed: still clearly "occupied", but mobile enough to flank a
     * moving fire.
     */
    float USE_MOVEMENT_MULTIPLIER = 0.5F;
}
