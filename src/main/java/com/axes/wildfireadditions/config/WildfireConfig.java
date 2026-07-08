package com.axes.wildfireadditions.config;

import net.neoforged.neoforge.common.ModConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

/**
 * All of Wildfire Additions' player-facing configuration.
 *
 * <p>Three specs, by NeoForge config type:
 * <ul>
 *   <li><b>SERVER</b> ({@code wildfireadditions-server.toml}, per-world, synced to clients): the
 *       gameplay and balance knobs. These are authoritative on a server and reach clients that need
 *       them (e.g. the hose length the client rope obeys) through NeoForge's built-in config sync.</li>
 *   <li><b>CLIENT</b> ({@code wildfireadditions-client.toml}, per-client): purely local look-and-feel
 *       and rendering-cost settings. Changing these never affects other players.</li>
 *   <li><b>STARTUP</b> ({@code wildfireadditions-startup.toml}): the single value that must be known at
 *       item-registration time (the sprayer's charge pool). STARTUP configs are loaded during mod
 *       loading - early enough to be read when items are built - whereas a per-world SERVER config
 *       does not exist yet at that point.</li>
 * </ul>
 *
 * <p><b>Deliberately NOT configurable:</b> the drip torch's ember intensity cap (fixed at 1). It is the
 * load-bearing guarantee that a controlled backburn can never climb into PMWeather's native wildfire
 * spread; exposing it would re-open the runaway-fire bug the tool exists to avoid. See
 * {@link com.axes.wildfireadditions.event.DripTorchFireHandler#CAP}.
 *
 * <p>Read values through the static accessors here rather than touching the spec fields directly, so
 * derived maths (squared distances, min/max clamping) and the registration-time fallback live in one
 * place.
 */
public final class WildfireConfig {

    public static final ModConfigSpec SERVER_SPEC;
    public static final Server SERVER;
    public static final ModConfigSpec CLIENT_SPEC;
    public static final Client CLIENT;
    public static final ModConfigSpec STARTUP_SPEC;
    public static final Startup STARTUP;

    static {
        Pair<Server, ModConfigSpec> server = new ModConfigSpec.Builder().configure(Server::new);
        SERVER = server.getLeft();
        SERVER_SPEC = server.getRight();

        Pair<Client, ModConfigSpec> client = new ModConfigSpec.Builder().configure(Client::new);
        CLIENT = client.getLeft();
        CLIENT_SPEC = client.getRight();

        Pair<Startup, ModConfigSpec> startup = new ModConfigSpec.Builder().configure(Startup::new);
        STARTUP = startup.getLeft();
        STARTUP_SPEC = startup.getRight();
    }

    private WildfireConfig() {
    }

    // --- Accessors (server) ------------------------------------------------------------------------

    public static int coatingLifetimeTicks() {
        return SERVER.coatingLifetimeTicks.get();
    }

    public static int coatingBreakthroughIntensity() {
        return SERVER.coatingBreakthroughIntensity.get();
    }

    public static int sprayerRefillPercentPerMagmaCream() {
        return SERVER.sprayerRefillPercentPerMagmaCream.get();
    }

    public static double maxHoseLength() {
        return SERVER.maxHoseLengthBlocks.get();
    }

    public static double hoseSnapSlack() {
        return SERVER.hoseSnapSlackBlocks.get();
    }

    public static int douseCoolPeriodTicks() {
        return SERVER.douseCoolPeriodTicks.get();
    }

    public static int douseIntensityStep() {
        return SERVER.douseIntensityStep.get();
    }

    public static int sprinklerScanRange() {
        return SERVER.sprinklerScanRangeBlocks.get();
    }

    public static int maxTrackedEmbers() {
        return SERVER.maxTrackedEmbers.get();
    }

    /** Lower bound of a fresh ember's idle-clear budget, never above {@link #dripTorchClearRadiusMax()}. */
    public static int dripTorchClearRadiusMin() {
        return Math.min(SERVER.dripTorchClearRadiusMin.get(), SERVER.dripTorchClearRadiusMax.get());
    }

    /** Upper bound of a fresh ember's idle-clear budget, never below {@link #dripTorchClearRadiusMin()}. */
    public static int dripTorchClearRadiusMax() {
        return Math.max(SERVER.dripTorchClearRadiusMin.get(), SERVER.dripTorchClearRadiusMax.get());
    }

    public static int aircraftWaterKnockdown() {
        return SERVER.aircraftWaterKnockdown.get();
    }

    public static int aircraftRunDurationTicks() {
        return SERVER.aircraftRunDurationTicks.get();
    }

    // --- Accessors (client) ------------------------------------------------------------------------

