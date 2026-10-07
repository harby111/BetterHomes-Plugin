package dev.betterhomes.commands;

import dev.betterhomes.BetterHomes;
import dev.betterhomes.Home;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.List;
import java.util.Locale;

import static dev.betterhomes.Messages.ph;

public record DelHomeCommand(BetterHomes plugin) implements BasicCommand {

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        if (!(source.getSender() instanceof Player player)) {
            plugin.messages().send(source.getSender(), "players-only");
            return;
        }
        if (args.length == 0) {
            plugin.messages().send(player, "usage-delhome");
            return;
        }
        if (!plugin.homes().isLoaded(player.getUniqueId())) {
            plugin.messages().send(player, "data-not-loaded");
            return;
        }
        if (plugin.homes().deleteHome(player.getUniqueId(), args[0])) {
            plugin.messages().send(player, "home-deleted", ph("name", args[0]));
        } else {
            plugin.messages().send(player, "home-not-found", ph("name", args[0]));
        }
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        if (!(source.getSender() instanceof Player player) || args.length > 1) return List.of();
        String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        return plugin.homes().getHomes(player.getUniqueId()).stream()
                .map(Home::name).filter(n -> n.startsWith(prefix)).toList();
    }

    @Override
    public String permission() {
        return "betterhomes.use";
    }
}
