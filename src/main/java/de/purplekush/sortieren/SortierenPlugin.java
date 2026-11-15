package de.purplekush.sortieren;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.block.Container;
import org.bukkit.block.DoubleChest;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Legt beim Öffnen einer Kiste einen Sortier-Knopf in den letzten Slot.
 * Der Gegenstand, der dort eigentlich lag, wird im Knopf selbst gespeichert
 * und beim Schließen zurückgelegt. So geht nichts verloren, auch nicht bei einem Absturz.
 */
public class SortierenPlugin extends JavaPlugin implements Listener {

    private static final LegacyComponentSerializer FARBEN = LegacyComponentSerializer.legacyAmpersand();

    private NamespacedKey knopfKey;
    private NamespacedKey originalKey;
    private Material knopfMaterial;
    private String knopfName;
    private List<String> knopfLore;
    private boolean ton;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        knopfKey = new NamespacedKey(this, "sortier_knopf");
        originalKey = new NamespacedKey(this, "original_item");

        Material m = Material.matchMaterial(getConfig().getString("knopf.material", "HOPPER"));
        knopfMaterial = (m == null || m.isAir() || !m.isItem()) ? Material.HOPPER : m;
        knopfName = getConfig().getString("knopf.name", "&d&lSortieren");
        knopfLore = getConfig().getStringList("knopf.lore");
        ton = getConfig().getBoolean("ton", true);

