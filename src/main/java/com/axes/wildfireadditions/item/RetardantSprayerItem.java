package com.axes.wildfireadditions.item;

import com.axes.wildfireadditions.coating.RetardantCoating;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * The Retardant Sprayer: a handheld tool that paints a fire-retardant coating over a small patch of
 * blocks. It leans entirely on vanilla tool mechanics for its economy and balance -
 *
 * <ul>
 *   <li><b>Durability.</b> Built with {@code .durability(256)}, so it wears like an iron tool - one
 *       point per block it actually coats (see {@link #useOn}).</li>
 *   <li><b>Refill = repair.</b> {@link #isValidRepairItem} makes Magma Cream a valid anvil repair
 *       material, so "reloading the chemical" is just repairing the tool the vanilla way - no fluid
 *       tanks, no custom UI.</li>
 * </ul>
 *
 * <p>The coating itself is not a block change: it's a per-chunk note recorded through
 * {@link RetardantCoating}. That's what lets a single click flag a 3x3 patch of arbitrary blocks
 * (stairs, fences, logs...) as fire-resistant without registering any new block states.
 */
public class RetardantSprayerItem extends Item {

    public static final int DURABILITY = 256;

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
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        BlockPos clicked = context.getClickedPos();
        Direction face = context.getClickedFace();

        // A 3x3 patch on the face the player is looking at: perpendicular to the clicked face's axis so
        // spraying a floor coats a flat 3x3, and spraying a wall coats a vertical 3x3 on that wall.
        List<BlockPos> patch = patchAround(clicked, face);

        if (level.isClientSide()) {
            // Server does the real work and drives the particles/sound; on the client we just report
            // a successful swing so the arm animates without predicting anything.
            return InteractionResult.SUCCESS;
        }

        ServerLevel serverLevel = (ServerLevel) level;
        Player player = context.getPlayer();
        ItemStack stack = context.getItemInHand();

        int coatedCount = 0;
        for (BlockPos pos : patch) {
            BlockState state = level.getBlockState(pos);
            // Skip the two cases the design calls out: empty air and blocks already coated.
            if (state.isAir()) continue;
            if (RetardantCoating.isCoated(level, pos)) continue;

            RetardantCoating.coat(serverLevel, pos);
            coatedCount++;
        }

        if (coatedCount == 0) {
            return InteractionResult.PASS; // Nothing new to coat - don't wear the tool for a no-op.
        }

        // One durability point per block actually coated.
        if (player != null && !player.getAbilities().instabuild) {
            EquipmentSlot slot = context.getHand() == InteractionHand.MAIN_HAND
                    ? EquipmentSlot.MAINHAND : EquipmentSlot.OFFHAND;
            stack.hurtAndBreak(coatedCount, player, slot);
        }

        emitSprayCone(serverLevel, context);
        level.playSound(null, clicked, SoundEvents.FIRE_EXTINGUISH, SoundSource.PLAYERS,
                0.7f, 1.6f + level.random.nextFloat() * 0.2f);

        return InteractionResult.CONSUME;
    }

    // The nine blocks in the plane of the clicked face, centred on the clicked block.
    private static List<BlockPos> patchAround(BlockPos center, Direction face) {
        List<BlockPos> out = new ArrayList<>(9);
        Direction.Axis axis = face.getAxis();
        for (int a = -1; a <= 1; a++) {
            for (int b = -1; b <= 1; b++) {
                BlockPos pos = switch (axis) {
                    case Y -> center.offset(a, 0, b);
                    case Z -> center.offset(a, b, 0);
                    case X -> center.offset(0, a, b);
                };
                out.add(pos);
            }
        }
        return out;
    }

    // A dense cone of red retardant dust blasted in the direction the player is looking, widening with
    // distance so it reads as an aerosol spray rather than a straight line of dots.
    private static void emitSprayCone(ServerLevel level, UseOnContext context) {
        Player player = context.getPlayer();
        Vec3 origin;
        Vec3 look;
        if (player != null) {
            origin = player.getEyePosition().add(player.getLookAngle().scale(0.4));
            look = player.getLookAngle();
        } else {
            // Dispenser-style fallback: spray straight out from the clicked face.
            Direction face = context.getClickedFace();
            look = Vec3.atLowerCornerOf(face.getNormal());
            origin = Vec3.atCenterOf(context.getClickedPos()).add(look.scale(0.6));
        }

        Vec3 right = look.cross(new Vec3(0, 1, 0));
        if (right.lengthSqr() < 1.0E-6) right = new Vec3(1, 0, 0); // Looking straight up/down.
        right = right.normalize();
        Vec3 up = right.cross(look).normalize();

        for (int i = 0; i < 34; i++) {
            double t = 0.3 + level.random.nextDouble() * 2.4; // Distance along the cone axis.
            double spread = 0.05 + t * 0.16; // Cone widens with distance.
            double rOff = (level.random.nextDouble() - 0.5) * 2.0 * spread;
            double uOff = (level.random.nextDouble() - 0.5) * 2.0 * spread;

            Vec3 p = origin.add(look.scale(t)).add(right.scale(rOff)).add(up.scale(uOff));
            Vec3 vel = look.scale(0.06);
            level.sendParticles(DustParticleOptions.REDSTONE, p.x, p.y, p.z, 1,
                    vel.x, vel.y, vel.z, 0.0);
        }
    }
}
