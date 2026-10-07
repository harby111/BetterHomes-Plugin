package dev.betterhomes.commands;

import dev.betterhomes.BetterHomes;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;

import java.util.Collection;
import java.util.List;

/** /betterhomes reload */
public record BetterHomesCommand(BetterHomes plugin) implements BasicCommand {

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            plugin.reloadConfig();
            plugin.messages().send(source.getSender(), "reload-success");
        } else {
            plugin.messages().send(source.getSender(), "usage-betterhomes");
        }
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        return args.length <= 1 ? List.of("reload") : List.of();
    }

    @Override
    public String permission() {
        return "betterhomes.admin";
    }
}
