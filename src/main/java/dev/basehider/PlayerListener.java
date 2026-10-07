package dev.basehider;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

/** Feeds player lifecycle events (join, quit, teleport, world change, respawn) into the manager. */
final class PlayerListener implements Listener {

    private final VisibilityManager vm;

    PlayerListener(VisibilityManager vm) {
        this.vm = vm;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        vm.onJoin(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        vm.onQuit(e.getPlayer());
    }

    /** Covers same-world teleports, portals and world changes. Runs before the new chunks are sent. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        vm.refresh(e.getPlayer(), e.getTo());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent e) {
        vm.refresh(e.getPlayer(), e.getRespawnLocation());
    }
}
