package dev.basehider;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerMultiBlockChange;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerMultiBlockChange.EncodedBlock;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.TileState;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Owns all per-player state and every main-thread operation:
 * position evaluation, section hide/reveal for chunks the client already has, entity hide/reveal.
 * Packet filtering for NEW packets lives in {@link PacketHandler}.
 */
final class VisibilityManager {

    private final BaseHiderPlugin plugin;
    private volatile HiderConfig cfg;
    private final ConcurrentHashMap<UUID, PlayerState> states = new ConcurrentHashMap<>();
    /** Players with outdated chunks. Main thread only. */
    private final LinkedHashSet<UUID> backlog = new LinkedHashSet<>();
    private final BlockStateCache cache = new BlockStateCache();
    /** Scratch location, main thread only. */
    private final Location tmp = new Location(null, 0, 0, 0);
    private BukkitTask tickTask;
    private boolean drainScheduled;

    final LongAdder chunkPackets = new LongAdder();
    final LongAdder sectionsBlanked = new LongAdder();
    final LongAdder packetsCancelled = new LongAdder();
    final LongAdder sectionsHidden = new LongAdder();
    final LongAdder sectionsRevealed = new LongAdder();
    final LongAdder entitiesHidden = new LongAdder();
    final LongAdder entitiesRevealed = new LongAdder();

    VisibilityManager(BaseHiderPlugin plugin, HiderConfig cfg) {
        this.plugin = plugin;
        this.cfg = cfg;
    }

    HiderConfig config() {
        return cfg;
    }

    PlayerState state(UUID uuid) {
        return uuid == null ? null : states.get(uuid);
    }

    int trackedPlayers() {
        return states.size();
    }

    // ------------------------------------------------------------------ lifecycle

    void start() {
        scheduleTick();
        for (Player p : Bukkit.getOnlinePlayers()) {
            onJoin(p);
        }
    }

