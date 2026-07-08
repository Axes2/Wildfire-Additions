package com.axes.wildfireadditions.client.hose;

import com.axes.wildfireadditions.config.WildfireConfig;
import com.axes.wildfireadditions.event.HosePhysicsHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Client-side verlet rope for one player's fire hose: a fixed chain of points integrated under
 * gravity, held together by stretch-only distance constraints, and collided against real block
 * collision shapes each solver iteration.
 *
 * <p>Collision here is strictly local penetration resolution - a point is only ever moved when it is
 * physically inside a block's collision shape, and it is pushed out along the shallowest axis. There
 * is deliberately no "scan for the surface" logic anywhere: the old renderer's upward ground-clamp is
 * what hoisted the hose onto tree canopies when the player walked under leaves. Leaves are skipped
 * entirely (matching the server's routing raycasts), so foliage can neither lift nor snag the rope.
 *
 * <p>The rope is purely visual. The server keeps its own coarse corner-chain for the gameplay rules
 * (taut warning, slowdown, snapping); this simulation only needs the pump position off the item.
 */
public final class HoseRopeSimulation {

    public static final int POINT_COUNT = 96;
    /** Half-thickness of the hose tube; collision keeps point centers this far off surfaces. */
    public static final double RADIUS = 0.11;

    private static final int CONSTRAINT_ITERATIONS = 6;
    /** Verlet gravity per tick^2. Tuned for a heavy, water-filled canvas hose rather than a whip. */
    private static final double GRAVITY = 0.06;
    private static final double AIR_DRAG = 0.94;
    /** Extra horizontal damping for points resting on something, so laid-out hose stays put. */
    private static final double GROUND_FRICTION = 0.45;
    /** Per-tick displacement cap per point; stops explosive corrections from tunnelling through blocks. */
    private static final double MAX_STEP = 0.5;
    /** Small clearance kept between the rope surface and block faces to avoid z-fighting. */
    private static final double SKIN = 0.02;
    /** Pushing a point up costs "less" than sideways in collision resolution, so near-ties settle on top. */
    private static final double UP_BIAS = 0.66;

    /** Extra length paid out beyond the straight-line need, so the hose drags with a natural droop. */
    private static final double SLACK = 1.5;
    /** How fast surplus length is reeled back in, in blocks per tick. Slow, so slack visibly lingers. */
    private static final double REEL_IN_RATE = 0.05;
    private static final double MIN_DEPLOYED = 3.0;
    /**
     * The visual rope can pay out right up to the length at which the server snaps the hose. Read live
     * from config (not a constant) so it tracks the server's synced hose-length setting.
     */
    private static double maxDeployed() {
        return WildfireConfig.maxHoseLength() + WildfireConfig.hoseSnapSlack();
    }
    /** An end anchor jumping further than this in one tick means teleport/desync: re-drape from scratch. */
    private static final double TELEPORT_RESET_DISTANCE = 8.0;

    private final BlockPos pumpPos;
    private final Vec3 pumpAnchor;

    // Current and previous tick positions (verlet state + render interpolation), as flat arrays to
    // avoid churning thousands of Vec3 allocations per tick.
    private final double[] x = new double[POINT_COUNT];
    private final double[] y = new double[POINT_COUNT];
    private final double[] z = new double[POINT_COUNT];
    private final double[] px = new double[POINT_COUNT];
    private final double[] py = new double[POINT_COUNT];
    private final double[] pz = new double[POINT_COUNT];
    private final boolean[] grounded = new boolean[POINT_COUNT];
    // Scratch cursor for collision queries; the sim only ever runs on the client tick thread.
    private final BlockPos.MutableBlockPos collisionCursor = new BlockPos.MutableBlockPos();

    /** Total rest length currently paid out of the pump, distributed evenly across all segments. */
    private double deployedLength;

    public HoseRopeSimulation(BlockPos pumpPos, Vec3 handAnchor) {
        this.pumpPos = pumpPos.immutable();
        this.pumpAnchor = HosePhysicsHandler.getPumpAnchor(this.pumpPos);
        drape(handAnchor);
    }

    public BlockPos pumpPos() {
        return pumpPos;
    }

    public int pointCount() {
        return POINT_COUNT;
    }

    /** Position of point {@code i} interpolated between the last two ticks, for smooth rendering. */
    public Vec3 getRenderPoint(int i, float partialTick) {
        return new Vec3(
                Mth.lerp(partialTick, px[i], x[i]),
                Mth.lerp(partialTick, py[i], y[i]),
                Mth.lerp(partialTick, pz[i], z[i]));
    }

    /** Advances the simulation by one tick. {@code handAnchor} is where the player holds the hose. */
    public void step(Level level, Vec3 handAnchor) {
        int last = POINT_COUNT - 1;

        // Teleports (or NaN from some pathological state) can't be simulated across - re-drape.
        double endJumpSq = distSqTo(last, handAnchor);
        if (!Double.isFinite(endJumpSq) || endJumpSq > TELEPORT_RESET_DISTANCE * TELEPORT_RESET_DISTANCE) {
            drape(handAnchor);
            return;
        }

        updateDeployedLength(handAnchor);
        integrate(handAnchor);

        double restLength = deployedLength / last;
        for (int iteration = 0; iteration < CONSTRAINT_ITERATIONS; iteration++) {
            solveDistanceConstraints(restLength);
            for (int i = 1; i < last; i++) {
                collidePoint(level, i);
            }
        }
    }

    /** Lays the rope out in a straight line pump-to-hand with no velocity; collision untangles it. */
    private void drape(Vec3 handAnchor) {
        int last = POINT_COUNT - 1;
        for (int i = 0; i <= last; i++) {
            double t = i / (double) last;
            x[i] = px[i] = Mth.lerp(t, pumpAnchor.x, handAnchor.x);
            y[i] = py[i] = Mth.lerp(t, pumpAnchor.y, handAnchor.y);
            z[i] = pz[i] = Mth.lerp(t, pumpAnchor.z, handAnchor.z);
            grounded[i] = false;
        }
        deployedLength = Mth.clamp(pumpAnchor.distanceTo(handAnchor) + SLACK, MIN_DEPLOYED, maxDeployed());
    }

    /**
     * Pay-out model. Growing is immediate: the rope stretching past its rest length (walking away,
     * or being forced the long way around an obstacle) pays out more hose at once. Reeling back in
     * is slow and only happens when segments are genuinely compressed (hose piling up on the
     * ground), so walking back toward the pump leaves believable slack lying around for a while.
     */
    private void updateDeployedLength(Vec3 handAnchor) {
        double arc = arcLength();
        double direct = pumpAnchor.distanceTo(handAnchor);
        if (arc > deployedLength * 1.02 || direct + SLACK * 0.5 > deployedLength) {
            deployedLength = Math.max(arc, direct + SLACK);
        } else if (deployedLength > arc + 2.0 * SLACK) {
            deployedLength = Math.max(arc + SLACK, deployedLength - REEL_IN_RATE);
        }
        deployedLength = Mth.clamp(deployedLength, MIN_DEPLOYED, maxDeployed());
    }

    private double arcLength() {
        double total = 0;
        for (int i = 0; i < POINT_COUNT - 1; i++) {
            double dx = x[i + 1] - x[i];
            double dy = y[i + 1] - y[i];
            double dz = z[i + 1] - z[i];
            total += Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
        return total;
    }

    private void integrate(Vec3 handAnchor) {
        int last = POINT_COUNT - 1;
        for (int i = 1; i < last; i++) {
            double vx = (x[i] - px[i]) * AIR_DRAG;
            double vy = (y[i] - py[i]) * AIR_DRAG;
            double vz = (z[i] - pz[i]) * AIR_DRAG;
            if (grounded[i]) {
                vx *= GROUND_FRICTION;
                vz *= GROUND_FRICTION;
            }
            vy -= GRAVITY;

            double speedSq = vx * vx + vy * vy + vz * vz;
            if (speedSq > MAX_STEP * MAX_STEP) {
                double scale = MAX_STEP / Math.sqrt(speedSq);
                vx *= scale;
                vy *= scale;
                vz *= scale;
            }

            px[i] = x[i];
            py[i] = y[i];
            pz[i] = z[i];
            x[i] += vx;
            y[i] += vy;
            z[i] += vz;
            grounded[i] = false;
        }

        // Pinned ends: previous position keeps its own trail so the renderer can interpolate them.
        px[0] = x[0];
        py[0] = y[0];
        pz[0] = z[0];
        x[0] = pumpAnchor.x;
        y[0] = pumpAnchor.y;
        z[0] = pumpAnchor.z;

        px[last] = x[last];
        py[last] = y[last];
        pz[last] = z[last];
        x[last] = handAnchor.x;
        y[last] = handAnchor.y;
        z[last] = handAnchor.z;
    }

    /**
     * Stretch-only rope constraints: segments longer than rest are pulled back together, shorter
     * ones are left alone so surplus hose compresses and piles instead of pushing itself straight.
     */
    private void solveDistanceConstraints(double restLength) {
        int last = POINT_COUNT - 1;
        for (int i = 0; i < last; i++) {
            double dx = x[i + 1] - x[i];
            double dy = y[i + 1] - y[i];
            double dz = z[i + 1] - z[i];
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (dist <= restLength || dist < 1.0E-7) continue;

            double excess = (dist - restLength) / dist;
            boolean pinA = i == 0;
            boolean pinB = i + 1 == last;
            double weightA = pinA ? 0.0 : (pinB ? 1.0 : 0.5);
            double weightB = pinB ? 0.0 : (pinA ? 1.0 : 0.5);

            x[i] += dx * excess * weightA;
            y[i] += dy * excess * weightA;
            z[i] += dz * excess * weightA;
            x[i + 1] -= dx * excess * weightB;
            y[i + 1] -= dy * excess * weightB;
            z[i + 1] -= dz * excess * weightB;
        }
    }

    /**
     * Resolves point {@code i} out of any block collision shape it penetrates. What counts as solid
     * is {@link HosePhysicsHandler#blocksHose} - the same rule the server's routing raycasts use -
     * so leaves can neither catch nor lift the rope (the "snaps upward under trees" bug).
     */
    private void collidePoint(Level level, int i) {
        int minBx = Mth.floor(x[i] - RADIUS);
        int maxBx = Mth.floor(x[i] + RADIUS);
        int minBy = Mth.floor(y[i] - RADIUS);
        int maxBy = Mth.floor(y[i] + RADIUS);
        int minBz = Mth.floor(z[i] - RADIUS);
        int maxBz = Mth.floor(z[i] + RADIUS);

        for (int bx = minBx; bx <= maxBx; bx++) {
            for (int by = minBy; by <= maxBy; by++) {
                for (int bz = minBz; bz <= maxBz; bz++) {
                    collisionCursor.set(bx, by, bz);
                    BlockState state = level.getBlockState(collisionCursor);
                    if (!HosePhysicsHandler.blocksHose(state)) continue;
                    VoxelShape shape = state.getCollisionShape(level, collisionCursor);
                    if (shape.isEmpty()) continue;
                    for (AABB box : shape.toAabbs()) {
                        resolveAgainst(i, box.move(bx, by, bz));
                    }
                }
            }
        }
    }

    private void resolveAgainst(int i, AABB box) {
        if (x[i] + RADIUS <= box.minX || x[i] - RADIUS >= box.maxX
                || y[i] + RADIUS <= box.minY || y[i] - RADIUS >= box.maxY
                || z[i] + RADIUS <= box.minZ || z[i] - RADIUS >= box.maxZ) {
            return;
        }

        // Penetration depth for escaping along each of the six directions.
        double pushUp = box.maxY - (y[i] - RADIUS);
        double pushDown = (y[i] + RADIUS) - box.minY;
        double pushWest = (x[i] + RADIUS) - box.minX;
        double pushEast = box.maxX - (x[i] - RADIUS);
        double pushNorth = (z[i] + RADIUS) - box.minZ;
        double pushSouth = box.maxZ - (z[i] - RADIUS);

        // Cheapest escape wins; "up" is discounted so corner-ish penetrations settle on top of
        // blocks instead of being spat out sideways.
        int bestDir = 0; // 0=up 1=down 2=west 3=east 4=north 5=south
        double bestCost = pushUp * UP_BIAS;
        if (pushDown < bestCost) { bestCost = pushDown; bestDir = 1; }
        if (pushWest < bestCost) { bestCost = pushWest; bestDir = 2; }
        if (pushEast < bestCost) { bestCost = pushEast; bestDir = 3; }
        if (pushNorth < bestCost) { bestCost = pushNorth; bestDir = 4; }
        if (pushSouth < bestCost) { bestDir = 5; }

        switch (bestDir) {
            case 0 -> {
                y[i] = box.maxY + RADIUS + SKIN;
                grounded[i] = true;
            }
            case 1 -> y[i] = box.minY - RADIUS - SKIN;
            case 2 -> x[i] = box.minX - RADIUS - SKIN;
            case 3 -> x[i] = box.maxX + RADIUS + SKIN;
            case 4 -> z[i] = box.minZ - RADIUS - SKIN;
            case 5 -> z[i] = box.maxZ + RADIUS + SKIN;
        }
    }

    private double distSqTo(int i, Vec3 target) {
        double dx = x[i] - target.x;
        double dy = y[i] - target.y;
        double dz = z[i] - target.z;
        return dx * dx + dy * dy + dz * dz;
    }
}
