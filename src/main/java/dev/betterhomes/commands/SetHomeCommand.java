package dev.betterhomes.commands;

import dev.betterhomes.BetterHomes;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.entity.Player;

/** Creates an auto-named ("1", "2", ...) home at the player's location. */
public record SetHomeCommand(BetterHomes plugin) implements BasicCommand {

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        if (!(source.getSender() instanceof Player player)) {
            plugin.messages().send(source.getSender(), "players-only");
            return;
        }
        plugin.homes().createAndNotify(player);
    }

    @Override
    public String permission() {
        return "betterhomes.use";
    }
}
