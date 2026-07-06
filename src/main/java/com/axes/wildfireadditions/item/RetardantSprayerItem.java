package com.axes.wildfireadditions.item;

import com.axes.wildfireadditions.coating.RetardantCoating;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The Retardant Sprayer: a handheld tool that paints a fire-retardant coating over the surface it's
 * aimed at. Like the fire hose, it's a <i>held</i> tool - hold right-click and it sprays continuously,
 * coating whatever solid surface is within {@link #RANGE} blocks of where you're looking.
 *
 * <p>It leans entirely on vanilla tool mechanics for its economy and balance:
 * <ul>
 *   <li><b>Durability.</b> Built with {@code .durability(256)}, so it wears like an iron tool - one
 *       point per block it actually coats. Re-spraying an already-coated surface costs nothing, so the
 *       real cost tracks how much <i>new</i> area you protect.</li>
 *   <li><b>Refill = repair.</b> {@link #isValidRepairItem} makes Magma Cream a valid anvil repair
 *       material, so "reloading the chemical" is just repairing the tool the vanilla way.</li>
 * </ul>
 *
 * <p>The coating itself is not a block change: it's a per-chunk note recorded through
 * {@link RetardantCoating}, which is what lets it flag arbitrary blocks (stairs, fences, logs...) as
 * fire-resistant without registering any new block states.
 */
public class RetardantSprayerItem extends Item {

    public static final int DURABILITY = 256;

    private static final double RANGE = 6.0; // How far the spray reaches, in blocks.
    private static final int SPRAY_INTERVAL = 4; // Ticks between coating passes (and durability drain).
    private static final int PARTICLE_INTERVAL = 2; // Ticks between cosmetic dust bursts.

    public RetardantSprayerItem(Properties properties) {
        super(properties);
    }

    // Anvil "refill": combining the sprayer with Magma Cream repairs its durability, standing in for
    // topping up the retardant compound. Purely vanilla anvil behaviour once this returns true.
    @Override
    public boolean isValidRepairItem(ItemStack toRepair, ItemStack repair) {
        return repair.is(Items.MAGMA_CREAM);
    }

    @Override
    public UseAnim getUseAnimation(ItemStack stack) {
        return UseAnim.BOW;
    }

    @Override
    public int getUseDuration(ItemStack stack, LivingEntity entity) {
        return 72000;
    }

    // Right-click starts the continuous spray; onUseTick then runs every tick while it's held.
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        player.startUsingItem(hand);
        return InteractionResultHolder.consume(player.getItemInHand(hand));
    }

    @Override
    public void onUseTick(Level level, LivingEntity entity, ItemStack stack, int remainingUseDuration) {
        if (!(entity instanceof Player player)) return;

        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getLookAngle();
        Vec3 end = eye.add(look.scale(RANGE));
        BlockHitResult hit = level.clip(new ClipContext(eye, end,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        boolean hasHit = hit.getType() == HitResult.Type.BLOCK;
        double reach = hasHit ? hit.getLocation().distanceTo(eye) : RANGE;

        // The server is authoritative and drives the visuals via sendParticles, so every nearby player
        // sees the same spray; the client just plays out the item-use animation.
        if (level.isClientSide()) return;
        ServerLevel serverLevel = (ServerLevel) level;

        if (player.tickCount % PARTICLE_INTERVAL == 0) {
            emitSprayCone(serverLevel, eye, look, reach);
        }

        if (!hasHit || player.tickCount % SPRAY_INTERVAL != 0) return;

        BlockPos center = hit.getBlockPos();
        Direction face = hit.getDirection();

        int coatedCount = 0;
        Direction.Axis axis = face.getAxis();
        for (int a = -1; a <= 1; a++) {
            for (int b = -1; b <= 1; b++) {
                BlockPos pos = switch (axis) {
                    case Y -> center.offset(a, 0, b);
                    case Z -> center.offset(a, b, 0);
                    case X -> center.offset(0, a, b);
                };
                BlockState state = level.getBlockState(pos);
                // Skip the two cases the design calls out: empty air and blocks already coated.
                if (state.isAir()) continue;
                if (RetardantCoating.isCoated(level, pos)) continue;

                RetardantCoating.coat(serverLevel, pos);
                coatedCount++;
            }
        }

        if (coatedCount == 0) return; // Aimed at bare air or an already-coated surface: no wear, no sound.

        if (!player.getAbilities().instabuild) {
            EquipmentSlot slot = player.getUsedItemHand() == InteractionHand.MAIN_HAND
                    ? EquipmentSlot.MAINHAND : EquipmentSlot.OFFHAND;
            stack.hurtAndBreak(coatedCount, player, slot);
        }

        level.playSound(null, center, SoundEvents.FIRE_EXTINGUISH, SoundSource.PLAYERS,
                0.4f, 1.6f + level.random.nextFloat() * 0.2f);
    }

    // A dense cone of red retardant dust from the nozzle out to where the spray lands, widening with
    // distance so it reads as an aerosol spray rather than a straight line of dots.
    private static void emitSprayCone(ServerLevel level, Vec3 origin, Vec3 look, double maxDist) {
        Vec3 right = look.cross(new Vec3(0, 1, 0));
        if (right.lengthSqr() < 1.0E-6) right = new Vec3(1, 0, 0); // Looking straight up/down.
        right = right.normalize();
        Vec3 up = right.cross(look).normalize();

        for (int i = 0; i < 22; i++) {
            double t = 0.2 + level.random.nextDouble() * (maxDist - 0.2); // Distance along the cone axis.
            double spread = 0.03 + t * 0.12; // Cone widens with distance.
            double rOff = (level.random.nextDouble() - 0.5) * 2.0 * spread;
            double uOff = (level.random.nextDouble() - 0.5) * 2.0 * spread;

            Vec3 p = origin.add(look.scale(t)).add(right.scale(rOff)).add(up.scale(uOff));
            level.sendParticles(DustParticleOptions.REDSTONE, p.x, p.y, p.z, 1, 0.0, 0.0, 0.0, 0.0);
        }
    }
}
