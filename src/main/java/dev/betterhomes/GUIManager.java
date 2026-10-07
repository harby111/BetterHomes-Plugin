package dev.betterhomes;

import dev.betterhomes.bedrock.BedrockBridge;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.UUID;

import static dev.betterhomes.Messages.ph;

public final class GUIManager implements Listener {

    private static final int PAGE_SIZE = 45;
    private static final int SLOT_PREV = 45, SLOT_PAGE = 48, SLOT_ACTION = 49, SLOT_NEXT = 53;

    private static final Material[] BEDS = {
            Material.RED_BED, Material.BLUE_BED, Material.CYAN_BED, Material.LIME_BED,
            Material.YELLOW_BED, Material.PURPLE_BED, Material.ORANGE_BED, Material.PINK_BED,
            Material.MAGENTA_BED, Material.LIGHT_BLUE_BED, Material.GREEN_BED, Material.WHITE_BED,
            Material.GRAY_BED, Material.LIGHT_GRAY_BED, Material.BROWN_BED, Material.BLACK_BED
    };

    /** Inventory holder carrying per-viewer GUI state. */
    static final class HomesGui implements InventoryHolder {
        final UUID owner;
        final String ownerName;
        final boolean admin;
        final List<Home> homes; // live cache list for online owners, snapshot for offline ones
        int page;
        Inventory inventory;

