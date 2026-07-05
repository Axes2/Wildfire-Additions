package com.axes.wildfireadditions.event;

import com.axes.wildfireadditions.WildfireAdditions;
import com.axes.wildfireadditions.item.HoseItem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
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
import net.minecraft.world.level.Level;
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

    private static final ResourceLocation HOSE_TEXTURE = ResourceLocation.fromNamespaceAndPath(WildfireAdditions.MODID, "textures/entity/hose.png");
    private static final float THICKNESS = 0.11f; // Half-width of the hose tube, in blocks
    private static final double TEXTURE_TILE_LENGTH = 1.0; // The hose texture repeats once per block of length

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
        renderNodes.add(HosePhysicsHandler.getPumpAnchor(BlockPos.of(tag.getLong("PumpPos")))); // Start at Pump

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

        // Flatten the corner-to-corner nodes plus the sagging subdivisions of the held segment into
        // a single continuous point path, so the tube below can share one cross-section ring at every
        // joint instead of building each segment as its own disconnected box.
        List<Vec3> path = buildPath(renderNodes);

        PoseStack poseStack = event.getPoseStack();
        Vec3 cameraPos = event.getCamera().getPosition();

        VertexConsumer vertexConsumer = mc.renderBuffers().bufferSource().getBuffer(RenderType.entitySolid(HOSE_TEXTURE));
        poseStack.pushPose();
        poseStack.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);
        Matrix4f matrix = poseStack.last().pose();

        renderTube(vertexConsumer, matrix, player.level(), path);

        poseStack.popPose();
    }

    private static List<Vec3> buildPath(List<Vec3> macroNodes) {
        List<Vec3> path = new ArrayList<>();
        path.add(macroNodes.get(0));

        for (int i = 0; i < macroNodes.size() - 1; i++) {
            Vec3 start = macroNodes.get(i);
            Vec3 end = macroNodes.get(i + 1);
            boolean isHeldSegment = (i == macroNodes.size() - 2); // Only the piece you're holding sags

            if (!isHeldSegment) {
                path.add(end);
                continue;
            }

            double distance = start.distanceTo(end);
            double sag = Math.min(distance * 0.1, 1.5);
            int segments = 12;
            for (int s = 1; s <= segments; s++) {
                float t = s / (float) segments;
                double x = Mth.lerp(t, start.x, end.x);
                double y = Mth.lerp(t, start.y, end.y) + (4.0 * sag * t * (t - 1.0)); // Parabola
                double z = Mth.lerp(t, start.z, end.z);
                path.add(new Vec3(x, y, z));
            }
        }
        return path;
    }

    // Extrudes a single continuous tube along `path`. Cross-section rings are computed per vertex
    // (not per segment) so two adjoining segments share the exact same ring at their joint - this is
    // what stops a visible gap/twist from opening up into the hollow interior at every bend.
    private static void renderTube(VertexConsumer consumer, Matrix4f matrix, Level level, List<Vec3> path) {
        int n = path.size();
        if (n < 2) return;

        Vec3[] tangents = new Vec3[n];
        Vec3[] rights = new Vec3[n];
        Vec3[] ups = new Vec3[n];

        for (int i = 0; i < n; i++) {
            Vec3 tangent;
            if (i == 0) tangent = path.get(1).subtract(path.get(0));
            else if (i == n - 1) tangent = path.get(n - 1).subtract(path.get(n - 2));
            else tangent = path.get(i + 1).subtract(path.get(i - 1));

            if (tangent.lengthSqr() < 1.0E-8) tangent = new Vec3(0, 0, 1);
            tangent = tangent.normalize();

            // A shared world-up reference keeps the cross-section from twisting between vertices,
            // except when the hose runs (near) straight up/down, where that reference is degenerate.
            Vec3 reference = Math.abs(tangent.y) > 0.99 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
            Vec3 right = tangent.cross(reference).normalize();
            Vec3 up = right.cross(tangent).normalize();

            tangents[i] = tangent;
            rights[i] = right;
            ups[i] = up;
        }

        double traveled = 0;
        for (int i = 0; i < n - 1; i++) {
            Vec3 a = path.get(i);
            Vec3 b = path.get(i + 1);
            double segmentLength = a.distanceTo(b);

            Vec3[] ringA = buildRing(a, rights[i], ups[i]);
            Vec3[] ringB = buildRing(b, rights[i + 1], ups[i + 1]);

            int lightA = sampleLight(level, a);
            int lightB = sampleLight(level, b);

            float vStart = (float) (traveled / TEXTURE_TILE_LENGTH);
            float vEnd = (float) ((traveled + segmentLength) / TEXTURE_TILE_LENGTH);

            // The 4 sides of the tube: top, left, bottom, right (relative to this ring's own basis)
            drawSideQuad(consumer, matrix, ringA[0], ringA[1], ringB[1], ringB[0], ups[i], lightA, lightB, vStart, vEnd);
            drawSideQuad(consumer, matrix, ringA[1], ringA[2], ringB[2], ringB[1], rights[i].scale(-1), lightA, lightB, vStart, vEnd);
            drawSideQuad(consumer, matrix, ringA[2], ringA[3], ringB[3], ringB[2], ups[i].scale(-1), lightA, lightB, vStart, vEnd);
            drawSideQuad(consumer, matrix, ringA[3], ringA[0], ringB[0], ringB[3], rights[i], lightA, lightB, vStart, vEnd);

            traveled += segmentLength;
        }

        // Cap both ends so the tube reads as a solid rope instead of an open, hollow pipe.
        Vec3[] startRing = buildRing(path.get(0), rights[0], ups[0]);
        int startLight = sampleLight(level, path.get(0));
        drawCap(consumer, matrix, startRing, tangents[0].scale(-1), startLight, true);

        Vec3[] endRing = buildRing(path.get(n - 1), rights[n - 1], ups[n - 1]);
        int endLight = sampleLight(level, path.get(n - 1));
        drawCap(consumer, matrix, endRing, tangents[n - 1], endLight, false);
    }

    private static Vec3[] buildRing(Vec3 center, Vec3 right, Vec3 up) {
        Vec3 r = right.scale(THICKNESS);
        Vec3 u = up.scale(THICKNESS);
        return new Vec3[]{
                center.add(r).add(u), center.subtract(r).add(u),
                center.subtract(r).subtract(u), center.add(r).subtract(u)
        };
    }

    private static int sampleLight(Level level, Vec3 pos) {
        return LevelRenderer.getLightColor(level, BlockPos.containing(pos.x, pos.y, pos.z));
    }

    private static void drawSideQuad(VertexConsumer consumer, Matrix4f matrix, Vec3 ringACorner0, Vec3 ringACorner1,
                                      Vec3 ringBCorner1, Vec3 ringBCorner0, Vec3 normal,
                                      int lightA, int lightB, float vStart, float vEnd) {
        int overlay = OverlayTexture.NO_OVERLAY;
        float nx = (float) normal.x, ny = (float) normal.y, nz = (float) normal.z;

        consumer.addVertex(matrix, (float) ringACorner0.x, (float) ringACorner0.y, (float) ringACorner0.z)
                .setColor(255, 255, 255, 255).setUv(0, vStart).setOverlay(overlay).setLight(lightA).setNormal(nx, ny, nz);
        consumer.addVertex(matrix, (float) ringACorner1.x, (float) ringACorner1.y, (float) ringACorner1.z)
                .setColor(255, 255, 255, 255).setUv(1, vStart).setOverlay(overlay).setLight(lightA).setNormal(nx, ny, nz);
        consumer.addVertex(matrix, (float) ringBCorner1.x, (float) ringBCorner1.y, (float) ringBCorner1.z)
                .setColor(255, 255, 255, 255).setUv(1, vEnd).setOverlay(overlay).setLight(lightB).setNormal(nx, ny, nz);
        consumer.addVertex(matrix, (float) ringBCorner0.x, (float) ringBCorner0.y, (float) ringBCorner0.z)
                .setColor(255, 255, 255, 255).setUv(0, vEnd).setOverlay(overlay).setLight(lightB).setNormal(nx, ny, nz);
    }

    private static void drawCap(VertexConsumer consumer, Matrix4f matrix, Vec3[] ring, Vec3 normal, int light, boolean reverseWinding) {
        Vec3 v0 = ring[0], v1 = ring[1], v2 = ring[2], v3 = ring[3];
        if (reverseWinding) {
            drawFlatQuad(consumer, matrix, v3, v2, v1, v0, normal, light);
        } else {
            drawFlatQuad(consumer, matrix, v0, v1, v2, v3, normal, light);
        }
    }

    private static void drawFlatQuad(VertexConsumer consumer, Matrix4f matrix, Vec3 v1, Vec3 v2, Vec3 v3, Vec3 v4, Vec3 normal, int light) {
        int overlay = OverlayTexture.NO_OVERLAY;
        float nx = (float) normal.x, ny = (float) normal.y, nz = (float) normal.z;

        consumer.addVertex(matrix, (float) v1.x, (float) v1.y, (float) v1.z).setColor(255, 255, 255, 255).setUv(0, 0).setOverlay(overlay).setLight(light).setNormal(nx, ny, nz);
        consumer.addVertex(matrix, (float) v2.x, (float) v2.y, (float) v2.z).setColor(255, 255, 255, 255).setUv(0, 1).setOverlay(overlay).setLight(light).setNormal(nx, ny, nz);
        consumer.addVertex(matrix, (float) v3.x, (float) v3.y, (float) v3.z).setColor(255, 255, 255, 255).setUv(1, 1).setOverlay(overlay).setLight(light).setNormal(nx, ny, nz);
        consumer.addVertex(matrix, (float) v4.x, (float) v4.y, (float) v4.z).setColor(255, 255, 255, 255).setUv(1, 0).setOverlay(overlay).setLight(light).setNormal(nx, ny, nz);
    }
}