    void applyConfig(HiderConfig newCfg) {
        this.cfg = newCfg;
        scheduleTick();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (states.containsKey(p.getUniqueId())) {
                refresh(p, p.getLocation());
            }
        }
    }

    private void scheduleTick() {
        if (tickTask != null) {
            tickTask.cancel();
        }
        int interval = cfg.updateIntervalTicks;
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, interval, interval);
    }

    void shutdown() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        // Plugin disabled while the server keeps running: give the clients their blocks back.
        if (!Bukkit.isStopping()) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                PlayerState st = states.get(p.getUniqueId());
                if (st == null) {
                    continue;
                }
                st.clearCeiling();
                try {
                    processPlayer(p, st, Integer.MAX_VALUE);
                } catch (Throwable t) {
                    debug("reveal on disable failed for " + p.getName() + ": " + t);
                }
            }
        }
        states.clear();
        backlog.clear();
        cache.clear();
    }

    // ------------------------------------------------------------------ player events

    void onJoin(Player p) {
        PlayerState st = new PlayerState(p.getUniqueId());
        states.put(p.getUniqueId(), st);
        refresh(p, p.getLocation());
    }

    void onQuit(Player p) {
        states.remove(p.getUniqueId());
        backlog.remove(p.getUniqueId());
    }

    /** Re-evaluates enabled-world flag and ceiling for a (possibly future) location. */
    void refresh(Player p, Location loc) {
        World w = loc.getWorld();
        PlayerState st = states.get(p.getUniqueId());
        if (w == null || st == null) {
            return;
        }
        HiderConfig c = cfg;
        String worldName = w.getName();
        if (!worldName.equals(st.lastWorld)) {
            // New world: the client throws away all chunks and entities, so start clean.
            st.lastWorld = worldName;
            st.clearWorldData();
            st.clearCeiling();
        }
        boolean enabled = c.enabledWorlds.contains(worldName);
        st.enabled = enabled;
        if (!enabled) {
            if (st.clearCeiling()) {
                enqueue(st.uuid);
            }
            return;
        }
        int y = loc.getBlockY();
        st.playerY = y;
        st.lastEvalY = y;
        if (st.recompute(y, c.hideDistanceY, c.revealDistanceY)) {
            enqueue(st.uuid);
            if (c.debug) {
                debug(p.getName() + " ceiling -> " + st.ceiling + " (y=" + y + ")");
            }
        }
    }

    // ------------------------------------------------------------------ periodic evaluation

    private void tick() {
        HiderConfig c = cfg;
        for (Player p : Bukkit.getOnlinePlayers()) {
            PlayerState st = states.get(p.getUniqueId());
            if (st == null) {
                continue;
            }
            p.getLocation(tmp);
            World w = tmp.getWorld();
            if (w != null && !w.getName().equals(st.lastWorld)) {
                refresh(p, tmp);
            }
            if (!st.enabled) {
                continue;
            }
            int y = tmp.getBlockY();
            if (y != st.lastEvalY) {
                st.lastEvalY = y;
                st.playerY = y;
                if (st.recompute(y, c.hideDistanceY, c.revealDistanceY)) {
                    enqueue(st.uuid);
                    if (c.debug) {
                        debug(p.getName() + " ceiling -> " + st.ceiling + " (y=" + y + ")");
                    }
                }
            }
            if (c.hideEntities && !st.entities.isEmpty()) {
                updateEntities(p, st, y, c);
            }
        }
    }

    // ------------------------------------------------------------------ entities

    private void updateEntities(Player p, PlayerState st, int py, HiderConfig c) {
        User user = userOf(p, st);
        if (user == null) {
            return;
        }
        double hideLine = py - c.hideDistanceY;
        double revealLine = py - c.revealDistanceY;
        for (TrackedEntity te : st.entities.values()) {
            Entity e = Bukkit.getEntity(te.uuid);
            if (e == null) {
                continue;
            }
            double ey = e.getLocation(tmp).getY();
            if (!te.hidden) {
                if (ey <= hideLine) {
                    st.setHidden(te, true);
                    user.sendPacketSilently(new WrapperPlayServerDestroyEntities(te.id));
                    entitiesHidden.increment();
                }
            } else if (ey > revealLine) {
                st.setHidden(te, false);
                // The server thinks the client already has this entity. Hide + show makes the
                // server's own tracker send a complete, correct spawn sequence again.
                p.hideEntity(plugin, e);
                p.showEntity(plugin, e);
                entitiesRevealed.increment();
            }
        }
    }

    // ------------------------------------------------------------------ chunk transitions

    private void enqueue(UUID id) {
        backlog.add(id);
        scheduleDrain();
    }

    private void scheduleDrain() {
        if (drainScheduled) {
            return;
        }
        drainScheduled = true;
        Bukkit.getScheduler().runTask(plugin, this::drain);
    }

    private void drain() {
        drainScheduled = false;
        int budget = cfg.transitionBudget;
        var it = backlog.iterator();
        while (it.hasNext()) {
            UUID id = it.next();
            Player p = Bukkit.getPlayer(id);
            PlayerState st = states.get(id);
            if (p == null || st == null) {
                it.remove();
                continue;
            }
            boolean more;
            try {
                more = processPlayer(p, st, budget);
            } catch (Throwable t) {
                more = false;
                debug("transition failed for " + p.getName() + ": " + t);
            }
            if (!more) {
                it.remove();
            }
        }
        if (!backlog.isEmpty()) {
            scheduleDrain();
        }
    }

    /**
     * Brings the client's already-sent chunks in line with the player's current ceiling,
     * nearest chunks first, spending at most {@code budget} cost units.
     *
     * @return true if work remains
     */
    private boolean processPlayer(Player p, PlayerState st, int budget) {
        User user = userOf(p, st);
        if (user == null) {
            return false;
        }
        World w = p.getWorld();
        p.getLocation(tmp);
        ArrayList<long[]> dirty = st.dirtyChunks(tmp.getBlockX() >> 4, tmp.getBlockZ() >> 4);
        if (dirty.isEmpty()) {
            return false;
        }
        dirty.sort(Comparator.comparingLong(a -> a[1]));

        int minSec = user.getMinWorldHeight() >> 4;
        int maxSec = minSec + (user.getTotalWorldHeight() >> 4) - 1;
        int spent = 0;
        for (long[] d : dirty) {
            if (spent >= budget) {
                return true;
            }
            long key = d[0];
            int[] fromTo = st.claim(key);
            if (fromTo == null) {
                continue;
            }
            int cx = (int) (key >> 32);
            int cz = (int) key;
            int from = fromTo[0];
            int to = fromTo[1];
            if (to > from) {
                int lo = Math.max(from + 1, minSec);
                int hi = Math.min(to, maxSec);
                for (int s = lo; s <= hi; s++) {
                    sendHide(user, cx, s, cz);
                    sectionsHidden.increment();
                    spent++;
                }
            } else {
                int lo = Math.max(to + 1, minSec);
                int hi = Math.min(from, maxSec);
                if (lo <= hi) {
                    spent += revealRange(p, user, w, cx, cz, lo, hi);
                }
            }
            st.markApplied(key, to);
        }
        return st.hasDirtyChunks();
    }

    /** Overwrites a whole section with AIR on one client. No server data needed. */
    private void sendHide(User user, int cx, int sy, int cz) {
        int bx = cx << 4;
        int by = sy << 4;
        int bz = cz << 4;
        EncodedBlock[] blocks = new EncodedBlock[4096];
        int i = 0;
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    blocks[i++] = new EncodedBlock(0, bx + x, by + y, bz + z);
                }
            }
        }
        user.sendPacketSilently(new WrapperPlayServerMultiBlockChange(new Vector3i(cx, sy, cz), Boolean.FALSE, blocks));
    }

    /** Sends the real contents of sections lo..hi (and their block entities) to one client. */
    private int revealRange(Player p, User user, World w, int cx, int cz, int lo, int hi) {
        int sections = hi - lo + 1;
        if (!w.isChunkLoaded(cx, cz)) {
            return sections; // cannot read it; nothing safe to send
        }
        Chunk chunk = w.getChunkAt(cx, cz);
        for (int s = lo; s <= hi; s++) {
            sendReveal(user, chunk, cx, cz, s);
            sectionsRevealed.increment();
        }
        final int yMin = lo << 4;
        final int yMax = (hi << 4) + 15;
        for (BlockState bs : chunk.getTileEntities(b -> b.getY() >= yMin && b.getY() <= yMax, false)) {
            if (bs instanceof TileState ts) {
                p.sendBlockUpdate(bs.getLocation(), ts);
            }
        }
        return sections * 4;
    }

    private void sendReveal(User user, Chunk chunk, int cx, int cz, int sy) {
        int bx = cx << 4;
        int by = sy << 4;
        int bz = cz << 4;
        ArrayList<EncodedBlock> list = new ArrayList<>(1024);
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    Block b = chunk.getBlock(x, by + y, z);
                    if (b.isEmpty()) {
                        continue; // the client already shows AIR here
                    }
                    list.add(new EncodedBlock(cache.idOf(b.getBlockData()), bx + x, by + y, bz + z));
                }
            }
        }
        if (list.isEmpty()) {
            return;
        }
        user.sendPacketSilently(new WrapperPlayServerMultiBlockChange(
                new Vector3i(cx, sy, cz), Boolean.FALSE, list.toArray(new EncodedBlock[0])));
    }

    // ------------------------------------------------------------------ helpers

    private User userOf(Player p, PlayerState st) {
        User u = st.user;
        if (u == null) {
            u = PacketEvents.getAPI().getPlayerManager().getUser(p);
            st.user = u;
        }
        return u;
    }

    void debug(String msg) {
        if (cfg.debug) {
            plugin.getLogger().info("[debug] " + msg);
        }
    }
}
