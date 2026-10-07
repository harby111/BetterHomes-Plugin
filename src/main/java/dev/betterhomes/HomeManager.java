package dev.betterhomes;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachmentInfo;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import static dev.betterhomes.Messages.ph;

/** In-memory home cache. All public methods are main-thread only. */
public final class HomeManager {

    private static final String LIMIT_PREFIX = "betterhomes.limit.";

    public sealed interface CreateResult {
        record Created(Home home, int used, int limit) implements CreateResult {}
        record LimitReached(int limit) implements CreateResult {}
        record NotLoaded() implements CreateResult {}
    }

    private final BetterHomes plugin;
    private final DatabaseManager database;
    private final Map<UUID, List<Home>> cache = new ConcurrentHashMap<>();

    public HomeManager(BetterHomes plugin, DatabaseManager database) {
        this.plugin = plugin;
        this.database = database;
    }

    // ---- lifecycle -------------------------------------------------------------------------

    public void load(UUID id) {
        database.loadHomes(id)
                .thenAcceptAsync(list -> {
                    if (Bukkit.getPlayer(id) == null) return; // quit before the load finished
                    cache.put(id, sorted(list));
                }, plugin.mainThread())
                .exceptionally(ex -> {
                    plugin.getSLF4JLogger().error("Failed to load homes for {}", id, ex);
                    return null;
                });
    }

    public void unload(UUID id) {
        cache.remove(id);
    }

    public boolean isLoaded(UUID id) {
        return cache.containsKey(id);
    }

    // ---- queries ---------------------------------------------------------------------------

    public List<Home> getHomes(UUID id) {
        return cache.getOrDefault(id, List.of());
    }

    public Optional<Home> getHome(UUID id, String name) {
        return getHomes(id).stream().filter(h -> h.name().equals(name)).findFirst();
    }

    /** Cached list for online players, otherwise a fresh DB read. Completes on the main thread. */
    public CompletableFuture<List<Home>> fetchHomes(UUID owner) {
        List<Home> cached = cache.get(owner);
        if (cached != null) return CompletableFuture.completedFuture(cached);
        return database.loadHomes(owner).thenApplyAsync(HomeManager::sorted, plugin.mainThread());
    }

    /** Highest {@code betterhomes.limit.<n>} held, or the configured default. */
    public int getLimit(Player player) {
        int fallback = plugin.getConfig().getInt("homes.default-limit", 2);
        int best = player.getEffectivePermissions().stream()
                .filter(PermissionAttachmentInfo::getValue)
                .map(PermissionAttachmentInfo::getPermission)
                .filter(p -> p.startsWith(LIMIT_PREFIX))
                .mapToInt(p -> parse(p.substring(LIMIT_PREFIX.length())))
                .max().orElse(0);
        return Math.max(fallback, best);
    }

    // ---- mutations -------------------------------------------------------------------------

    public CreateResult createHome(Player player) {
        UUID id = player.getUniqueId();
        List<Home> list = cache.get(id);
        if (list == null) return new CreateResult.NotLoaded();

        int limit = getLimit(player);
        if (list.size() >= limit) return new CreateResult.LimitReached(limit);

        Home home = Home.at(nextName(list), player.getLocation());
        list.add(home);
        list.sort(Home.ORDER);

        database.insertHome(id, home).exceptionally(ex -> {
            plugin.getSLF4JLogger().error("Failed to save home {} for {}", home.name(), id, ex);
            plugin.mainThread().execute(() -> {
                list.remove(home); // roll back the optimistic cache write
                Player p = Bukkit.getPlayer(id);
                if (p != null) plugin.messages().send(p, "database-error");
            });
            return null;
        });
        return new CreateResult.Created(home, list.size(), limit);
    }

    /** Creates a home and sends feedback. Returns true on success. */
    public boolean createAndNotify(Player player) {
        Messages m = plugin.messages();
        return switch (createHome(player)) {
            case CreateResult.Created c -> {
                m.send(player, "home-set", ph("name", c.home().name()),
                        ph("used", c.used()), ph("limit", c.limit()));
                yield true;
            }
            case CreateResult.LimitReached l -> {
                m.send(player, "home-limit", ph("limit", l.limit()));
                yield false;
            }
            case CreateResult.NotLoaded _ -> {
                m.send(player, "data-not-loaded");
                yield false;
            }
        };
    }

    /** Deletes from cache (if cached) and DB. Returns false if a cached owner has no such home. */
    public boolean deleteHome(UUID owner, String name) {
        List<Home> list = cache.get(owner);
        if (list != null && !list.removeIf(h -> h.name().equals(name))) return false;
        database.deleteHome(owner, name).exceptionally(ex -> {
            plugin.getSLF4JLogger().error("Failed to delete home {} for {}", name, owner, ex);
            return null;
        });
        return true;
    }

    // ---- helpers ---------------------------------------------------------------------------

    private static List<Home> sorted(List<Home> source) {
        List<Home> list = new CopyOnWriteArrayList<>(source);
        list.sort(Home.ORDER);
        return list;
    }

    /** Smallest unused positive integer: "1", "2", "3"... */
    private static String nextName(List<Home> list) {
        Set<String> used = list.stream().map(Home::name).collect(Collectors.toSet());
        int i = 1;
        while (used.contains(String.valueOf(i))) i++;
        return String.valueOf(i);
    }

    private static int parse(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException _) {
            return 0;
        }
    }
}