    public static float useMovementMultiplier() {
        return CLIENT.useMovementMultiplier.get().floatValue();
    }

    /** Squared render cutoff, so the render loop can compare against a squared distance directly. */
    public static double retardantOverlayRenderDistanceSqr() {
        double d = CLIENT.retardantOverlayRenderDistanceBlocks.get();
        return d * d;
    }

    public static int maxRetardantOverlayModels() {
        return CLIENT.maxRetardantOverlayModels.get();
    }

    // --- Accessors (startup) -----------------------------------------------------------------------

    /**
     * The sprayer's full charge pool, baked into the item at registration. Read from the STARTUP config,
     * which is loaded early enough for this; guarded so that in the unlikely event it is read before the
     * config has loaded it falls back to the default rather than throwing.
     */
    public static int sprayerDurability() {
        try {
            return STARTUP.sprayerDurability.get();
        } catch (IllegalStateException notLoadedYet) {
            return 256;
        }
    }

    // --- Specs ------------------------------------------------------------------------------------

    public static final class Server {
        public final ModConfigSpec.IntValue coatingLifetimeTicks;
        public final ModConfigSpec.IntValue coatingBreakthroughIntensity;
        public final ModConfigSpec.IntValue sprayerRefillPercentPerMagmaCream;
        public final ModConfigSpec.DoubleValue maxHoseLengthBlocks;
        public final ModConfigSpec.DoubleValue hoseSnapSlackBlocks;
        public final ModConfigSpec.IntValue douseCoolPeriodTicks;
        public final ModConfigSpec.IntValue douseIntensityStep;
        public final ModConfigSpec.IntValue sprinklerScanRangeBlocks;
        public final ModConfigSpec.IntValue maxTrackedEmbers;
        public final ModConfigSpec.IntValue dripTorchClearRadiusMin;
        public final ModConfigSpec.IntValue dripTorchClearRadiusMax;
        public final ModConfigSpec.IntValue aircraftWaterKnockdown;
        public final ModConfigSpec.IntValue aircraftRunDurationTicks;

        Server(ModConfigSpec.Builder builder) {
            builder.comment("Wildfire Additions - server-side gameplay & balance.",
                    "Applied per world; synced to clients where a value affects what they render.");

            builder.push("coating");
            coatingLifetimeTicks = builder
                    .comment("How long a retardant coating lasts, in ticks (20 ticks = 1 second).",
                            "Default 12000 = 10 real minutes.")
                    .defineInRange("coatingLifetimeTicks", 12000, 20, 1_728_000);
            coatingBreakthroughIntensity = builder
                    .comment("Fire intensity a blaze must reach to burn through a coated block.",
                            "PMWeather fire intensity runs 1-10. Higher = more fireproof: 10 means only the",
                            "very fiercest infernos ever break a coating, 1 means the coating barely helps.",
                            "Default 9 ('practically fireproof, but the worst infernos still win').")
                    .defineInRange("coatingBreakthroughIntensity", 9, 1, 10);
            builder.pop();

            builder.push("sprayer");
            sprayerRefillPercentPerMagmaCream = builder
                    .comment("Percent of the sprayer's full charge restored per Magma Cream when refilling",
                            "it in a crafting grid. (Anvil refills follow vanilla's own repair maths.)",
                            "Default 25.")
                    .defineInRange("sprayerRefillPercentPerMagmaCream", 25, 1, 100);
            builder.pop();

            builder.push("hose");
            maxHoseLengthBlocks = builder
                    .comment("How far the fire hose can be routed from its pump box before it pulls taut and",
                            "slows the player, in blocks. The client-side hose visual obeys the same value.",
                            "Default 50.")
                    .defineInRange("maxHoseLengthBlocks", 50.0, 4.0, 512.0);
            hoseSnapSlackBlocks = builder
                    .comment("Extra length past the max the hose can stretch before it snaps and is lost,",
                            "in blocks. Default 6.")
                    .defineInRange("hoseSnapSlackBlocks", 6.0, 0.0, 128.0);
            builder.pop();

            builder.push("dousing");
            douseCoolPeriodTicks = builder
                    .comment("Ticks between actual fire-intensity reductions from a water stream (hose and",
                            "sprinkler). Lower = fire goes out faster. Default 10 (twice a second).")
                    .defineInRange("douseCoolPeriodTicks", 10, 1, 200);
            douseIntensityStep = builder
                    .comment("How many intensity levels a PMWeather fire loses per cooling step (of 1-10).",
                            "Default 3.")
                    .defineInRange("douseIntensityStep", 3, 1, 10);
            builder.pop();

            builder.push("sprinkler");
            sprinklerScanRangeBlocks = builder
                    .comment("Horizontal radius the fire sprinkler turret hunts for fire within, in blocks.",
                            "Kept inside the nozzle's ballistic reach (~27) by default so every target it",
                            "picks is actually hittable; raising it much past that finds fires it can't reach.",
                            "Default 26.")
                    .defineInRange("sprinklerScanRangeBlocks", 26, 1, 64);
            builder.pop();

            // WARNING attached to the drip_torch section header: the intensity cap is intentionally absent.
            builder.comment("Drip torch backburn tuning.",
                            "NOTE: the ember intensity cap is deliberately NOT configurable - it is fixed at 1",
                            "and is what guarantees a controlled burn can never turn into a wildfire. Only the",
                            "performance ceiling and idle-clearing size are exposed.")
                    .push("drip_torch");
            maxTrackedEmbers = builder
                    .comment("SERVER PERFORMANCE CAP: the most drip-torch embers tracked at once per",
                            "dimension. Each costs a little work per tick; a wide, slow backburn can need",
                            "thousands. Lower this if backburns strain your server. Default 4000.")
                    .defineInRange("maxTrackedEmbers", 4000, 1, 200_000);
            dripTorchClearRadiusMin = builder
                    .comment("When there's no wildfire to burn toward, an ember clears a small diamond of",
                            "undergrowth this many blocks out, at minimum, before burning off. Default 3.")
                    .defineInRange("dripTorchIdleClearRadiusMin", 3, 0, 32);
            dripTorchClearRadiusMax = builder
                    .comment("...and this many blocks out at most (the actual radius is randomised in the",
                            "min..max range per ember). Values below the min are treated as the min.",
                            "Default 5.")
                    .defineInRange("dripTorchIdleClearRadiusMax", 5, 0, 32);
            builder.pop();

            builder.push("aircraft");
            aircraftWaterKnockdown = builder
                    .comment("How many intensity levels (of 1-10) an aircraft water drop knocks off each fire",
                            "it lands on, in one shot. Default 8.")
                    .defineInRange("aircraftWaterKnockdown", 8, 1, 10);
            aircraftRunDurationTicks = builder
                    .comment("How long an aircraft payload run keeps trailing the plane and laying its band,",
                            "in ticks. Default 40 (~2 seconds).")
                    .defineInRange("aircraftRunDurationTicks", 40, 1, 400);
            builder.pop();
        }
    }

