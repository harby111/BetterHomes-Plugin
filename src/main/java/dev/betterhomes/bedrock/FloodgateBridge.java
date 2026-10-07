package dev.betterhomes.bedrock;

import dev.betterhomes.BetterHomes;
import dev.betterhomes.Home;
import dev.betterhomes.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.entity.Player;
import org.geysermc.cumulus.form.ModalForm;
import org.geysermc.cumulus.form.SimpleForm;
import org.geysermc.cumulus.util.FormImage;
import org.geysermc.floodgate.api.FloodgateApi;

import java.util.List;
import java.util.UUID;

import static dev.betterhomes.Messages.ph;

/**
 * Bedrock forms: menu -> home details -> teleport / delete (with confirmation).
 * Form callbacks arrive on Floodgate's network thread, so every action hops to the main thread.
 */
public final class FloodgateBridge implements BedrockBridge {

    // Same order as Home#colorIndex(); names match Bedrock's textures/items/bed_<name>.png
    private static final String[] ICONS = {
            "red", "blue", "cyan", "lime", "yellow", "purple", "orange", "pink",
            "magenta", "light_blue", "green", "white", "gray", "silver", "brown", "black"
    };

    /** Everything a form needs to re-open itself. */
    private record Ctx(Player viewer, UUID owner, String ownerName, boolean admin, List<Home> homes) {}

    private final BetterHomes plugin;
    private final FloodgateApi api = FloodgateApi.getInstance();

    public FloodgateBridge(BetterHomes plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean isBedrock(Player player) {
        return api.isFloodgatePlayer(player.getUniqueId());
    }

    @Override
    public void openMenu(Player viewer, UUID owner, String ownerName, boolean admin, List<Home> homes) {
        showMenu(new Ctx(viewer, owner, ownerName, admin, homes));
    }

    // ---- forms -----------------------------------------------------------------------------

    private void showMenu(Ctx c) {
        Messages m = plugin.messages();
        List<Home> snapshot = List.copyOf(c.homes());
        int limit = c.admin() ? 0 : plugin.homes().getLimit(c.viewer());
        boolean canCreate = !c.admin() && snapshot.size() < limit;
        TagResolver[] r = {ph("player", c.ownerName()), ph("used", snapshot.size()), ph("limit", limit)};

        SimpleForm.Builder form = SimpleForm.builder()
                .title(legacy(m.raw(c.admin() ? "bedrock.admin-title" : "bedrock.title", r)))
                .content(legacy(m.raw(c.admin() ? "bedrock.admin-menu-content" : "bedrock.menu-content", r)));

        for (Home h : snapshot) {
            form.button(legacy(m.raw("bedrock.home-button", homePlaceholders(h))),
                    FormImage.Type.PATH, "textures/items/bed_" + ICONS[h.colorIndex()] + ".png");
        }
        if (canCreate) form.button(legacy(m.raw("bedrock.create-button")));

        form.validResultHandler(resp -> sync(() -> onMenu(c, snapshot, canCreate, resp.clickedButtonId())));
        api.sendForm(c.viewer().getUniqueId(), form);
    }

    private void onMenu(Ctx c, List<Home> snapshot, boolean canCreate, int id) {
        if (!allowed(c)) return;
        if (id >= 0 && id < snapshot.size()) {
            showHome(c, snapshot.get(id));
        } else if (canCreate && id == snapshot.size()) {
            plugin.homes().createAndNotify(c.viewer());
            showMenu(c);
        }
    }

    private void showHome(Ctx c, Home home) {
        Messages m = plugin.messages();
        TagResolver[] r = homePlaceholders(home);

        SimpleForm.Builder form = SimpleForm.builder()
                .title(legacy(m.raw("bedrock.home-title", r)))
                .content(legacy(m.raw("bedrock.home-info", r)))
                .button(legacy(m.raw("bedrock.teleport-button")))
                .button(legacy(m.raw("bedrock.delete-button")))
                .button(legacy(m.raw("bedrock.back-button")));

        form.validResultHandler(resp -> sync(() -> onHome(c, home, resp.clickedButtonId())));
        api.sendForm(c.viewer().getUniqueId(), form);
    }

    private void onHome(Ctx c, Home home, int id) {
        if (!allowed(c)) return;
        switch (id) {
            case 0 -> plugin.teleports().start(c.viewer(), home);
            case 1 -> showConfirm(c, home);
            default -> showMenu(c);
        }
    }

    private void showConfirm(Ctx c, Home home) {
        Messages m = plugin.messages();
        TagResolver[] r = homePlaceholders(home);

        ModalForm.Builder form = ModalForm.builder()
                .title(legacy(m.raw("bedrock.confirm-title", r)))
                .content(legacy(m.raw("bedrock.confirm-content", r)))
                .button1(legacy(m.raw("bedrock.confirm-yes")))
                .button2(legacy(m.raw("bedrock.confirm-no")));

        form.validResultHandler(resp -> sync(() -> onConfirm(c, home, resp.clickedFirst())));
        api.sendForm(c.viewer().getUniqueId(), form);
    }

    private void onConfirm(Ctx c, Home home, boolean confirmed) {
        if (!allowed(c)) return;
        if (!confirmed) {
            showHome(c, home);
            return;
        }
        if (plugin.homes().deleteHome(c.owner(), home.name())) {
            c.homes().removeIf(h -> h.name().equals(home.name())); // no-op for the live cache list
            plugin.messages().send(c.viewer(), "home-deleted", ph("name", home.name()));
        }
        showMenu(c);
    }

    // ---- helpers ---------------------------------------------------------------------------

    private boolean allowed(Ctx c) {
        return c.viewer().isOnline() && (!c.admin() || c.viewer().hasPermission("betterhomes.admin"));
    }

    private void sync(Runnable task) {
        plugin.mainThread().execute(task);
    }

    private TagResolver[] homePlaceholders(Home h) {
        return new TagResolver[]{
                ph("name", h.name()), ph("world", h.world()),
                ph("x", (int) Math.floor(h.x())), ph("y", (int) Math.floor(h.y())),
                ph("z", (int) Math.floor(h.z())), ph("date", plugin.messages().formatDate(h.createdAt()))
        };
    }

    /** Bedrock forms use legacy formatting; hex colours are down-sampled to the nearest legacy colour. */
    private static String legacy(Component component) {
        return LegacyComponentSerializer.legacySection().serialize(component);
    }
}
