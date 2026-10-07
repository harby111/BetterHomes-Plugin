package dev.betterhomes;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static dev.betterhomes.Messages.ph;

public final class TeleportManager implements Listener {

    private static final int PERIOD_TICKS = 2;
    private static final TextColor BAR_START = TextColor.color(0xFF5555);
    private static final TextColor BAR_END = TextColor.color(0x55FF55);
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final BetterHomes plugin;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();

    public TeleportManager(BetterHomes plugin) {
        this.plugin = plugin;
    }

    // ---- API -------------------------------------------------------------------------------

    public void start(Player player, Home home) {
        Location dest = home.toLocation();
        if (dest == null) {
            plugin.messages().send(player, "world-missing");
            return;
        }

        Session previous = sessions.remove(player.getUniqueId());
        if (previous != null) previous.cancel();

        int delay = player.hasPermission("betterhomes.bypass.delay")
                ? 0 : plugin.getConfig().getInt("teleport.delay", 5);
        if (delay <= 0) {
            teleport(player, dest, home.name());
            return;
        }

        Session session = new Session(player, dest, home.name(), delay * 20);
        sessions.put(player.getUniqueId(), session);
        session.runTaskTimer(plugin, 0L, PERIOD_TICKS);
        plugin.messages().send(player, "teleport-start", ph("name", home.name()), ph("seconds", delay));
    }

    public void cancelAll() {
        sessions.values().forEach(Session::cancel);
        sessions.clear();
    }

    private void abort(Player player, String messageKey) {
        Session session = sessions.remove(player.getUniqueId());
        if (session == null) return;
        session.cancel();
        Component msg = plugin.messages().get(messageKey);
        player.sendMessage(msg);
        player.sendActionBar(msg);
    }

    // ---- listeners -------------------------------------------------------------------------

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onMove(PlayerMoveEvent e) {
        if (sessions.isEmpty() || !e.hasChangedBlock()) return;
        if (sessions.containsKey(e.getPlayer().getUniqueId())) {
            abort(e.getPlayer(), "teleport-cancelled-move");
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && sessions.containsKey(p.getUniqueId())) {
            abort(p, "teleport-cancelled-damage");
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Session s = sessions.remove(e.getPlayer().getUniqueId());
        if (s != null) s.cancel();
    }

    // ---- internals -------------------------------------------------------------------------

    private void teleport(Player player, Location dest, String homeName) {
        player.teleportAsync(dest, PlayerTeleportEvent.TeleportCause.PLUGIN)
                .thenAcceptAsync(success -> {
                    if (!success || !player.isOnline()) return;
                    World world = dest.getWorld();
                    world.playSound(dest, Sound.ENTITY_ENDERMAN_TELEPORT, SoundCategory.PLAYERS, 0.6f, 1.2f);
                    world.spawnParticle(Particle.REVERSE_PORTAL, dest.clone().add(0, 1, 0),
                            60, 0.4, 0.8, 0.4, 0.05);
                    player.sendActionBar(plugin.messages().raw("actionbar.arrived"));
                    plugin.messages().send(player, "teleport-success", ph("name", homeName));
                }, plugin.mainThread());
    }

    private Component buildBar(double progress, int elapsedTicks) {
        int len = Math.max(5, plugin.getConfig().getInt("teleport.bar-length", 20));
        int filled = (int) Math.round(progress * len);
        boolean pulse = (elapsedTicks / PERIOD_TICKS) % 2 == 0;

        TextComponent.Builder bar = Component.text();
        for (int i = 0; i < len; i++) {
            if (i < filled) {
                // whole bar drifts red -> green as progress rises, with a slight positional gradient
                float t = (float) Math.min(1.0, progress * 0.75 + 0.25 * i / (len - 1));
                bar.append(Component.text("█", TextColor.lerp(t, BAR_START, BAR_END)));
            } else if (i == filled) {
                bar.append(Component.text("▌", pulse ? NamedTextColor.WHITE : NamedTextColor.GRAY));
            } else {
                bar.append(Component.text("░", NamedTextColor.DARK_GRAY));
            }
        }
        return bar.build();
    }

    private final class Session extends BukkitRunnable {
        private final Player player;
        private final Location dest;
        private final String homeName;
        private final int totalTicks;
        private int elapsed;

        Session(Player player, Location dest, String homeName, int totalTicks) {
            this.player = player;
            this.dest = dest;
            this.homeName = homeName;
            this.totalTicks = totalTicks;
        }

        @Override
        public void run() {
            if (!player.isOnline()) {
                sessions.remove(player.getUniqueId(), this);
                cancel();
                return;
            }
            if (elapsed >= totalTicks) {
                sessions.remove(player.getUniqueId(), this);
                cancel();
                teleport(player, dest, homeName);
                return;
            }

            double progress = (double) elapsed / totalTicks;
            String time = String.format(Locale.ROOT, "%.1f", (totalTicks - elapsed) / 20.0);
            player.sendActionBar(MM.deserialize(
                    plugin.getConfig().getString("actionbar.teleporting", "<bar>"),
                    Placeholder.unparsed("home", homeName),
                    Placeholder.component("bar", buildBar(progress, elapsed)),
                    Placeholder.unparsed("time", time)));
            elapsed += PERIOD_TICKS;
        }
    }
}
