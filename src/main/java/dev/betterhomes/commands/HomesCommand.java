package dev.betterhomes.commands;

import dev.betterhomes.BetterHomes;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static dev.betterhomes.Messages.ph;

/** /homes -> own GUI; /homes <player> -> admin GUI (betterhomes.admin). */
public record HomesCommand(BetterHomes plugin) implements BasicCommand {

    private static final String ADMIN = "betterhomes.admin";

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        if (!(source.getSender() instanceof Player player)) {
            plugin.messages().send(source.getSender(), "players-only");
            return;
        }
        if (args.length == 0) {
            plugin.gui().openSelf(player);
            return;
        }
        if (!player.hasPermission(ADMIN)) {
            plugin.messages().send(player, "no-permission");
            return;
        }

        UUID targetId;
        String targetName;
        Player online = Bukkit.getPlayerExact(args[0]);
        if (online != null) {
            targetId = online.getUniqueId();
            targetName = online.getName();
        } else {
            OfflinePlayer offline = Bukkit.getOfflinePlayerIfCached(args[0]);
            if (offline == null) {
                plugin.messages().send(player, "player-not-found", ph("player", args[0]));
                return;
            }
            targetId = offline.getUniqueId();
            targetName = offline.getName() != null ? offline.getName() : args[0];
        }
        plugin.gui().openAdmin(player, targetId, targetName);
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        if (!source.getSender().hasPermission(ADMIN) || args.length > 1) return List.of();
        String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }

    @Override
    public String permission() {
        return "betterhomes.use";
    }
}
