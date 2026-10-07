package dev.betterhomes;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** Thin MiniMessage layer over config.yml. Always reads live config so /betterhomes reload works. */
public final class Messages {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private final BetterHomes plugin;

    public Messages(BetterHomes plugin) {
        this.plugin = plugin;
    }

    public static TagResolver ph(String key, Object value) {
        return Placeholder.unparsed(key, String.valueOf(value));
    }

    /** Prefixed chat message from {@code messages.<key>}. */
    public Component get(String key, TagResolver... resolvers) {
        FileConfiguration cfg = plugin.getConfig();
        String raw = cfg.getString("messages." + key, "<red>Missing message: " + key);
        return MM.deserialize(cfg.getString("prefix", "") + raw, resolvers);
    }

    public void send(CommandSender target, String key, TagResolver... resolvers) {
        target.sendMessage(get(key, resolvers));
    }

    /** Un-prefixed component from an arbitrary config path. */
    public Component raw(String path, TagResolver... resolvers) {
        return MM.deserialize(plugin.getConfig().getString(path, ""), resolvers);
    }

    /** Component for item names/lore (italics disabled unless explicitly set). */
    public Component item(String path, TagResolver... resolvers) {
        return noItalic(raw(path, resolvers));
    }

    public List<Component> itemLore(String path, TagResolver... resolvers) {
        return plugin.getConfig().getStringList(path).stream()
                .map(line -> noItalic(MM.deserialize(line, resolvers)))
                .toList();
    }

    private static Component noItalic(Component c) {
        return c.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    public String formatDate(long millis) {
        String pattern = plugin.getConfig().getString("date-format", "yyyy-MM-dd HH:mm");
        DateTimeFormatter fmt;
        try {
            fmt = DateTimeFormatter.ofPattern(pattern);
        } catch (IllegalArgumentException _) {
            fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
        }
        return fmt.withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(millis));
    }
}
