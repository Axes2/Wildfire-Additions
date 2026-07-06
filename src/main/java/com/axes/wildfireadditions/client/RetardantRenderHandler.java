package com.axes.wildfireadditions.client;

import com.axes.wildfireadditions.WildfireAdditions;
import com.axes.wildfireadditions.coating.ClientCoatingStore;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.model.BakedQuad;
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
 * Paints the retardant tint by re-rendering each coated block's real baked model as a <b>flat, semi-
 * transparent red overlay</b>. After the world's own geometry is drawn, for every coated block we ask
 * {@link BlockRenderDispatcher} for its {@link BakedModel}, then re-emit that model's quad geometry -
 * <i>positions only, no texture</i> - in translucent red. Because it's the real model geometry:
 *
 * <ul>
 *   <li>Grass, ferns and flowers get red exactly on their cross-planes.</li>
 *   <li>Torches, fences, stairs and slabs hug their real shape.</li>
 *   <li>Complex modded models with no collision still wrap perfectly.</li>
 * </ul>
 *
 * <h2>Why flat colour, not the textured model</h2>
 * An earlier version re-rendered the model <i>with its texture</i> and multiplied by red - which turns a
 * green grass texture into dark mud, not red. Here we discard the texture entirely and fill the model's
 * quads with a flat red colour via {@link RenderType#debugSectionQuads()} (a position-colour, depth-
 * tested, no-cull, translucent layer), so the result is a clean red wash on the exact geometry.
 *
 * <h2>No z-fighting, no seams</h2>
 * Rather than scaling the model up (which makes neighbouring blocks' overlays overlap and double their
 * alpha into dark seam lines), we nudge the whole model a hair <i>toward the camera</i>
 * ({@link #CAMERA_NUDGE}). That's a rigid shift: it lifts the overlay just in front of the real block to
 * beat z-fighting, while adjacent blocks stay edge-to-edge with no overlap - so no seams.
 *
 * <p>Blocks that render via a block entity ({@link RenderShape#ENTITYBLOCK_ANIMATED} - chests, signs,
 * beds) have no baked block model to re-render and are skipped.
 */
@EventBusSubscriber(modid = WildfireAdditions.MODID, value = Dist.CLIENT)
public final class RetardantRenderHandler {

    private static final float R = 0.90f, G = 0.06f, B = 0.06f, A = 0.38f;
    private static final double CAMERA_NUDGE = 0.01; // Blocks to lift the overlay toward the camera.
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
        VertexConsumer consumer = buffers.getBuffer(RenderType.debugSectionQuads());
        BlockRenderDispatcher dispatcher = mc.getBlockRenderer();
        RandomSource random = RandomSource.create();

        int[] budget = {MAX_MODELS};
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        ClientCoatingStore.forEachCoating(now, packedPos -> {
            if (budget[0] <= 0) return;
            cursor.set(BlockPos.getX(packedPos), BlockPos.getY(packedPos), BlockPos.getZ(packedPos));

            double cx = cursor.getX() + 0.5 - cam.x;
            double cy = cursor.getY() + 0.5 - cam.y;
            double cz = cursor.getZ() + 0.5 - cam.z;
            if (cx * cx + cy * cy + cz * cz > MAX_RENDER_DIST_SQR) return;

            BlockState state = level.getBlockState(cursor);
            // Only blocks that actually have a baked block model to re-render. Air/liquids and
            // block-entity-rendered blocks (chests, signs, beds) have none.
            if (state.getRenderShape() != RenderShape.MODEL) return;

            BakedModel model = dispatcher.getBlockModel(state);
            ModelData modelData = model.getModelData(level, cursor, state, ModelData.EMPTY);
            renderTintedModel(pose, consumer, state, model, modelData, cursor, cam, random);
            budget[0]--;
        });

        buffers.endBatch(RenderType.debugSectionQuads());
    }

    private static void renderTintedModel(PoseStack pose, VertexConsumer consumer, BlockState state,
                                          BakedModel model, ModelData modelData, BlockPos pos, Vec3 cam,
                                          RandomSource random) {
        // Direction from the block centre toward the camera, so we can lift the overlay slightly in
        // front of the real block (beats z-fighting) as a rigid shift that doesn't cause seam overlap.
        double dx = cam.x - (pos.getX() + 0.5);
        double dy = cam.y - (pos.getY() + 0.5);
        double dz = cam.z - (pos.getZ() + 0.5);
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len > 1.0E-4) {
            dx = dx / len * CAMERA_NUDGE;
            dy = dy / len * CAMERA_NUDGE;
            dz = dz / len * CAMERA_NUDGE;
        } else {
            dx = dy = dz = 0.0;
        }

        pose.pushPose();
        pose.translate(pos.getX() - cam.x + dx, pos.getY() - cam.y + dy, pos.getZ() - cam.z + dz);
        PoseStack.Pose last = pose.last();

        for (Direction dir : DIRECTIONS) {
            random.setSeed(state.getSeed(pos)); // Vanilla reseeds per face so model variants stay stable.
            List<BakedQuad> quads = model.getQuads(state, dir, random, modelData, null);
            for (BakedQuad quad : quads) {
                emitQuadFlat(consumer, last, quad);
            }
        }

        pose.popPose();
    }

    // Emits a quad's four corner positions in flat red, ignoring its texture/UV entirely (the render
    // layer is position-colour). The positions live in the quad's packed vertex data.
    private static void emitQuadFlat(VertexConsumer consumer, PoseStack.Pose pose, BakedQuad quad) {
        int[] verts = quad.getVertices();
        int stride = verts.length / 4; // ints per vertex (position occupies the first three).
        for (int i = 0; i < 4; i++) {
            int o = i * stride;
            float x = Float.intBitsToFloat(verts[o]);
            float y = Float.intBitsToFloat(verts[o + 1]);
            float z = Float.intBitsToFloat(verts[o + 2]);
            consumer.addVertex(pose, x, y, z).setColor(R, G, B, A);
        }
    }

    // Wipe the client mirror on disconnect so coatings from one world never linger into the next.
    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientCoatingStore.clear();
    }
}
