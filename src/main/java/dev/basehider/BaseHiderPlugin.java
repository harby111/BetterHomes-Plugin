package dev.basehider;

import com.github.retrooper.packetevents.PacketEvents;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

/** Entry point: wires config, manager, packet listener, Bukkit listeners and the command. */
public final class BaseHiderPlugin extends JavaPlugin {

    private VisibilityManager manager;
    private PacketHandler packetHandler;

    @Override
    public void onEnable() {
        if (!getServer().getPluginManager().isPluginEnabled("packetevents")) {
            getLogger().severe("PacketEvents is not installed/enabled. Install PacketEvents 2.13.0+ (Spigot build).");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        saveDefaultConfig();
        manager = new VisibilityManager(this, HiderConfig.load(getConfig(), getLogger()));

        packetHandler = new PacketHandler(manager);
        PacketEvents.getAPI().getEventManager().registerListener(packetHandler);
        getServer().getPluginManager().registerEvents(new PlayerListener(manager), this);

        PluginCommand cmd = getCommand("basehider");
        if (cmd != null) {
            BaseHiderCommand handler = new BaseHiderCommand(this, manager);
            cmd.setExecutor(handler);
            cmd.setTabCompleter(handler);
        }

        manager.start();
        getLogger().info("BaseHider enabled for worlds " + manager.config().enabledWorlds + ".");
    }

    @Override
    public void onDisable() {
        if (packetHandler != null) {
            try {
                PacketEvents.getAPI().getEventManager().unregisterListener(packetHandler);
            } catch (Throwable ignored) {
                // PacketEvents may already be shutting down.
            }
            packetHandler = null;
        }
        if (manager != null) {
            manager.shutdown();
            manager = null;
        }
    }

    /** Re-reads config.yml. The packet listener stays registered, so nothing is duplicated. */
    void reloadHiderConfig() {
        reloadConfig();
        manager.applyConfig(HiderConfig.load(getConfig(), getLogger()));
    }
}
