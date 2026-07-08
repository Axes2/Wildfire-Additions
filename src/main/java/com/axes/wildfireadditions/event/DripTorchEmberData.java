package com.axes.wildfireadditions.event;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Persistent, per-dimension record of the drip torch's tracked embers (block position -> remaining
 * no-fire outward-spread budget), stored with the level's data storage so it survives a save/reload.
 *
 * <p>This is what makes the drip torch's safety guarantee survive a server restart. The ember fire
 * blocks themselves already persist in their chunks, but before this existed the in-memory tracking
 * set was rebuilt <i>empty</i> on startup - so those blocks came back <b>untracked</b>:
 * {@link com.axes.wildfireadditions.mixin.PMWFireBlockMixin} no longer clamped their random-tick
 * intensity, and a capped backburn could then climb into PMWeather's native spread the first time the
 * server bounced, turning a player's firebreak into the very wildfire the tool exists to prevent.
 * Persisting the tracked set means the embers are still recognised as ours after a reload and stay
 * capped until they burn out normally.
 *
 * <p>Only ever touched on the server thread (via {@link DripTorchFireHandler}), so it needs no
 * synchronisation. Mutate {@link #view()} directly and then call {@link #setDirty()} so the change is
 * written on the next save.
 */
public final class DripTorchEmberData extends SavedData {

    /** Data-storage id; becomes {@code data/wildfireadditions_drip_torch_embers.dat} per dimension. */
    public static final String ID = "wildfireadditions_drip_torch_embers";

    public static final SavedData.Factory<DripTorchEmberData> FACTORY =
            new SavedData.Factory<>(DripTorchEmberData::new, DripTorchEmberData::load, null);

    // Insertion-ordered to match the old in-memory set. The creep pass shuffles a copy before it
    // advances the front, so iteration order has no gameplay effect either way.
    private final Map<BlockPos, Integer> embers = new LinkedHashMap<>();

    public DripTorchEmberData() {
    }

    /** The live ember map. Mutate it directly, then call {@link #setDirty()} so the change is saved. */
    public Map<BlockPos, Integer> view() {
        return embers;
    }

    // Stored as two parallel arrays (packed positions + budgets) - compact and trivially portable
    // through NBT, the same shape ChunkCoatingData uses for its notes.
    private static DripTorchEmberData load(CompoundTag tag, HolderLookup.Provider registries) {
        DripTorchEmberData data = new DripTorchEmberData();
        long[] positions = tag.getLongArray("pos");
        int[] budgets = tag.getIntArray("budget");
        for (int i = 0; i < positions.length && i < budgets.length; i++) {
            data.embers.put(BlockPos.of(positions[i]), budgets[i]);
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        long[] positions = new long[embers.size()];
        int[] budgets = new int[embers.size()];
        int i = 0;
        for (Map.Entry<BlockPos, Integer> entry : embers.entrySet()) {
            positions[i] = entry.getKey().asLong();
            budgets[i] = entry.getValue();
            i++;
        }
        tag.putLongArray("pos", positions);
        tag.putIntArray("budget", budgets);
        return tag;
    }
}