    public static final class Client {
        public final ModConfigSpec.DoubleValue useMovementMultiplier;
        public final ModConfigSpec.DoubleValue retardantOverlayRenderDistanceBlocks;
        public final ModConfigSpec.IntValue maxRetardantOverlayModels;

        Client(ModConfigSpec.Builder builder) {
            builder.comment("Wildfire Additions - client-only look & feel. Local to you; never affects",
                    "other players or the server.");

            builder.push("feel");
            useMovementMultiplier = builder
                    .comment("Walk-speed multiplier while holding-to-use a field tool (drip torch, sprayer,",
                            "hose). Vanilla clamps item use to 0.2 (a fifth of walking speed); this replaces",
                            "that for our tools only. 1.0 = full speed, 0.2 = vanilla crawl. Default 0.5.")
                    .defineInRange("useMovementMultiplier", 0.5, 0.05, 1.0);
            builder.pop();

            builder.push("rendering");
            retardantOverlayRenderDistanceBlocks = builder
                    .comment("How far away, in blocks, the red retardant-coating overlay still draws. Lower",
                            "this for more FPS over large sprayed areas. Default 160.")
                    .defineInRange("retardantOverlayRenderDistanceBlocks", 160.0, 16.0, 512.0);
            maxRetardantOverlayModels = builder
                    .comment("Hard cap on how many coated block models are re-rendered for the overlay in a",
                            "single frame, so a huge sprayed area can't stall the game. Default 4096.")
                    .defineInRange("maxRetardantOverlayModels", 4096, 64, 65536);
            builder.pop();
        }
    }

    public static final class Startup {
        public final ModConfigSpec.IntValue sprayerDurability;

        Startup(ModConfigSpec.Builder builder) {
            builder.comment("Wildfire Additions - values needed when items are first registered.");
            builder.push("sprayer");
            sprayerDurability = builder
                    .comment("The retardant sprayer's full charge pool: one point is spent per new block it",
                            "coats, and it never breaks - it just stops until refilled with Magma Cream.",
                            "Baked into the item, so a change takes effect on the next game launch.",
                            "Default 256.")
                    .defineInRange("sprayerDurability", 256, 1, 100_000);
            builder.pop();
        }
    }
}
