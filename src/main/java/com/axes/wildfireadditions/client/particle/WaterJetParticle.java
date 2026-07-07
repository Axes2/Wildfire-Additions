package com.axes.wildfireadditions.client.particle;

import com.axes.wildfireadditions.registry.ModParticles;
import com.axes.wildfireadditions.util.WaterStream;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;

/**
 * One droplet of a pressurised water stream. Unlike the old hand-painted arc, every droplet is spawned
 * only at the nozzle with a real initial velocity and then flies its own ballistic trajectory here on
 * the client - the same 16 blocks/s^2 gravity and zero-drag flight the server's {@link WaterStream}
 * trace uses - so the visible water physically travels from the nozzle to wherever it lands, and lands
 * exactly where the server-side simulation says the stream lands.
 *
 * <p>Lifecycle: a droplet leaves the nozzle white (aerated, high-pressure water) and clears to blue a
 * few ticks out, which keeps the near-nozzle stretch of every stream reading as a white pressure core.
 * Downrange it "breaks up": it picks up a little turbulence, swells and fades like a jet dissolving
 * into spray, shedding the odd wisp of mist. On contact with the world it dies on the spot and throws
 * its own splash/mist, so impact effects always happen exactly where water really arrived.
 *
 * <p>Wind: PMWeather's {@code WindEngine} is applied to <em>every</em> vanilla particle each client
 * tick (its {@code ParticleMixin} makes every Particle carry velocity accessors, and its client tick
 * handler adds wind, multiplies velocity by 0.98 friction, and clamps horizontal speed down to roughly
 * wind speed). Left unchecked that drags the droplets off the ballistic arc the server actually douses
 * along, so the water visibly misses the fire it's putting out. To fix the disconnect, a coherent
 * droplet keeps its own authoritative velocity and re-asserts it at the top of every tick, discarding
 * whatever the wind pass did to it - so the stream flies its true arc and lands on the fire. A
 * configurable fraction ({@link #WIND_CAUGHT_CHANCE}, currently 0) can instead be left to the wind to
 * peel off as spray; with it disabled, only the white mist layer is windblown. When PMWeather isn't
 * present the re-assert is simply a harmless no-op.
 */
public class WaterJetParticle extends TextureSheetParticle {

    // Vanilla particle gravity is applied as (0.04 * gravity) blocks/tick^2, so this converts the
    // shared WaterStream constant (blocks/s^2) into an exact per-tick acceleration match.
    private static final double GRAVITY_PER_TICK = WaterStream.GRAVITY / 400.0;

    private static final float WHITE_R = 0.88f, WHITE_G = 0.93f, WHITE_B = 1.0f; // aerated nozzle water
    private static final float DEEP_R = 0.16f, DEEP_G = 0.38f, DEEP_B = 0.88f;   // darkest droplet blue
    private static final float LIGHT_R = 0.45f, LIGHT_G = 0.66f, LIGHT_B = 0.99f; // lightest droplet blue

    private static final int COLOR_BLEND_TICKS = 5; // how long the white->blue clearing takes
    private static final int BREAKUP_AGE = 8; // ticks of coherent flight before the jet starts to dissolve
    private static final float BASE_ALPHA = 0.85f;
    private static final float GROWTH = 0.55f; // fractional quad growth over a full lifetime

    // Fraction of jet droplets that let the wind carry them off as spray instead of holding their arc.
    // Currently 0: the blue jet fully resists wind (reads as a solid, high-pressure stream) and only the
    // white mist layer is left windblown. Bump this above 0 to also have some droplets peel off downwind.
    private static final float WIND_CAUGHT_CHANCE = 0.0f;

    // Per-droplet colour identity: how long it stays white and which blue it clears into.
    private final int whiteTicks;
    private final float blueR, blueG, blueB;

    // When false, the droplet re-asserts its own ballistic velocity each tick so external per-tick
    // velocity changes (PMWeather's wind pass) can't drag it off its arc; velX/Y/Z is that authoritative
    // velocity, carried across ticks independently of the shared xd/yd/zd the wind pass mutates. When
    // true, the droplet is deliberately left to the wind and becomes windblown spray.
    private final boolean windCaught;
    private double velX, velY, velZ;

    protected WaterJetParticle(ClientLevel level, double x, double y, double z,
                               double vx, double vy, double vz, SpriteSet sprites) {
        super(level, x, y, z);
        // The 4-arg super is used deliberately: the motion-taking vanilla constructor randomises the
        // velocity it is given, and these droplets must fly the exact ballistic velocity they were
        // emitted with or the visible stream diverges from the server's trace.
        this.xd = this.velX = vx;
        this.yd = this.velY = vy;
        this.zd = this.velZ = vz;

        this.windCaught = this.random.nextFloat() < WIND_CAUGHT_CHANCE;

        this.setSize(0.05f, 0.05f);
        this.quadSize = 0.10f + this.random.nextFloat() * 0.06f;
        // Wind-caught spray dissipates quickly (it's fine, aerated droplets); coherent water carries far.
        this.lifetime = this.windCaught ? 16 + this.random.nextInt(10) : 40 + this.random.nextInt(13);
        this.hasPhysics = true;

        this.whiteTicks = 3 + this.random.nextInt(4);
        float shade = this.random.nextFloat();
        this.blueR = Mth.lerp(shade, DEEP_R, LIGHT_R);
        this.blueG = Mth.lerp(shade, DEEP_G, LIGHT_G);
        this.blueB = Mth.lerp(shade, DEEP_B, LIGHT_B);
        this.setColor(WHITE_R, WHITE_G, WHITE_B);
        this.alpha = BASE_ALPHA;

        // A random fixed roll per droplet so the overlapping soft sprites don't visibly repeat.
        this.roll = this.oRoll = this.random.nextFloat() * (float) (Math.PI * 2.0);

        this.pickSprite(sprites);
    }

