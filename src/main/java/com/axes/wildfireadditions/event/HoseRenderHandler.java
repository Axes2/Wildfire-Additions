package com.axes.wildfireadditions.event;

import com.axes.wildfireadditions.WildfireAdditions;
import com.axes.wildfireadditions.client.hose.HoseRopeManager;
import com.axes.wildfireadditions.client.hose.HoseRopeSimulation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Draws every active {@link HoseRopeSimulation} as a continuous textured tube. All hose motion -
 * sag, draping, ground contact - comes from the simulation; this class only smooths the point chain
 * and extrudes geometry around it. Points are interpolated between the last two simulation ticks, so
 * the hose moves as fluidly as entities do regardless of frame rate.
 */
@EventBusSubscriber(modid = WildfireAdditions.MODID, value = Dist.CLIENT)
public class HoseRenderHandler {

    private static final ResourceLocation HOSE_TEXTURE = ResourceLocation.fromNamespaceAndPath(WildfireAdditions.MODID, "textures/entity/hose.png");
    // Half-width of the hose tube. Tied to the simulation's collision radius so the rendered surface
    // sits exactly on the surfaces the physics resolved against - never clipping in, never floating.
    private static final float THICKNESS = (float) HoseRopeSimulation.RADIUS;
    private static final double TEXTURE_TILE_LENGTH = 1.0; // The hose texture repeats once per block of length

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) return;

        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        PoseStack poseStack = event.getPoseStack();
        if (level == null || poseStack == null) return;

        Collection<HoseRopeSimulation> ropes = HoseRopeManager.activeRopes();
        if (ropes.isEmpty()) return;

        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(true);
        Vec3 cameraPos = event.getCamera().getPosition();

        // entityCutoutNoCull renders both sides of every quad: at tight bends the rings can wind
        // "backwards" relative to the camera, and a culled render type made those faces vanish.
        VertexConsumer vertexConsumer = mc.renderBuffers().bufferSource().getBuffer(RenderType.entityCutoutNoCull(HOSE_TEXTURE));
        poseStack.pushPose();
        poseStack.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);
        Matrix4f matrix = poseStack.last().pose();

        for (HoseRopeSimulation rope : ropes) {
            renderTube(vertexConsumer, matrix, level, buildPath(rope, partialTick));
        }

        poseStack.popPose();
    }

    // The rope points are already ~1 block apart and are rendered as straight segments between each
    // pair - no smoothing spline - so the hose reads as a chunky, faceted line that matches the
    // game's blocky style rather than a rounded cable.
    private static List<Vec3> buildPath(HoseRopeSimulation rope, float partialTick) {
        int count = rope.pointCount();
        List<Vec3> points = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            points.add(rope.getRenderPoint(i, partialTick));
        }
        return points;
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

            if (tangent.lengthSqr() < 1.0E-8) tangent = i > 0 ? tangents[i - 1] : new Vec3(0, 0, 1);
            tangents[i] = tangent.normalize();
        }

        // Parallel-transport frames: start from a world-up-derived cross-section, then carry it along
        // the curve by rotating it with the tangent at each step. Unlike re-deriving every frame from
        // world-up, this cannot suddenly flip 90 degrees when a piled or hanging stretch of hose turns
        // near-vertical - the ring orientation always changes as gradually as the path itself does.
        Vec3 reference = Math.abs(tangents[0].y) > 0.99 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
        rights[0] = tangents[0].cross(reference).normalize();
        ups[0] = rights[0].cross(tangents[0]).normalize();

        for (int i = 1; i < n; i++) {
            Vec3 right = rotateAlong(tangents[i - 1], tangents[i], rights[i - 1]);
            // Numerical drift accumulates over hundreds of points; re-orthogonalize against the tangent.
            right = right.subtract(tangents[i].scale(right.dot(tangents[i])));
            if (right.lengthSqr() < 1.0E-8) {
                Vec3 fallback = Math.abs(tangents[i].y) > 0.99 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
                right = tangents[i].cross(fallback);
            }
            rights[i] = right.normalize();
            ups[i] = rights[i].cross(tangents[i]).normalize();
        }

        // Adjacent path points usually share a block (the path is subdivided finer than a block), so
        // only re-query the light level when the containing block actually changes.
        int[] lights = new int[n];
        BlockPos lastLightPos = null;
        int lastLight = 0;
        for (int i = 0; i < n; i++) {
            Vec3 p = path.get(i);
            BlockPos pos = BlockPos.containing(p.x, p.y, p.z);
            if (!pos.equals(lastLightPos)) {
                lastLight = LevelRenderer.getLightColor(level, pos);
                lastLightPos = pos;
            }
            lights[i] = lastLight;
        }

        double traveled = 0;
        for (int i = 0; i < n - 1; i++) {
            Vec3 a = path.get(i);
            Vec3 b = path.get(i + 1);
            double segmentLength = a.distanceTo(b);

            Vec3[] ringA = buildRing(a, rights[i], ups[i]);
            Vec3[] ringB = buildRing(b, rights[i + 1], ups[i + 1]);

            float vStart = (float) (traveled / TEXTURE_TILE_LENGTH);
            float vEnd = (float) ((traveled + segmentLength) / TEXTURE_TILE_LENGTH);

            // The 4 sides of the tube: top, left, bottom, right (relative to this ring's own basis)
            drawSideQuad(consumer, matrix, ringA[0], ringA[1], ringB[1], ringB[0], ups[i], lights[i], lights[i + 1], vStart, vEnd);
            drawSideQuad(consumer, matrix, ringA[1], ringA[2], ringB[2], ringB[1], rights[i].scale(-1), lights[i], lights[i + 1], vStart, vEnd);
            drawSideQuad(consumer, matrix, ringA[2], ringA[3], ringB[3], ringB[2], ups[i].scale(-1), lights[i], lights[i + 1], vStart, vEnd);
            drawSideQuad(consumer, matrix, ringA[3], ringA[0], ringB[0], ringB[3], rights[i], lights[i], lights[i + 1], vStart, vEnd);

            traveled += segmentLength;
        }

        // Cap both ends so the tube reads as a solid rope instead of an open, hollow pipe.
        Vec3[] startRing = buildRing(path.get(0), rights[0], ups[0]);
        drawCap(consumer, matrix, startRing, tangents[0].scale(-1), lights[0], true);

        Vec3[] endRing = buildRing(path.get(n - 1), rights[n - 1], ups[n - 1]);
        drawCap(consumer, matrix, endRing, tangents[n - 1], lights[n - 1], false);
    }

    // Rotates `v` by the same rotation that takes unit vector `fromDir` onto unit vector `toDir`
    // (Rodrigues' formula) - the parallel-transport step for carrying a ring frame along the curve.
    private static Vec3 rotateAlong(Vec3 fromDir, Vec3 toDir, Vec3 v) {
        Vec3 axis = fromDir.cross(toDir);
        double sin = axis.length();
        double cos = fromDir.dot(toDir);
        if (sin < 1.0E-6) {
            // Collinear tangents: nothing to rotate. (A perfect 180-degree reversal between two
            // adjacent points can't survive the distance constraints, so it isn't handled specially.)
            return v;
        }
        Vec3 k = axis.scale(1.0 / sin);
        return v.scale(cos)
                .add(k.cross(v).scale(sin))
                .add(k.scale(k.dot(v) * (1.0 - cos)));
    }

    private static Vec3[] buildRing(Vec3 center, Vec3 right, Vec3 up) {
        Vec3 r = right.scale(THICKNESS);
        Vec3 u = up.scale(THICKNESS);
        return new Vec3[]{
                center.add(r).add(u), center.subtract(r).add(u),
                center.subtract(r).subtract(u), center.add(r).subtract(u)
        };
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