        getServer().getPluginManager().registerEvents(this, this);
    }

    @Override
    public void onDisable() {
        // Offene Kisten aufräumen, damit kein Knopf zurückbleibt
        for (Player p : getServer().getOnlinePlayers()) {
            Inventory top = p.getOpenInventory().getTopInventory();
            if (istKiste(top)) {
                entferneKnopf(top);
            }
        }
    }

    // ------------------------------------------------------------------
    // Hilfsmethoden
    // ------------------------------------------------------------------

    private boolean istKiste(Inventory inv) {
        if (inv == null) {
            return false;
        }
        InventoryType typ = inv.getType();
        if (typ == InventoryType.ENDER_CHEST) {
            return true;
        }
        if (typ != InventoryType.CHEST && typ != InventoryType.BARREL && typ != InventoryType.SHULKER_BOX) {
            return false;
        }
        // Nur echte Behälter, keine Menüs anderer Plugins
        InventoryHolder holder = inv.getHolder();
        return holder instanceof Container || holder instanceof DoubleChest;
    }

    private boolean istKnopf(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return false;
        }
        return item.getItemMeta().getPersistentDataContainer().has(knopfKey, PersistentDataType.BYTE);
    }

    private ItemStack leseOriginal(ItemStack knopf) {
        if (knopf == null || !knopf.hasItemMeta()) {
            return null;
        }
        byte[] bytes = knopf.getItemMeta().getPersistentDataContainer()
                .get(originalKey, PersistentDataType.BYTE_ARRAY);
        if (bytes == null) {
            return null;
        }
        try {
            return ItemStack.deserializeBytes(bytes);
        } catch (Exception e) {
            getLogger().warning("Gespeicherter Gegenstand im Knopf konnte nicht gelesen werden.");
            return null;
        }
    }

    private ItemStack baueKnopf(ItemStack original) {
        ItemStack item = new ItemStack(knopfMaterial);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(FARBEN.deserialize(knopfName).decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        for (String zeile : knopfLore) {
            lore.add(FARBEN.deserialize(zeile).decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(lore);
        meta.getPersistentDataContainer().set(knopfKey, PersistentDataType.BYTE, (byte) 1);
        if (original != null && !original.getType().isAir()) {
            meta.getPersistentDataContainer().set(originalKey, PersistentDataType.BYTE_ARRAY,
                    original.serializeAsBytes());
        }
        item.setItemMeta(meta);
        return item;
    }

    private void entferneKnopf(Inventory inv) {
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack item = inv.getItem(i);
            if (istKnopf(item)) {
                inv.setItem(i, leseOriginal(item));
            }
        }
    }

    private static boolean andereBetrachter(Inventory inv, Player ich) {
        return inv.getViewers().stream().anyMatch(v -> !v.equals(ich));
    }

    // ------------------------------------------------------------------
    // Öffnen / Schließen
    // ------------------------------------------------------------------

    @EventHandler
    public void onOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) {
            return;
        }
        Inventory inv = event.getInventory();
        if (!istKiste(inv) || inv.getSize() < 9) {
            return;
        }
        if (andereBetrachter(inv, player)) {
            return; // Knopf liegt schon, wenn ein anderer Spieler die Kiste offen hat
        }

        int letzter = inv.getSize() - 1;
        ItemStack vorhanden = inv.getItem(letzter);
        // Falls nach einem Absturz noch ein alter Knopf liegt, dessen Inhalt übernehmen
        ItemStack original = istKnopf(vorhanden) ? leseOriginal(vorhanden) : vorhanden;
        inv.setItem(letzter, baueKnopf(original));
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        Inventory inv = event.getInventory();
        if (!istKiste(inv)) {
            return;
        }
        if (event.getPlayer() instanceof Player player && andereBetrachter(inv, player)) {
            return; // erst aufräumen, wenn der letzte Spieler schließt
        }
        entferneKnopf(inv);
    }

    // ------------------------------------------------------------------
    // Klicks
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        ItemStack aktuell = event.getCurrentItem();
        if (!istKnopf(aktuell)) {
            return;
        }
        // Den Knopf kann man nie herausnehmen, verschieben oder tauschen
        event.setCancelled(true);

        Inventory top = event.getView().getTopInventory();
        if (event.getClickedInventory() != top) {
            return;
        }
        if (event.getClick().isLeftClick()) {
            sortiereKiste(top, player);
            klick(player);
        } else if (event.getClick().isRightClick()) {
            sortiereInventar(player);
            klick(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrag(InventoryDragEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!istKiste(top)) {
            return;
        }
        int letzter = top.getSize() - 1;
        if (istKnopf(top.getItem(letzter)) && event.getRawSlots().contains(letzter)) {
            event.setCancelled(true);
        }
    }

    private void klick(Player player) {
        if (ton) {
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.2f);
        }
    }

    // ------------------------------------------------------------------
    // Schutz des Knopfs (Trichter, Drops)
    // ------------------------------------------------------------------

    @EventHandler(ignoreCancelled = true)
    public void onMove(InventoryMoveItemEvent event) {
        if (istKnopf(event.getItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onSpawn(ItemSpawnEvent event) {
        ItemStack stack = event.getEntity().getItemStack();
        if (istKnopf(stack)) {
            ItemStack original = leseOriginal(stack);
            if (original == null) {
                event.setCancelled(true);
            } else {
                event.getEntity().setItemStack(original);
            }
        }
    }

    // ------------------------------------------------------------------
    // Sortieren
    // ------------------------------------------------------------------

    private void sortiereKiste(Inventory inv, Player player) {
        int letzter = inv.getSize() - 1;
        ItemStack knopf = inv.getItem(letzter);
        ItemStack original = leseOriginal(knopf);

        List<ItemStack> items = new ArrayList<>();
        for (int i = 0; i < letzter; i++) {
            items.add(inv.getItem(i));
        }
        if (original != null) {
            items.add(original);
        }

        List<ItemStack> sortiert = mischeUndSortiere(items);

        for (int i = 0; i < letzter; i++) {
            inv.setItem(i, i < sortiert.size() ? sortiert.get(i) : null);
        }
        // Passt nach dem Zusammenfügen ein Stapel nicht mehr in die Kiste,
        // wird er im Knopf aufbewahrt und beim Schließen in den letzten Slot gelegt.
        ItemStack rest = sortiert.size() > letzter ? sortiert.get(letzter) : null;
        inv.setItem(letzter, baueKnopf(rest));

        for (int i = letzter + 1; i < sortiert.size(); i++) {
            player.getWorld().dropItemNaturally(player.getLocation(), sortiert.get(i));
        }
    }

    private void sortiereInventar(Player player) {
        PlayerInventory pi = player.getInventory();
        // Nur das Hauptinventar (Slots 9-35), Hotbar und Rüstung bleiben unberührt
        List<ItemStack> items = new ArrayList<>();
        for (int i = 9; i <= 35; i++) {
            items.add(pi.getItem(i));
        }
        List<ItemStack> sortiert = mischeUndSortiere(items);
        for (int i = 9; i <= 35; i++) {
            int index = i - 9;
            pi.setItem(i, index < sortiert.size() ? sortiert.get(index) : null);
        }
    }

    /** Fügt gleiche Gegenstände zu vollen Stapeln zusammen und sortiert nach Name. */
    private List<ItemStack> mischeUndSortiere(List<ItemStack> items) {
        List<ItemStack> gemischt = new ArrayList<>();
        for (ItemStack item : items) {
            if (item == null || item.getType().isAir() || istKnopf(item)) {
                continue;
            }
            ItemStack rest = item.clone();
            for (ItemStack vorhanden : gemischt) {
                if (rest.getAmount() <= 0) {
                    break;
                }
                if (vorhanden.isSimilar(rest) && vorhanden.getAmount() < vorhanden.getMaxStackSize()) {
                    int platz = vorhanden.getMaxStackSize() - vorhanden.getAmount();
                    int menge = Math.min(platz, rest.getAmount());
                    vorhanden.setAmount(vorhanden.getAmount() + menge);
                    rest.setAmount(rest.getAmount() - menge);
                }
            }
            if (rest.getAmount() > 0) {
                gemischt.add(rest);
            }
        }
        gemischt.sort(Comparator
                .comparing((ItemStack i) -> i.getType().getKey().getKey())
                .thenComparingInt(i -> -i.getAmount()));
        return gemischt;
    }
}
