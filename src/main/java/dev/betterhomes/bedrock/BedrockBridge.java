package dev.betterhomes.bedrock;

import dev.betterhomes.Home;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;

/** Floodgate-free abstraction so the rest of the plugin never touches Floodgate classes. */
public interface BedrockBridge {

    boolean isBedrock(Player player);

    /** Opens the native homes menu. Must be called on the main thread. */
    void openMenu(Player viewer, UUID owner, String ownerName, boolean admin, List<Home> homes);
}
