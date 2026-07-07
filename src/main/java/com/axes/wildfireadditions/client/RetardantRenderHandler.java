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
import net.minecraft.resources.ResourceLocation;
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
 * Paints the retardant as a red "powder stain" texture wrapped onto each coated block's real baked
 * model, by re-emitting its {@link BakedQuad} geometry a second time into a translucent, texture-
 * parameterised vanilla render type ({@link RenderType#entityTranslucent}) bound to
 * {@link #OVERLAY_TEXTURE} instead of the block atlas.
 *
 * <p>An earlier version of this class tried to reuse vanilla's block-breaking decal machinery
 * ({@code SheetedDecalTextureGenerator} / {@code BlockRenderDispatcher.renderBreakingTexture}) to get
 * decal UVs "for free". That class turned out not to be safely referenceable here, so this version
 * avoids it entirely and instead derives decal UVs itself with nothing but arithmetic:
 *
 * <ol>
 *   <li>For each quad, read its own baked UV corners (already present in {@link BakedQuad#getVertices()}
 *       right alongside the positions - these point into the block atlas).</li>
 *   <li>Take that quad's own min/max U and min/max V, and linearly remap each corner into local
 *       {@code 0..1} space. Since a baked quad's four atlas UV corners always bound exactly one sprite
 *       region (whatever part of the block's real texture that quad uses), this recovers a normal
 *       per-quad {@code 0..1} UV without ever asking the atlas manager anything.</li>
 *   <li>Push the remapped UV, not the original one, so we sample {@link #OVERLAY_TEXTURE} - our own
 *       standalone stain image - instead of the block's real texture.</li>
 * </ol>
 *
 * <p>This keeps the property that made the model-re-render approach worth doing in the first place:
 * because it's the exact same quads the block renderer draws, grass/fern cross-planes, torches, stairs
 * and arbitrary modded geometry all get the stain wrapped onto their real shape, not a bounding box.
 *
 * <p>Vertex colour is left plain white so only the texture's own RGBA determines the result - an earlier
 * version multiplied the block's *own* texture by a red tint, which turned green grass to mud; sampling a
 * dedicated stain texture (transparent where untouched, red where stained) avoids that entirely.
 *
 * <p>The overlay is nudged a hair toward the camera ({@link #CAMERA_NUDGE}) to beat z-fighting against
 * the real block, as a rigid shift rather than a scale - so neighbouring blocks stay edge-to-edge with
 * no overlapping translucency (no seams). Rendering reads {@link ClientCoatingStore}, so the tint self-
 * expires as notes lapse. Blocks that render via a block entity ({@link RenderShape#ENTITYBLOCK_ANIMATED}
 * - chests, signs, beds) have no baked block model and are skipped.
 *
 * <h2>Hiding the repeat</h2>
 * Every coated block samples the same {@link #OVERLAY_TEXTURE}, so the texture itself is what has to
 * carry the "doesn't look like it's repeating" property - two things make that work:
 * <ul>
 *   <li><b>Seamless tiling.</b> The texture is generated from toroidal (wrap-around) noise, so its own
 *       edges already match up - no hard border where one tile's pattern meets the next.</li>
 *   <li><b>No dominant shape.</b> An earlier version used smooth value noise, which - however well it
 *       tiled - always produced one or two big "hero" blobs per tile that the eye could immediately
 *       recognise repeating. The texture is now multi-scale, toroidal <i>Worley</i> (cellular) noise:
 *       several independent populations of small, similarly-sized, irregularly-jittered patches, summed
 *       together. Because no single shape dominates the tile's silhouette, there's no specific landmark
 *       for the eye to lock onto - the same principle that makes vanilla's cobblestone texture repeat
 *       invisibly despite being the exact same 16x16 image on every block, with no per-block rotation.</li>
 * </ul>
 * (An earlier version tried hiding the repeat by rotating/mirroring the texture per block instead -
 * vanilla's trick for e.g. mycelium and dirt paths. That works for low-contrast textures, but rotating a
 * punchy, high-contrast pattern creates a hard visible discontinuity at every boundary where a block's
 * orientation differs from its neighbour's - since most neighbours get different orientations, that
 * produced *more* visible straight lines, not fewer. Fixing the texture's own repeatability instead of
 * fighting it with rotation is what actually solves this.)
 */
@EventBusSubscriber(modid = WildfireAdditions.MODID, value = Dist.CLIENT)
public final class RetardantRenderHandler {

    private static final ResourceLocation OVERLAY_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(WildfireAdditions.MODID, "textures/misc/retardant_overlay.png");
    private static final RenderType OVERLAY_RENDER_TYPE = RenderType.entityTranslucent(OVERLAY_TEXTURE);

    private static final double CAMERA_NUDGE = 0.01; // Blocks to lift the overlay toward the camera.
    // Kept generous so retardant dropped from an aircraft is still visible from cruising altitude, rather
    // than being culled the moment you climb away from it. The MAX_MODELS budget still bounds frame cost.
    private static final double MAX_RENDER_DIST_SQR = 160.0 * 160.0;
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
        VertexConsumer consumer = buffers.getBuffer(OVERLAY_RENDER_TYPE);
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
            // Only blocks with a baked block model. A coated block that's since been broken (or was a
            // plant that burned/popped) leaves a stale note over air - skipped here, pruned on expiry.
            if (state.getRenderShape() != RenderShape.MODEL) return;

            BakedModel model = dispatcher.getBlockModel(state);
            ModelData modelData = model.getModelData(level, cursor, state, ModelData.EMPTY);

            renderStain(pose, consumer, level, state, model, modelData, cursor.immutable(), cam, random);
            budget[0]--;
        });

        buffers.endBatch(OVERLAY_RENDER_TYPE);
    }

    private static void renderStain(PoseStack pose, VertexConsumer consumer, ClientLevel level, BlockState state,
                                    BakedModel model, ModelData modelData, BlockPos pos, Vec3 cam,
                                    RandomSource random) {
        // Direction from the block centre toward the camera, so the overlay can be lifted a hair in
        // front of the real block (beats z-fighting) as a rigid shift - not a scale, so it can't cause
        // neighbouring blocks' overlays to overlap into seams.
        double dx = cam.x - (pos.getX() + 0.5);
        double dy = cam.y - (pos.getY() + 0.5);
        double dz = cam.z - (pos.getZ() + 0.5);
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double nx = 0, ny = 0, nz = 0;
        if (len > 1.0E-4) {
            nx = dx / len * CAMERA_NUDGE;
            ny = dy / len * CAMERA_NUDGE;
            nz = dz / len * CAMERA_NUDGE;
        }

        pose.pushPose();
        pose.translate(pos.getX() - cam.x + nx, pos.getY() - cam.y + ny, pos.getZ() - cam.z + nz);
        PoseStack.Pose last = pose.last();

        for (Direction dir : DIRECTIONS) {
            // Light doesn't propagate INSIDE an opaque block, so a solid cube's own position reads as
            // dark regardless of time of day. Vanilla's own block renderer sidesteps this by sampling
            // each face's light from the block just past that face (e.g. a top face reads the air
            // above it), not the block's own position - match that here, per direction, or the coating
            // renders black on every solid surface. A null-direction quad (cross-plane plants, which
            // don't cull against a neighbour) has no such neighbour, so it uses the block's own light.
            BlockPos lightPos = dir != null ? pos.relative(dir) : pos;
            int light = LevelRenderer.getLightColor(level, lightPos);

            random.setSeed(state.getSeed(pos)); // Vanilla reseeds per face so model variants stay stable.
            List<BakedQuad> quads = model.getQuads(state, dir, random, modelData, null);
            for (BakedQuad quad : quads) {
                emitQuadStained(consumer, last, quad, light);
            }
        }

        pose.popPose();
    }

    // Re-emits one quad's four corners against our own stain texture: positions come straight from the
    // quad, but UV is the quad's own baked (atlas-space) UV linearly remapped to local 0..1 space (see
    // class doc), so it samples OVERLAY_TEXTURE correctly instead of wherever it originally pointed in
    // the block atlas. Colour is left white so only the stain texture's own RGBA shows through.
    private static void emitQuadStained(VertexConsumer consumer, PoseStack.Pose pose, BakedQuad quad, int light) {
        int[] verts = quad.getVertices();
        int stride = verts.length / 4; // ints per vertex: position(3) + color(1) + uv(2) + ...

        float[] px = new float[4], py = new float[4], pz = new float[4];
        float[] u = new float[4], v = new float[4];
        for (int i = 0; i < 4; i++) {
            int o = i * stride;
            px[i] = Float.intBitsToFloat(verts[o]);
            py[i] = Float.intBitsToFloat(verts[o + 1]);
            pz[i] = Float.intBitsToFloat(verts[o + 2]);
            u[i] = Float.intBitsToFloat(verts[o + 4]);
            v[i] = Float.intBitsToFloat(verts[o + 5]);
        }

        float uMin = Math.min(Math.min(u[0], u[1]), Math.min(u[2], u[3]));
        float uMax = Math.max(Math.max(u[0], u[1]), Math.max(u[2], u[3]));
        float vMin = Math.min(Math.min(v[0], v[1]), Math.min(v[2], v[3]));
        float vMax = Math.max(Math.max(v[0], v[1]), Math.max(v[2], v[3]));
        float uSpan = Math.max(uMax - uMin, 1.0E-5f);
        float vSpan = Math.max(vMax - vMin, 1.0E-5f);

        // Face normal via the quad's own two edges - a basic per-quad flat normal is all lighting needs.
        float ex1 = px[1] - px[0], ey1 = py[1] - py[0], ez1 = pz[1] - pz[0];
        float ex2 = px[2] - px[0], ey2 = py[2] - py[0], ez2 = pz[2] - pz[0];
        float nx = ey1 * ez2 - ez1 * ey2;
        float ny = ez1 * ex2 - ex1 * ez2;
        float nz = ex1 * ey2 - ey1 * ex2;
        float nLen = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (nLen > 1.0E-8f) {
            nx /= nLen;
            ny /= nLen;
            nz /= nLen;
        } else {
            nx = 0;
            ny = 1;
            nz = 0;
        }

        for (int i = 0; i < 4; i++) {
            float localU = (u[i] - uMin) / uSpan;
            float localV = (v[i] - vMin) / vSpan;
            consumer.addVertex(pose, px[i], py[i], pz[i])
                    .setColor(1.0f, 1.0f, 1.0f, 1.0f)
                    .setUv(localU, localV)
                    .setOverlay(OverlayTexture.NO_OVERLAY)
                    .setLight(light)
                    .setNormal(pose, nx, ny, nz);
        }
    }

    // Wipe the client mirror on disconnect so coatings from one world never linger into the next.
    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientCoatingStore.clear();
    }
}
