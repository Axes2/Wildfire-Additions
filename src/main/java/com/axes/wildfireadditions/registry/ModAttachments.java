package com.axes.wildfireadditions.registry;

import com.axes.wildfireadditions.WildfireAdditions;
import com.axes.wildfireadditions.aircraft.AircraftTankData;
import com.axes.wildfireadditions.coating.ChunkCoatingData;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.function.Supplier;

/**
 * Registers the data attachment that holds a chunk's retardant "sticky notes".
 *
 * <p>The attachment is serialised (via {@link ChunkCoatingData#CODEC}) so coatings survive a save/load
 * and, critically, so a chunk that unloads with active notes still carries them when it comes back -
 * that's the persistence the {@link com.axes.wildfireadditions.event.RetardantFireHandler} relies on
 * for its cross-reload recovery.
 *
 * <p>We deliberately do <i>not</i> use NeoForge's built-in attachment {@code sync} here; the visual
 * tint is instead pushed to clients through {@link com.axes.wildfireadditions.network.CoatingSync}, so
 * we control exactly what crosses the wire (deltas on spray, a full set on chunk-watch) and can expire
 * notes client-side against the shared game time.
 */
public class ModAttachments {
    public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, WildfireAdditions.MODID);

    public static final Supplier<AttachmentType<ChunkCoatingData>> COATING =
            ATTACHMENT_TYPES.register("coating", () ->
                    AttachmentType.builder(ChunkCoatingData::new)
                            .serialize(ChunkCoatingData.CODEC)
                            .build());

    /**
     * The water/retardant tank fitted to an aircraft. Hung on the aircraft <i>entity</i> and serialised so
     * a fuelled tank survives the aircraft being unloaded or the world being saved. This is the crux of the
     * soft dependency: we store our own state on Immersive Aircraft's entity without touching its code.
     */
    public static final Supplier<AttachmentType<AircraftTankData>> AIRCRAFT_TANK =
            ATTACHMENT_TYPES.register("aircraft_tank", () ->
                    AttachmentType.builder(() -> AircraftTankData.EMPTY)
                            .serialize(AircraftTankData.CODEC)
                            .build());
}
