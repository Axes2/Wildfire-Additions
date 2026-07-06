package com.axes.wildfireadditions.client;

import com.axes.wildfireadditions.WildfireAdditions;
import com.axes.wildfireadditions.coating.ClientCoatingStore;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.model.data.ModelData;

import java.util.List;

/**
 * Paints the retardant tint by <b>re-rendering each coated block's real baked model</b> in translucent
 * red, rather than drawing a box around it. After the world's own geometry is drawn, for every coated
 * block we ask {@link BlockRenderDispatcher} for its {@link BakedModel}, then push that model's quads
 * again through a translucent buffer with a red colour injected. Because we render the actual model:
 *
 * <ul>
 *   <li>Grass, ferns and flowers tint onto their exact cross-planes (the transparent parts of the
 *       texture stay transparent, so only the blades/petals go red).</li>
 *   <li>Torches, fences, stairs and slabs hug their real geometry.</li>
 *   <li>Complex modded models with no collision still get a pixel-perfect wrap.</li>
 * </ul>
 *
 * <p>Two details make it look right: the model is scaled up by a hair ({@link #INFLATE}) around its
 * centre so it doesn't z-fight the real block, and the quads are pushed with an explicit alpha (which
 * {@code ModelBlockRenderer} can't do on its own - it writes opaque) so the overlay is genuinely
 * translucent. Rendering off {@link ClientCoatingStore} means the tint self-expires as notes lapse.
 *
 * <p>Blocks that render via a block entity ({@link RenderShape#ENTITYBLOCK_ANIMATED} - chests, signs,
 * beds) have no baked block model to re-render, so they're skipped.
 */
@EventBusSubscriber(modid = WildfireAdditions.MODID, value = Dist.CLIENT)
public final class RetardantRenderHandler {

    private static final float R = 0.85f, G = 0.05f, B = 0.05f, A = 0.35f;
    private static final float INFLATE = 1.02f; // Scale-up around centre to avoid z-fighting the real block.
    private static final double MAX_RENDER_DIST_SQR = 64.0 * 64.0;
    private static final int MAX_MODELS = 4096; // Hard cap so a huge sprayed area can't stall a frame.

    // All six face directions plus the null "general" bucket, so we gather every quad of the model.
    private static final Direction[] DIRECTIONS = {
            Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, null
    };

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
        VertexConsumer consumer = buffers.getBuffer(RenderType.translucent());
        BlockRenderDispatcher dispatcher = mc.getBlockRenderer();
        RandomSource random = RandomSource.create();

        int[] budget = {MAX_MODELS};
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        ClientCoatingStore.forEachCoating(now, packedPos -> {
            if (budget[0] <= 0) return;
            cursor.set(BlockPos.getX(packedPos), BlockPos.getY(packedPos), BlockPos.getZ(packedPos));

            double dx = cursor.getX() + 0.5 - cam.x;
            double dy = cursor.getY() + 0.5 - cam.y;
            double dz = cursor.getZ() + 0.5 - cam.z;
            if (dx * dx + dy * dy + dz * dz > MAX_RENDER_DIST_SQR) return;

            BlockState state = level.getBlockState(cursor);
            // Only blocks that actually have a baked block model to re-render. Air/liquids and
            // block-entity-rendered blocks (chests, signs, beds) have none.
            if (state.getRenderShape() != RenderShape.MODEL) return;

            BakedModel model = dispatcher.getBlockModel(state);
            ModelData modelData = model.getModelData(level, cursor, state, ModelData.EMPTY);
            int light = LevelRenderer.getLightColor(level, cursor);

            renderTintedModel(pose, consumer, state, model, modelData, cursor, cam, random, light);
            budget[0]--;
        });

        buffers.endBatch(RenderType.translucent());
    }

    private static void renderTintedModel(PoseStack pose, VertexConsumer consumer, BlockState state,
                                          BakedModel model, ModelData modelData, BlockPos pos, Vec3 cam,
                                          RandomSource random, int light) {
        pose.pushPose();
        // Position in world space; the pose carries the -camera offset.
        pose.translate(pos.getX() - cam.x, pos.getY() - cam.y, pos.getZ() - cam.z);
        // Inflate a hair around the block centre so the tint sits just proud of the real geometry.
        pose.translate(0.5, 0.5, 0.5);
        pose.scale(INFLATE, INFLATE, INFLATE);
        pose.translate(-0.5, -0.5, -0.5);

        PoseStack.Pose last = pose.last();
        float[] brightness = {1.0f, 1.0f, 1.0f, 1.0f};
        int[] lightmap = {light, light, light, light};

        for (Direction dir : DIRECTIONS) {
            random.setSeed(state.getSeed(pos)); // Vanilla reseeds per face so model variants stay stable.
            List<BakedQuad> quads = model.getQuads(state, dir, random, modelData, null);
            for (BakedQuad quad : quads) {
                // Push each quad tinted red at our alpha. readExistingColor=false => use (R,G,B,A)
                // directly for every vertex instead of the quad's baked colour, giving a uniform red
                // wash that still respects the texture's own transparency (cross-planes stay see-through).
                consumer.putBulkData(last, quad, brightness, R, G, B, A, lightmap,
                        OverlayTexture.NO_OVERLAY, false);
            }
        }

        pose.popPose();
    }

    // Wipe the client mirror on disconnect so coatings from one world never linger into the next.
    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientCoatingStore.clear();
    }
}