        HomesGui(UUID owner, String ownerName, boolean admin, List<Home> homes) {
            this.owner = owner;
            this.ownerName = ownerName;
            this.admin = admin;
            this.homes = homes;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final BetterHomes plugin;
    private final NamespacedKey homeKey;

    public GUIManager(BetterHomes plugin) {
        this.plugin = plugin;
        this.homeKey = new NamespacedKey(plugin, "home_name");
    }

    // ---- opening ---------------------------------------------------------------------------

    public void openSelf(Player player) {
        UUID id = player.getUniqueId();
        if (!plugin.homes().isLoaded(id)) {
            plugin.messages().send(player, "data-not-loaded");
            return;
        }
        List<Home> homes = plugin.homes().getHomes(id);
        if (openBedrock(player, id, player.getName(), false, homes)) return;
        open(player, new HomesGui(id, player.getName(), false, homes));
    }

    public void openAdmin(Player admin, UUID target, String targetName) {
        plugin.homes().fetchHomes(target)
                .thenAccept(list -> {
                    if (!admin.isOnline()) return;
                    if (openBedrock(admin, target, targetName, true, list)) return;
                    open(admin, new HomesGui(target, targetName, true, list));
                })
                .exceptionally(ex -> {
                    plugin.getSLF4JLogger().error("Failed to open admin GUI for {}", target, ex);
                    plugin.mainThread().execute(() -> plugin.messages().send(admin, "database-error"));
                    return null;
                });
    }

    /** Bedrock (Geyser/Floodgate) players get native forms; returns true if one was opened. */
    private boolean openBedrock(Player viewer, UUID owner, String ownerName, boolean admin, List<Home> homes) {
        BedrockBridge bridge = plugin.bedrock();
        if (bridge == null || !plugin.getConfig().getBoolean("bedrock.use-forms", true)
                || !bridge.isBedrock(viewer)) {
            return false;
        }
        bridge.openMenu(viewer, owner, ownerName, admin, homes);
        return true;
    }

    private void open(Player viewer, HomesGui gui) {
        var title = gui.admin
                ? plugin.messages().raw("gui.admin-title", ph("player", gui.ownerName))
                : plugin.messages().raw("gui.title");
        gui.inventory = Bukkit.createInventory(gui, 54, title);
        render(viewer, gui);
        viewer.openInventory(gui.inventory);
    }

    // ---- rendering -------------------------------------------------------------------------

    private void render(Player viewer, HomesGui gui) {
        Messages m = plugin.messages();
        Inventory inv = gui.inventory;
        inv.clear();

        List<Home> homes = List.copyOf(gui.homes);
        int pages = Math.max(1, (homes.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        gui.page = Math.clamp(gui.page, 0, pages - 1);
        int start = gui.page * PAGE_SIZE;

        for (int i = 0; i < PAGE_SIZE && start + i < homes.size(); i++) {
            inv.setItem(i, homeItem(homes.get(start + i)));
        }

        ItemStack filler = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        filler.editMeta(meta -> meta.setHideTooltip(true));
        for (int slot = PAGE_SIZE; slot < 54; slot++) inv.setItem(slot, filler);

        if (gui.page > 0) inv.setItem(SLOT_PREV, button(Material.ARROW, "gui.prev-name", List.of()));
        if (gui.page < pages - 1) inv.setItem(SLOT_NEXT, button(Material.ARROW, "gui.next-name", List.of()));

        ItemStack pageItem = new ItemStack(Material.PAPER);
        pageItem.editMeta(meta -> meta.displayName(
                m.item("gui.page-name", ph("page", gui.page + 1), ph("pages", pages))));
        inv.setItem(SLOT_PAGE, pageItem);

        ItemStack action;
        if (gui.admin) {
            action = new ItemStack(Material.COMPASS);
            action.editMeta(meta -> {
                meta.displayName(m.item("gui.admin-info-name"));
                meta.lore(m.itemLore("gui.admin-info-lore",
                        ph("player", gui.ownerName), ph("used", homes.size())));
            });
        } else {
            int limit = plugin.homes().getLimit(viewer);
            boolean canCreate = homes.size() < limit;
            action = new ItemStack(canCreate ? Material.EMERALD : Material.BARRIER);
            action.editMeta(meta -> {
                meta.displayName(m.item(canCreate ? "gui.create-name" : "gui.limit-name"));
                meta.lore(m.itemLore(canCreate ? "gui.create-lore" : "gui.limit-lore",
                        ph("used", homes.size()), ph("limit", limit)));
            });
        }
        inv.setItem(SLOT_ACTION, action);
    }

    private ItemStack button(Material material, String namePath, List<net.kyori.adventure.text.Component> lore) {
        ItemStack item = new ItemStack(material);
        item.editMeta(meta -> {
            meta.displayName(plugin.messages().item(namePath));
            if (!lore.isEmpty()) meta.lore(lore);
        });
        return item;
    }

    private ItemStack homeItem(Home home) {
        ItemStack item = new ItemStack(bedFor(home));
        item.editMeta(meta -> {
            meta.displayName(plugin.messages().item("gui.home-name", ph("name", home.name())));
            meta.lore(plugin.messages().itemLore("gui.home-lore",
                    ph("name", home.name()),
                    ph("world", home.world()),
                    ph("x", (int) Math.floor(home.x())),
                    ph("y", (int) Math.floor(home.y())),
                    ph("z", (int) Math.floor(home.z())),
                    ph("date", plugin.messages().formatDate(home.createdAt()))));
            meta.getPersistentDataContainer().set(homeKey, PersistentDataType.STRING, home.name());
        });
        return item;
    }

    private static Material bedFor(Home home) {
        return BEDS[home.colorIndex()];
    }

    // ---- listeners -------------------------------------------------------------------------

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder(false) instanceof HomesGui gui)) return;

        e.setCancelled(true); // blocks every item movement: clicks, shift-click, hotbar swaps, double-click
        if (!(e.getWhoClicked() instanceof Player player)) return;
        if (e.getClickedInventory() != top) return;

        ItemStack clicked = e.getCurrentItem();
        if (clicked == null || clicked.getType().isAir()) return;

        if (gui.admin && !player.hasPermission("betterhomes.admin")) {
            player.closeInventory();
            return;
        }

        int slot = e.getRawSlot();
        switch (slot) {
            case SLOT_PREV -> {
                if (clicked.getType() == Material.ARROW) {
                    gui.page--;
                    render(player, gui);
                }
            }
            case SLOT_NEXT -> {
                if (clicked.getType() == Material.ARROW) {
                    gui.page++;
                    render(player, gui);
                }
            }
            case SLOT_ACTION -> {
                if (!gui.admin && clicked.getType() == Material.EMERALD) {
                    plugin.homes().createAndNotify(player);
                    render(player, gui);
                }
            }
            default -> {
                if (slot < PAGE_SIZE) handleHomeClick(player, gui, e, clicked);
            }
        }
    }

    private void handleHomeClick(Player player, HomesGui gui, InventoryClickEvent e, ItemStack clicked) {
        String name = clicked.getItemMeta().getPersistentDataContainer().get(homeKey, PersistentDataType.STRING);
        if (name == null) return;

        Home home = gui.homes.stream().filter(h -> h.name().equals(name)).findFirst().orElse(null);
        if (home == null) { // stale item
            render(player, gui);
            return;
        }

        if (e.getClick().isLeftClick()) {
            player.closeInventory();
            plugin.teleports().start(player, home);
        } else if (e.getClick().isRightClick()) {
            if (plugin.homes().deleteHome(gui.owner, name)) {
                gui.homes.removeIf(h -> h.name().equals(name)); // no-op when gui.homes is the live cache
                plugin.messages().send(player, "home-deleted", ph("name", name));
            }
            render(player, gui);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder(false) instanceof HomesGui) {
            e.setCancelled(true);
        }
    }
}
