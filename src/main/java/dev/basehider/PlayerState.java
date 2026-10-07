package dev.basehider;

import com.github.retrooper.packetevents.protocol.player.User;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Everything BaseHider remembers about one player. Deliberately tiny:
 * one ceiling integer, a map chunk -> ceiling the client currently shows, and the
 * entities the client was told about.
 *
 * <p>The whole visibility rule collapses into one number, {@link #ceiling}: every section
 * with index &lt;= ceiling is hidden for this player. It is written on the main thread and read
 * on netty threads, so changes and chunk registration are synchronised on this object.
 */
final class PlayerState {

    /** "Nothing hidden". Inside the Integer cache range (-128..127), so no boxing garbage. */
    static final int NO_HIDE = -128;

    final UUID uuid;

    volatile boolean enabled;
    /** Player block Y; NaN until first known. Read by netty threads for entity decisions. */
    volatile double playerY = Double.NaN;
    /** Highest hidden section index (absolute section Y), or NO_HIDE. */
    volatile int ceiling = NO_HIDE;
    volatile User user;

    /** chunkKey -> ceiling that the client's copy of that chunk currently reflects. Guarded by this. */
    private final HashMap<Long, Integer> chunks = new HashMap<>(512);
    final ConcurrentHashMap<Integer, TrackedEntity> entities = new ConcurrentHashMap<>();
    final AtomicInteger hiddenEntities = new AtomicInteger();

    /** Main thread only: last Y that was evaluated. */
    int lastEvalY = Integer.MIN_VALUE;
    /** Main thread only: world the player was last evaluated in. */
    String lastWorld;

    PlayerState(UUID uuid) {
        this.uuid = uuid;
    }

    static long chunkKey(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    /**
     * Applies the hysteresis rule for a player standing at block Y {@code py}.
     * A section s (blocks s*16 .. s*16+15) becomes hidden when its top block is at least
     * {@code hide} below py, and stays hidden until its top block is less than {@code reveal} below py.
     *
     * @return true if the ceiling changed
     */
    synchronized boolean recompute(int py, int hide, int reveal) {
        int eligible = Math.floorDiv(py - hide - 15, 16);
        int keep = Math.floorDiv(py - reveal - 15, 16);
        int current = ceiling;
        int next = current;
        if (current > keep) {
            next = keep;
        } else if (eligible > current) {
            next = eligible;
        }
        if (next < NO_HIDE) {
            next = NO_HIDE;
        }
        if (next == current) {
            return false;
        }
        ceiling = next;
        return true;
    }

    synchronized boolean clearCeiling() {
        if (ceiling == NO_HIDE) {
            return false;
        }
        ceiling = NO_HIDE;
        return true;
    }

    /** Called by the chunk packet handler: records the ceiling this chunk is sent with. */
    synchronized int registerChunk(long key) {
        int c = ceiling;
        chunks.put(key, c);
        return c;
    }

    synchronized void unloadChunk(long key) {
        chunks.remove(key);
    }

    synchronized int chunkCount() {
        return chunks.size();
    }

    /** Dimension change / respawn: the client discarded everything. */
    synchronized void clearWorldData() {
        chunks.clear();
        entities.clear();
        hiddenEntities.set(0);
    }

    /** Returns {key, squaredDistanceToPlayerChunk} for every chunk whose client state is outdated. */
    synchronized ArrayList<long[]> dirtyChunks(int pcx, int pcz) {
        ArrayList<long[]> out = new ArrayList<>();
        int c = ceiling;
        for (var e : chunks.entrySet()) {
            if (e.getValue() != c) {
                long key = e.getKey();
                long dx = (int) (key >> 32) - pcx;
                long dz = (int) key - pcz;
                out.add(new long[]{key, dx * dx + dz * dz});
            }
        }
        return out;
    }

    /**
     * Claims a chunk for updating.
     *
     * @return {from, to} or null if the chunk is gone / already current
     */
    synchronized int[] claim(long key) {
        Integer applied = chunks.get(key);
        if (applied == null || applied == ceiling) {
            return null;
        }
        return new int[]{applied, ceiling};
    }

    synchronized void markApplied(long key, int value) {
        if (chunks.containsKey(key)) {
            chunks.put(key, value);
        }
    }

    /**
     * Flips the hidden flag of a tracked entity. The counter is raised BEFORE the flag is set and
     * lowered AFTER it is cleared, so it is never below the real number of hidden entities
     * (netty threads use it to skip decoding when it is zero).
     */
    void setHidden(TrackedEntity te, boolean hidden) {
        synchronized (te) {
            if (te.hidden == hidden) {
                return;
            }
            if (hidden) {
                hiddenEntities.incrementAndGet();
                te.hidden = true;
            } else {
                te.hidden = false;
                hiddenEntities.decrementAndGet();
            }
        }
    }

    synchronized boolean hasDirtyChunks() {
        int c = ceiling;
        for (Integer v : chunks.values()) {
            if (v != c) {
                return true;
            }
        }
        return false;
    }
}
