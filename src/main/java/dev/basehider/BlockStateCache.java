package dev.basehider;

import com.github.retrooper.packetevents.PacketEvents;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import org.bukkit.block.data.BlockData;

import java.util.HashMap;

/**
 * Caches BlockData -> global block state id. PacketEvents converts BlockData through its
 * string form, which is slow; there are only a few hundred distinct states in practice, so
 * a small bounded map removes that cost. Main thread only.
 */
final class BlockStateCache {

    private static final int MAX_ENTRIES = 4096;
    private final HashMap<BlockData, Integer> map = new HashMap<>(512);

    int idOf(BlockData data) {
        Integer cached = map.get(data);
        if (cached != null) {
            return cached;
        }
        if (map.size() >= MAX_ENTRIES) {
            map.clear();
        }
        int id = SpigotConversionUtil.fromBukkitBlockData(data).getGlobalId();
        map.put(data, id);
        return id;
    }

    void clear() {
        map.clear();
    }
}