    @Override
    public void tick() {
        this.xo = this.x;
        this.yo = this.y;
        this.zo = this.z;

        if (this.age++ >= this.lifetime) {
            this.remove();
            return;
        }

        // Re-assert the droplet's own velocity, discarding any per-tick change something else made to
        // xd/yd/zd since the last tick - specifically PMWeather's wind pass, which otherwise applies
        // friction and clamps horizontal speed down to wind speed, sweeping the droplet off the arc the
        // server douses along. Wind-caught droplets skip this and keep whatever the wind did to them.
        if (!this.windCaught) {
            this.xd = this.velX;
            this.yd = this.velY;
            this.zd = this.velZ;
        }

        // Pure ballistics: full gravity, no air drag. This is the exact integration the server-side
        // WaterStream trace performs, so the visible droplets land where the douse logic douses.
        this.yd -= GRAVITY_PER_TICK;

        // Downrange breakup: a touch of turbulence once the jet stops being coherent.
        if (this.age > BREAKUP_AGE) {
            this.xd += (this.random.nextDouble() - 0.5) * 0.004;
            this.zd += (this.random.nextDouble() - 0.5) * 0.004;
        }

        // Persist this tick's authoritative velocity (gravity + turbulence folded in) for the next
        // re-assert, so coherent droplets accumulate their own physics but never the wind's.
        if (!this.windCaught) {
            this.velX = this.xd;
            this.velY = this.yd;
            this.velZ = this.zd;
        }

        double ix = this.xd, iy = this.yd, iz = this.zd; // intended motion this tick
        this.move(this.xd, this.yd, this.zd);

        // move() clips against the world; any difference between intended and actual motion means the
        // droplet struck something (ground, wall or ceiling) - splash right there and die.
        if (this.onGround
                || Math.abs((this.x - this.xo) - ix) > 1.0e-4
                || Math.abs((this.y - this.yo) - iy) > 1.0e-4
                || Math.abs((this.z - this.zo) - iz) > 1.0e-4) {
            this.splash(ix, iy, iz);
            return;
        }

        // Landing in water: merge into it quietly with a bubble or two instead of a splash plume.
        if (this.level.getFluidState(BlockPos.containing(this.x, this.y, this.z)).is(FluidTags.WATER)) {
            if (this.random.nextFloat() < 0.3f) {
                this.level.addParticle(ParticleTypes.BUBBLE, this.x, this.y, this.z, 0.0, 0.05, 0.0);
            }
            this.remove();
            return;
        }

        // Colour lifecycle: hold white near the nozzle, then clear to this droplet's blue.
        float blend = Mth.clamp((this.age - this.whiteTicks) / (float) COLOR_BLEND_TICKS, 0.0f, 1.0f);
        this.rCol = Mth.lerp(blend, WHITE_R, this.blueR);
        this.gCol = Mth.lerp(blend, WHITE_G, this.blueG);
        this.bCol = Mth.lerp(blend, WHITE_B, this.blueB);

        // Fade out over the tail of the lifetime so an unobstructed stream dissipates like real spray
        // instead of blinking off at max range.
        float lifeFrac = (float) this.age / (float) this.lifetime;
        if (lifeFrac > 0.7f) {
            this.alpha = BASE_ALPHA * (1.0f - (lifeFrac - 0.7f) / 0.3f);
        }

        // Shed mist: generously while in the white high-pressure core, sparsely during breakup.
        float mistChance = this.age <= this.whiteTicks + 2 ? 0.12f : (this.age > BREAKUP_AGE ? 0.025f : 0.0f);
        if (mistChance > 0.0f && this.random.nextFloat() < mistChance) {
            this.level.addParticle(ModParticles.WATER_MIST.get(), this.x, this.y, this.z,
                    this.xd * 0.25, this.yd * 0.25 + 0.01, this.zd * 0.25);
        }
    }

    // Impact at a real collision point: a rising puff of mist and, sometimes, a vanilla splash droplet
    // burst. Every droplet in the stream does this where it personally lands, which is what builds the
    // continuous splash plume at the point the stream is playing onto.
    private void splash(double vx, double vy, double vz) {
        if (this.random.nextFloat() < 0.45f) {
            this.level.addParticle(ModParticles.WATER_MIST.get(), this.x, this.y, this.z,
                    vx * 0.12, Math.abs(vy) * 0.10 + 0.03, vz * 0.12);
        }
        if (this.random.nextFloat() < 0.25f) {
            this.level.addParticle(ParticleTypes.SPLASH, this.x, this.y, this.z,
                    (this.random.nextDouble() - 0.5) * 0.15, 0.12, (this.random.nextDouble() - 0.5) * 0.15);
        }
        this.remove();
    }

    @Override
    public float getQuadSize(float partialTicks) {
        // Swell slightly over the flight, mimicking the jet loosening into larger, softer spray.
        float lifeFrac = Mth.clamp((this.age + partialTicks) / (float) this.lifetime, 0.0f, 1.0f);
        return this.quadSize * (1.0f + GROWTH * lifeFrac);
    }

    @Override
    public ParticleRenderType getRenderType() {
        return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT;
    }

    public static class Provider implements ParticleProvider<SimpleParticleType> {
        private final SpriteSet sprites;

        public Provider(SpriteSet sprites) {
            this.sprites = sprites;
        }

        @Override
        public Particle createParticle(SimpleParticleType type, ClientLevel level,
                                       double x, double y, double z, double vx, double vy, double vz) {
            return new WaterJetParticle(level, x, y, z, vx, vy, vz, this.sprites);
        }
    }
}
