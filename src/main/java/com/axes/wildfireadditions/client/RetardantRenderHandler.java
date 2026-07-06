package com.axes.wildfireadditions.client;

import com.axes.wildfireadditions.WildfireAdditions;
import com.axes.wildfireadditions.coating.ClientCoatingStore;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.SheetedDecalTextureGenerator;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.model.data.ModelData;

/**
 * Paints the retardant as a <b>decal projected onto each coated block's real model</b>, using the very
 * pipeline vanilla uses for block-breaking cracks - just with a red "powder stain" texture instead of
 * crack textures. For each coated block we call
 * {@link BlockRenderDispatcher#renderBreakingTexture}, feeding it a {@link SheetedDecalTextureGenerator}
 * that re-emits the model's quads with decal UVs generated from their positions, into
 * {@link RenderType#crumbling} bound to {@link #OVERLAY_TEXTURE}.
 *
 * <p>Why this beats both previous attempts:
 * <ul>
 *   <li><b>Fits everything.</b> It's the crack-overlay machinery, so it wraps grass/fern cross-planes,
 *       torches, stairs, fences and arbitrary modded models exactly - vanilla has kept this correct for
 *       every block model that exists.</li>
 *   <li><b>No z-fighting, no seams.</b> The crumbling render type uses polygon-offset layering (the
 *       purpose-built fix for decals over existing geometry), so there's no scale-up or camera nudge -
 *       neighbouring blocks stay edge-to-edge with no overlapping translucency.</li>
 *   <li><b>Not a flat colour wash.</b> The look now comes from a texture: blotchy, soft-edged red
 *       staining with neutral gaps, so surfaces read as dusted with retardant powder rather than
 *       repainted a solid cartoon red. The crumbling blend is multiplicative (like cracks darkening a
 *       block), so the stain interacts with the underlying surface - brighter on pale blocks, a deep
 *       sooty red on dark bark - instead of being one uniform sticker everywhere.</li>
 * </ul>
 *
 * <p>Texture notes: the overlay PNG spans exactly one block face (decal UVs run 0..1 per face). In the
 * crumbling blend, 50% gray is invisible/neutral and transparent pixels are discarded, so the texture
 * encodes stain <i>strength</i> as distance from neutral gray toward red - that's what gives the soft
 * fade-out at splotch edges. Adjust the texture alone to restyle the coating.
 *
 * <p>Rendering reads {@link ClientCoatingStore}, so the tint self-expires as notes lapse. Blocks that
 * render via a block entity (chests, signs, beds - {@link RenderShape#ENTITYBLOCK_ANIMATED}) have no
 * baked block model and are skipped.
 */
@EventBusSubscriber(modid = WildfireAdditions.MODID, value = Dist.CLIENT)
public final class RetardantRenderHandler {

    private static final ResourceLocation OVERLAY_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(WildfireAdditions.MODID, "textures/misc/retardant_overlay.png");
    // crumbling() is memoized, but hold the instance anyway so per-frame use is a plain field read.
    private static final RenderType OVERLAY_RENDER_TYPE = RenderType.crumbling(OVERLAY_TEXTURE);

    private static final double MAX_RENDER_DIST_SQR = 64.0 * 64.0;
    private static final int MAX_MODELS = 4096; // Hard cap so a huge sprayed area can't stall a frame.

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
        BlockRenderDispatcher dispatcher = mc.getBlockRenderer();

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

            pose.pushPose();
            pose.translate(cursor.getX() - cam.x, cursor.getY() - cam.y, cursor.getZ() - cam.z);
            // Exactly how LevelRenderer draws breaking cracks: wrap the crumbling buffer in a decal
            // UV generator anchored at this block's pose, then re-tesselate the block model into it.
            VertexConsumer decal = new SheetedDecalTextureGenerator(
                    buffers.getBuffer(OVERLAY_RENDER_TYPE), pose.last(), 1.0f);
            ModelData modelData = dispatcher.getBlockModel(state).getModelData(level, cursor, state, ModelData.EMPTY);
            dispatcher.renderBreakingTexture(state, cursor, level, pose, decal, modelData);
            pose.popPose();

            budget[0]--;
        });

        buffers.endBatch(OVERLAY_RENDER_TYPE);
    }

    // Wipe the client mirror on disconnect so coatings from one world never linger into the next.
    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientCoatingStore.clear();
    }
}
