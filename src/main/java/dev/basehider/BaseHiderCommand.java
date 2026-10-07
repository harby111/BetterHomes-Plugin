package dev.basehider;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;

/** /basehider reload | status */
final class BaseHiderCommand implements CommandExecutor, TabCompleter {

    private final BaseHiderPlugin plugin;
    private final VisibilityManager vm;

    BaseHiderCommand(BaseHiderPlugin plugin, VisibilityManager vm) {
        this.plugin = plugin;
        this.vm = vm;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("basehider.admin")) {
            sender.sendMessage("You do not have permission to use this command.");
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            plugin.reloadHiderConfig();
            sender.sendMessage("BaseHider configuration reloaded.");
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("status")) {
            HiderConfig c = vm.config();
            sender.sendMessage("BaseHider | worlds=" + c.enabledWorlds
                    + " hide=" + c.hideDistanceY + " reveal=" + c.revealDistanceY
                    + " interval=" + c.updateIntervalTicks + "t entities=" + c.hideEntities);
            sender.sendMessage("tracked players=" + vm.trackedPlayers()
                    + " | chunk packets=" + vm.chunkPackets.sum()
                    + " sections blanked=" + vm.sectionsBlanked.sum()
                    + " packets cancelled=" + vm.packetsCancelled.sum());
            sender.sendMessage("section hides=" + vm.sectionsHidden.sum()
                    + " reveals=" + vm.sectionsRevealed.sum()
                    + " | entity hides=" + vm.entitiesHidden.sum()
                    + " reveals=" + vm.entitiesRevealed.sum());
            if (sender instanceof Player p) {
                PlayerState st = vm.state(p.getUniqueId());
                if (st != null) {
                    sender.sendMessage("you: enabled=" + st.enabled + " hiddenCeilingSection="
                            + (st.ceiling == PlayerState.NO_HIDE ? "none" : String.valueOf(st.ceiling))
                            + " chunks=" + st.chunkCount()
                            + " entities=" + st.entities.size()
                            + " hiddenEntities=" + st.hiddenEntities.get());
                }
            }
            return true;
        }
        sender.sendMessage("Usage: /" + label + " <reload|status>");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1 && sender.hasPermission("basehider.admin")) {
            return List.of("reload", "status");
        }
        return List.of();
    }
}
