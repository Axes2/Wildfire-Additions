package com.axes.wildfireadditions.client.hose;

import com.axes.wildfireadditions.config.WildfireConfig;
import com.axes.wildfireadditions.event.HosePhysicsHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Client-side verlet rope for one player's fire hose: a chain of points integrated under gravity,
 * held together by stretch-only distance constraints, and collided against real block collision
 * shapes each solver iteration.
 *
 * <p>Collision here is strictly local penetration resolution - a point is only ever moved when it is
 * physically inside a block's collision shape, and it is pushed out along the shallowest axis. There
 * is deliberately no "scan for the surface" logic anywhere: the old renderer's upward ground-clamp is
 * what hoisted the hose onto tree canopies when the player walked under leaves. Leaves are skipped
 * entirely (matching the server's routing raycasts), so foliage can neither lift nor snag the rope.
 *
 * <p>The chain runs roughly one point per block ({@link #SEGMENT_LENGTH}), so the rope reads as a
 * chunky, faceted line in keeping with the game's blocky style rather than a smooth spline. The
 * active point count grows and shrinks with how much hose is currently paid out.
 *
 * <p>The rope is purely visual. The server keeps its own coarse corner-chain for the gameplay rules
 * (taut warning, slowdown, snapping); this simulation only needs the pump position off the item.
 */
public final class HoseRopeSimulation {

    /** Half-thickness of the hose tube; collision keeps point centers this far off surfaces. */
    public static final double RADIUS = 0.11;
    /** Target spacing between rope points. ~1 block gives the deliberately blocky, low-node look. */
    public static final double SEGMENT_LENGTH = 1.0;

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

    /** Constant droop kept beyond the straight-line need, so the hose hangs a little instead of a taut wire. */
    private static final double SLACK = 1.25;
    /** How fast surplus length is pulled back in, in blocks per tick (~8 b/s: keeps up with a sprint). */
    private static final double RETRACT_RATE = 0.4;
    private static final double MIN_DEPLOYED = 2.0;
    /**
     * The visual rope can pay out right up to the length at which the server snaps the hose. Read live
     * (not a constant) so it tracks WildfireConfig's synced hose-length setting.
     */
    private static double maxDeployed() {
        return WildfireConfig.maxHoseLength() + WildfireConfig.hoseSnapSlack();
    }
    /**
     * Fixed backing-array size, at one point per block plus headroom. Sized from the config's hard
     * upper bounds (maxHoseLength <= 512, snapSlack <= 128) rather than the live value, so the arrays
     * never need to grow when the server config is set high.
     */
    private static final int MAX_POINTS = (int) Math.ceil((512.0 + 128.0) / SEGMENT_LENGTH) + 4;
    /** An end anchor jumping further than this in one tick means teleport/desync: re-drape from scratch. */
    private static final double TELEPORT_RESET_DISTANCE = 8.0;

    private final BlockPos pumpPos;
    private final Vec3 pumpAnchor;

    // Current and previous tick positions (verlet state + render interpolation), as flat arrays to
    // avoid churning Vec3 allocations every tick. Only indices [0, count) are live.
    private final double[] x = new double[MAX_POINTS];
    private final double[] y = new double[MAX_POINTS];
    private final double[] z = new double[MAX_POINTS];
    private final double[] px = new double[MAX_POINTS];
    private final double[] py = new double[MAX_POINTS];
    private final double[] pz = new double[MAX_POINTS];
    private final boolean[] grounded = new boolean[MAX_POINTS];
    // Scratch cursor for collision queries; the sim only ever runs on the client tick thread.
    private final BlockPos.MutableBlockPos collisionCursor = new BlockPos.MutableBlockPos();

    /** Number of live points, always >= 2 (pump end + hand end). Tracks deployedLength at ~1/block. */
    private int count;
    /** Total rest length currently paid out of the pump, distributed evenly across the live points. */
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
        return count;
    }

    /** Position of point {@code i} interpolated between the last two ticks, for smooth rendering. */
    public Vec3 getRenderPoint(int i, float partialTick) {
        return new Vec3(
                Mth.lerp(partialTick, px[i], x[i]),
                Mth.lerp(partialTick, py[i], y[i]),
                Mth.lerp(partialTick, pz[i], z[i]));
    }

    /**
     * Advances the simulation by one tick. {@code owner} is the player holding the hose (used as the
     * non-null entity for path raycasts); {@code handAnchor} is where they hold it.
     */
    public void step(Level level, Player owner, Vec3 handAnchor) {
        // Teleports (or NaN from some pathological state) can't be simulated across - re-drape.
        double endJumpSq = distSqTo(count - 1, handAnchor);
        if (!Double.isFinite(endJumpSq) || endJumpSq > TELEPORT_RESET_DISTANCE * TELEPORT_RESET_DISTANCE) {
            drape(handAnchor);
            return;
        }

        updateDeployedLength(level, owner, handAnchor);
        resampleTo(desiredCount(deployedLength));

        int last = count - 1;
        integrate(handAnchor);

        double restLength = deployedLength / last;
        for (int iteration = 0; iteration < CONSTRAINT_ITERATIONS; iteration++) {
            solveDistanceConstraints(restLength);
            for (int i = 1; i < last; i++) {
                collidePoint(level, i);
            }
        }
    }

    private static int desiredCount(double length) {
        return Mth.clamp((int) Math.round(length / SEGMENT_LENGTH) + 1, 2, MAX_POINTS);
    }

    /** Lays the rope out in a straight line pump-to-hand with no velocity; collision untangles it. */
    private void drape(Vec3 handAnchor) {
        deployedLength = Mth.clamp(pumpAnchor.distanceTo(handAnchor) + SLACK, MIN_DEPLOYED, maxDeployed());
        count = desiredCount(deployedLength);
        int last = count - 1;
        for (int i = 0; i <= last; i++) {
            double t = i / (double) last;
            x[i] = px[i] = Mth.lerp(t, pumpAnchor.x, handAnchor.x);
            y[i] = py[i] = Mth.lerp(t, pumpAnchor.y, handAnchor.y);
            z[i] = pz[i] = Mth.lerp(t, pumpAnchor.z, handAnchor.z);
            grounded[i] = false;
        }
    }

    /**
     * Pay-out model. The hose keeps just enough length to reach the player plus a constant droop, so
     * running back and forth can't accumulate slack:
     * <ul>
     *   <li>Reaching further than the current length pays out immediately (walking away).</li>
     *   <li>Otherwise, if the straight run back to the pump is clear, the length is reeled in toward
     *       that straight-line need - the common open-terrain case, and what stops slack piling up.</li>
     *   <li>If the run is blocked, the hose is wrapped around something, so we only trim down to the
     *       rope's actual draped arc; retracting further would drag it through the obstacle.</li>
     * </ul>
     */
    private void updateDeployedLength(Level level, Player owner, Vec3 handAnchor) {
        double direct = pumpAnchor.distanceTo(handAnchor);
        double reach = direct + SLACK;
        if (reach > deployedLength) {
            deployedLength = reach;
        } else if (HosePhysicsHandler.isHosePathClear(level, pumpAnchor, handAnchor, owner)) {
            deployedLength = Math.max(reach, deployedLength - RETRACT_RATE);
        } else {
            deployedLength = Math.max(arcLength(), deployedLength - RETRACT_RATE);
        }
        deployedLength = Mth.clamp(deployedLength, MIN_DEPLOYED, maxDeployed());
    }

    private double arcLength() {
        double total = 0;
        for (int i = 0; i < count - 1; i++) {
            double dx = x[i + 1] - x[i];
            double dy = y[i + 1] - y[i];
            double dz = z[i + 1] - z[i];
            total += Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
        return total;
    }

    /**
     * Re-points the rope to {@code newCount} points by resampling the current chain at even arc-length
     * intervals. Preserves the rope's shape and (approximately) each point's velocity, and keeps the
     * pinned end points exactly, so paying out or reeling in hose changes the node density smoothly
     * instead of popping.
     */
    private void resampleTo(int newCount) {
        newCount = Mth.clamp(newCount, 2, MAX_POINTS);
        if (newCount == count) return;

        int oldLast = count - 1;
        double[] cum = new double[count];
        for (int i = 1; i < count; i++) {
            double dx = x[i] - x[i - 1];
            double dy = y[i] - y[i - 1];
            double dz = z[i] - z[i - 1];
            cum[i] = cum[i - 1] + Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
        double total = cum[oldLast];

        double[] nx = new double[newCount], ny = new double[newCount], nz = new double[newCount];
        double[] npx = new double[newCount], npy = new double[newCount], npz = new double[newCount];

        if (total < 1.0E-6) {
            for (int j = 0; j < newCount; j++) {
                nx[j] = x[0]; ny[j] = y[0]; nz[j] = z[0];
                npx[j] = px[0]; npy[j] = py[0]; npz[j] = pz[0];
            }
        } else {
            int seg = 0;
            for (int j = 0; j < newCount; j++) {
                double target = total * j / (newCount - 1);
                while (seg < oldLast - 1 && cum[seg + 1] < target) seg++;
                double segLen = cum[seg + 1] - cum[seg];
                double f = segLen > 1.0E-9 ? (target - cum[seg]) / segLen : 0.0;
                nx[j] = Mth.lerp(f, x[seg], x[seg + 1]);
                ny[j] = Mth.lerp(f, y[seg], y[seg + 1]);
                nz[j] = Mth.lerp(f, z[seg], z[seg + 1]);
                npx[j] = Mth.lerp(f, px[seg], px[seg + 1]);
                npy[j] = Mth.lerp(f, py[seg], py[seg + 1]);
                npz[j] = Mth.lerp(f, pz[seg], pz[seg + 1]);
            }
        }

        System.arraycopy(nx, 0, x, 0, newCount);
        System.arraycopy(ny, 0, y, 0, newCount);
        System.arraycopy(nz, 0, z, 0, newCount);
        System.arraycopy(npx, 0, px, 0, newCount);
        System.arraycopy(npy, 0, py, 0, newCount);
        System.arraycopy(npz, 0, pz, 0, newCount);
        for (int j = 0; j < newCount; j++) grounded[j] = false;
        count = newCount;
    }

    private void integrate(Vec3 handAnchor) {
        int last = count - 1;
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
        int last = count - 1;
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
