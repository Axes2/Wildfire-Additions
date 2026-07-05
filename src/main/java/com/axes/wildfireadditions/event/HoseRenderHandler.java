package com.axes.wildfireadditions.event;

import com.axes.wildfireadditions.WildfireAdditions;
import com.axes.wildfireadditions.item.HoseItem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

@EventBusSubscriber(modid = WildfireAdditions.MODID, value = Dist.CLIENT)
public class HoseRenderHandler {

    // We borrow the gray concrete texture to make the hose look solid and physical
    private static final ResourceLocation HOSE_TEXTURE = ResourceLocation.withDefaultNamespace("textures/block/gray_concrete.png");

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) return;

        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return;

        ItemStack mainHand = player.getMainHandItem();
        ItemStack offHand = player.getOffhandItem();
        ItemStack hoseStack = mainHand.getItem() instanceof HoseItem ? mainHand : (offHand.getItem() instanceof HoseItem ? offHand : null);
        if (hoseStack == null) return;

        CustomData customData = hoseStack.get(DataComponents.CUSTOM_DATA);
        if (customData == null || !customData.contains("PumpPos")) return;

        CompoundTag tag = customData.copyTag();
        if (!tag.getString("PumpDimension").equals(player.level().dimension().location().toString())) return;

        // Build the list of physical nodes
        List<Vec3> renderNodes = new ArrayList<>();
        renderNodes.add(Vec3.atBottomCenterOf(BlockPos.of(tag.getLong("PumpPos"))).add(0, 1.0, 0)); // Start at Pump

        if (tag.contains("HoseNodes")) {
            ListTag nodesList = tag.getList("HoseNodes", Tag.TAG_COMPOUND);
            for (int i = 1; i < nodesList.size(); i++) { // Skip index 0 as it's the pump box
                CompoundTag nodeTag = nodesList.getCompound(i);
                // Lift corners slightly off the ground to prevent Z-fighting with grass
                renderNodes.add(new Vec3(nodeTag.getDouble("x"), nodeTag.getDouble("y") + 0.1, nodeTag.getDouble("z")));
            }
        }

        // End at player's hand
        double handOffset = player.getMainArm() == net.minecraft.world.entity.HumanoidArm.RIGHT ? 0.4 : -0.4;
        Vec3 endPos = player.position().add(
                -Math.sin(player.yBodyRot * (Math.PI / 180F)) * handOffset,
                1.0, // Hand height
                Math.cos(player.yBodyRot * (Math.PI / 180F)) * handOffset
        );
        renderNodes.add(endPos);

        PoseStack poseStack = event.getPoseStack();
        Vec3 cameraPos = event.getCamera().getPosition();

        // Setup 3D Rendering Context
        VertexConsumer vertexConsumer = mc.renderBuffers().bufferSource().getBuffer(RenderType.entitySolid(HOSE_TEXTURE));
        poseStack.pushPose();
        poseStack.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);
        Matrix4f matrix = poseStack.last().pose();

        // Draw each segment of the hose
        for (int i = 0; i < renderNodes.size() - 1; i++) {
            boolean isLastSegment = (i == renderNodes.size() - 2);
            render3DHoseSegment(vertexConsumer, matrix, renderNodes.get(i), renderNodes.get(i + 1), isLastSegment);
        }

        poseStack.popPose();
    }

    private static void render3DHoseSegment(VertexConsumer consumer, Matrix4f matrix, Vec3 start, Vec3 end, boolean applySag) {
        double distance = start.distanceTo(end);
        double sag = applySag ? Math.min(distance * 0.1, 1.5) : 0; // Only sag the piece you are holding

        int segments = applySag ? 12 : 1; // Straight lines don't need subdivisions
        float thickness = 0.1f; // 10% of a block thick

        Vec3 previousPoint = start;
        for (int i = 1; i <= segments; i++) {
            float t = i / (float) segments;

            double lerpX = Mth.lerp(t, start.x, end.x);
            double lerpY = Mth.lerp(t, start.y, end.y);
            double lerpZ = Mth.lerp(t, start.z, end.z);
            lerpY += (4.0 * sag * t * (t - 1.0)); // Apply parabola

            Vec3 currentPoint = new Vec3(lerpX, lerpY, lerpZ);

            // Calculate 3D Box vectors
            Vec3 dir = currentPoint.subtract(previousPoint).normalize();
            Vec3 up = Math.abs(dir.y) > 0.99 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0); // Handle looking straight down
            Vec3 right = dir.cross(up).normalize().scale(thickness);
            Vec3 realUp = right.cross(dir).normalize().scale(thickness);

            // Calculate the 4 corners of the box at Start and End
            Vec3[] s = {
                    previousPoint.add(right).add(realUp), previousPoint.subtract(right).add(realUp),
                    previousPoint.subtract(right).subtract(realUp), previousPoint.add(right).subtract(realUp)
            };
            Vec3[] e = {
                    currentPoint.add(right).add(realUp), currentPoint.subtract(right).add(realUp),
                    currentPoint.subtract(right).subtract(realUp), currentPoint.add(right).subtract(realUp)
            };

            // Draw the 4 sides of the tube (Top, Left, Bottom, Right)
            drawQuad(consumer, matrix, s[0], s[1], e[1], e[0]);
            drawQuad(consumer, matrix, s[1], s[2], e[2], e[1]);
            drawQuad(consumer, matrix, s[2], s[3], e[3], e[2]);
            drawQuad(consumer, matrix, s[3], s[0], e[0], e[3]);

            previousPoint = currentPoint;
        }
    }

    private static void drawQuad(VertexConsumer consumer, Matrix4f matrix, Vec3 v1, Vec3 v2, Vec3 v3, Vec3 v4) {
        int overlay = OverlayTexture.NO_OVERLAY;
        int light = 15728880; // Full bright block lighting

        consumer.addVertex(matrix, (float) v1.x, (float) v1.y, (float) v1.z).setColor(255, 255, 255, 255).setUv(0, 0).setOverlay(overlay).setLight(light).setNormal(0, 1, 0);
        consumer.addVertex(matrix, (float) v2.x, (float) v2.y, (float) v2.z).setColor(255, 255, 255, 255).setUv(0, 1).setOverlay(overlay).setLight(light).setNormal(0, 1, 0);
        consumer.addVertex(matrix, (float) v3.x, (float) v3.y, (float) v3.z).setColor(255, 255, 255, 255).setUv(1, 1).setOverlay(overlay).setLight(light).setNormal(0, 1, 0);
        consumer.addVertex(matrix, (float) v4.x, (float) v4.y, (float) v4.z).setColor(255, 255, 255, 255).setUv(1, 0).setOverlay(overlay).setLight(light).setNormal(0, 1, 0);
    }
}