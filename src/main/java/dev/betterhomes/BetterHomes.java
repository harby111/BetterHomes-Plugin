package dev.betterhomes;

import dev.betterhomes.commands.BetterHomesCommand;
import dev.betterhomes.commands.DelHomeCommand;
import dev.betterhomes.commands.HomeCommand;
import dev.betterhomes.commands.HomesCommand;
import dev.betterhomes.commands.SetHomeCommand;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.concurrent.Executor;

public final class BetterHomes extends JavaPlugin implements Listener {

    private final Executor mainThread = task -> {
        if (isEnabled()) getServer().getScheduler().runTask(this, task);
    };

    private Messages messages;
    private DatabaseManager database;
    private HomeManager homes;
    private TeleportManager teleports;
    private GUIManager gui;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        messages = new Messages(this);
        database = new DatabaseManager(this);
        try {
            database.init();
        } catch (Exception e) {
            getSLF4JLogger().error("Could not initialise SQLite database, disabling plugin", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        homes = new HomeManager(this, database);
        teleports = new TeleportManager(this);
        gui = new GUIManager(this);

        var pm = getServer().getPluginManager();
        pm.registerEvents(this, this);
        pm.registerEvents(teleports, this);
        pm.registerEvents(gui, this);

        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS,
                event -> registerCommands(event.registrar()));

        // Support /reload-style restarts: load data for players already online.
        for (Player p : getServer().getOnlinePlayers()) homes.load(p.getUniqueId());
    }

    @Override
    public void onDisable() {
        if (teleports != null) teleports.cancelAll();
        if (database != null) database.close();
    }

    private void registerCommands(Commands registrar) {
        registrar.register("home", "Teleport to a home or open the homes menu", List.of(), new HomeCommand(this));
        registrar.register("homes", "Open the homes menu (admins: inspect another player)", List.of(), new HomesCommand(this));
        registrar.register("sethome", "Create a new home at your location", List.of(), new SetHomeCommand(this));
        registrar.register("delhome", "Delete a home", List.of(), new DelHomeCommand(this));
        registrar.register("betterhomes", "BetterHomes administration", List.of(), new BetterHomesCommand(this));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        homes.load(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        homes.unload(e.getPlayer().getUniqueId());
    }

    public Executor mainThread() { return mainThread; }
    public Messages messages() { return messages; }
    public HomeManager homes() { return homes; }
    public TeleportManager teleports() { return teleports; }
    public GUIManager gui() { return gui; }
}
