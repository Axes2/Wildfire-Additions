package com.axes.wildfireadditions.client;

import com.axes.wildfireadditions.WildfireAdditions;
import com.axes.wildfireadditions.coating.ClientCoatingStore;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.debug.DebugRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * Paints the retardant tint. After the world's solid, fluid and translucent geometry is drawn, we step
 * in and, for every coated block the client knows about ({@link ClientCoatingStore}), fetch that
 * block's actual collision shape, inflate it a hair to avoid z-fighting with the block face, and draw a
 * semi-transparent red box over it. Because we read the real shape per block, stairs, slabs, fences and
 * full blocks all get a snugly fitted tint with no bespoke textures.
 *
 * <p>Rendering off the shared {@link ClientCoatingStore} means the tint self-expires: {@code forEachCoating}
 * skips (and prunes) notes whose timestamp has passed, measured against the synced game time.
 */
@EventBusSubscriber(modid = WildfireAdditions.MODID, value = Dist.CLIENT)
public final class RetardantRenderHandler {

    private static final float R = 0.85f, G = 0.05f, B = 0.05f, A = 0.32f;
    private static final AABB FULL_CUBE = new AABB(0, 0, 0, 1, 1, 1);
    private static final double MAX_RENDER_DIST_SQR = 64.0 * 64.0;
    private static final int MAX_BOXES = 8192; // Hard cap so a pathologically large area can't stall a frame.

    private RetardantRenderHandler() {
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;

        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) return;

        Vec3 cam = event.getCamera().getPosition();
        long now = level.getGameTime();
        PoseStack pose = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();

        pose.pushPose();
        pose.translate(-cam.x, -cam.y, -cam.z); // Draw the boxes in world space; pose handles the camera offset.

        int[] budget = {MAX_BOXES};
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        ClientCoatingStore.forEachCoating(now, packedPos -> {
            if (budget[0] <= 0) return;
            cursor.set(BlockPos.getX(packedPos), BlockPos.getY(packedPos), BlockPos.getZ(packedPos));

            double dx = cursor.getX() + 0.5 - cam.x;
            double dy = cursor.getY() + 0.5 - cam.y;
            double dz = cursor.getZ() + 0.5 - cam.z;
            if (dx * dx + dy * dy + dz * dz > MAX_RENDER_DIST_SQR) return;

            BlockState state = level.getBlockState(cursor);
            // Mirror the sprayer's own rule: only tint blocks with a real collision shape. This skips
            // non-solid undergrowth (tall grass, ferns, flowers, ...) whose thin, per-position offset
            // shapes make the box tint misfit, and it also hides any such coatings sprayed before the
            // application-side rule was added.
            VoxelShape collision = state.getCollisionShape(level, cursor);
            if (collision.isEmpty()) return;

            VoxelShape shape = state.getShape(level, cursor);
            AABB local = shape.isEmpty() ? FULL_CUBE : shape.bounds();
            AABB box = local.inflate(0.01).move(cursor.getX(), cursor.getY(), cursor.getZ());

            DebugRenderer.renderFilledBox(pose, buffers, box, R, G, B, A);
            budget[0]--;
        });

        pose.popPose();
        buffers.endBatch();
    }

    // Wipe the client mirror on disconnect so coatings from one world never linger into the next.
    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientCoatingStore.clear();
    }
}
