package com.axes.wildfireadditions.client.particle;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.util.Mth;

/**
 * A soft, white, expanding wisp of water spray. This is the "high pressure" half of the stream's look:
 * it gets blasted out of the nozzle as an aerated jacket around the jet, shed by droplets as the jet
 * breaks up in flight, and thrown up as a plume wherever droplets land. It decelerates quickly (unlike
 * the ballistic droplets), balloons to roughly three times its spawn size and gently fades and rises
 * like fine spray hanging in the air.
 *
 * <p>Unlike the coherent jet droplets, the mist deliberately does nothing to resist PMWeather's wind
 * pass - it's fine, aerated spray, so being carried off on the wind is exactly the intended read of
 * mist blowing away from a pressurised jet.
 */
public class WaterMistParticle extends TextureSheetParticle {

    private static final float GROWTH = 1.8f; // fractional quad growth over a full lifetime

    private final float baseAlpha;
    private final float spin;

    protected WaterMistParticle(ClientLevel level, double x, double y, double z,
                                double vx, double vy, double vz, SpriteSet sprites) {
        super(level, x, y, z);
        // 4-arg super + direct assignment: the motion-taking vanilla constructor would rescale these.
        this.xd = vx;
        this.yd = vy;
        this.zd = vz;

        this.setSize(0.1f, 0.1f);
        this.quadSize = 0.14f + this.random.nextFloat() * 0.10f;
        this.lifetime = 14 + this.random.nextInt(12);
        this.friction = 0.86f;   // spray sheds its forward speed almost immediately
        this.gravity = -0.1f;    // then hangs and drifts gently upward, like fine mist
        this.hasPhysics = false; // pure atmosphere; skipping collision keeps hundreds of these cheap

        this.baseAlpha = 0.20f + this.random.nextFloat() * 0.12f;
        this.alpha = this.baseAlpha;
        this.setColor(0.94f, 0.97f, 1.0f);

        this.roll = this.oRoll = this.random.nextFloat() * (float) (Math.PI * 2.0);
        this.spin = (this.random.nextFloat() - 0.5f) * 0.08f;

        this.pickSprite(sprites);
    }

    @Override
    public void tick() {
        super.tick();
        this.oRoll = this.roll;
        this.roll += this.spin;

        // Ease the wisp out: opacity falls off quadratically so it lingers, then melts away.
        float lifeFrac = (float) this.age / (float) this.lifetime;
        this.alpha = this.baseAlpha * (1.0f - lifeFrac * lifeFrac);
    }

    @Override
    public float getQuadSize(float partialTicks) {
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
            return new WaterMistParticle(level, x, y, z, vx, vy, vz, this.sprites);
        }
    }
}
