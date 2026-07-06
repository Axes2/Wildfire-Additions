package com.axes.wildfireadditions.client;

import com.axes.wildfireadditions.WildfireAdditions;
import com.axes.wildfireadditions.block.entity.FireSprinklerBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;

/**
 * Renders the fire sprinkler's rotating head as a separate baked model on top of the static base, so it
 * can swivel (yaw only) toward whatever the turret is currently targeting. The base (model pixels 0-8)
 * comes from the normal blockstate model; this draws the head (pixels 9-31) rotated about the central
 * vertical axis.
 */
public class FireSprinklerRenderer implements BlockEntityRenderer<FireSprinklerBlockEntity> {

    public static final ModelResourceLocation HEAD_MODEL = ModelResourceLocation.standalone(
            ResourceLocation.fromNamespaceAndPath(WildfireAdditions.MODID, "block/fire_sprinkler_head"));

    private final BlockRenderDispatcher blockRenderer;

    public FireSprinklerRenderer(BlockEntityRendererProvider.Context context) {
        this.blockRenderer = context.getBlockRenderDispatcher();
    }

    @Override
    public void render(FireSprinklerBlockEntity be, float partialTick, PoseStack poseStack, MultiBufferSource buffer, int packedLight, int packedOverlay) {
        BakedModel model = Minecraft.getInstance().getModelManager().getModel(HEAD_MODEL);
        BlockState state = be.getBlockState();
        float yaw = Mth.rotLerp(partialTick, be.prevRenderYaw, be.renderYaw);

        poseStack.pushPose();
        // Rotate the head about the block's central vertical axis (0.5, y, 0.5).
        poseStack.translate(0.5, 0.0, 0.5);
        poseStack.mulPose(Axis.YP.rotationDegrees(yaw));
        poseStack.translate(-0.5, 0.0, -0.5);

        VertexConsumer consumer = buffer.getBuffer(RenderType.cutout());
        blockRenderer.getModelRenderer().renderModel(
                poseStack.last(), consumer, state, model,
                1.0f, 1.0f, 1.0f, packedLight, packedOverlay, ModelData.EMPTY, RenderType.cutout());

        poseStack.popPose();
    }
}
