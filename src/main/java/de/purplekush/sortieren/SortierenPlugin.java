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
 * Legt beim Öffnen einer Kiste drei Sortier-Knöpfe in die letzten drei Slots.
 * Die Gegenstände, die dort eigentlich lagen, werden in den Knöpfen selbst gespeichert
 * und beim Schließen zurückgelegt. So geht nichts verloren, auch nicht bei einem Absturz.
 */
public class SortierenPlugin extends JavaPlugin implements Listener {

    private static final LegacyComponentSerializer FARBEN = LegacyComponentSerializer.legacyAmpersand();
    private static final int KNOEPFE = 3;

    private enum Modus {
        NAME("name", Material.NAME_TAG),
        KATEGORIE("kategorie", Material.CHEST),
        MENGE("menge", Material.HOPPER);

        final String configKey;
        final Material standard;

        Modus(String configKey, Material standard) {
            this.configKey = configKey;
            this.standard = standard;
        }
    }

    private NamespacedKey knopfKey;
    private NamespacedKey originalKey;
    private boolean ton;

    private final Material[] material = new Material[KNOEPFE];
    private final String[] namen = new String[KNOEPFE];
    private final List<List<String>> lores = new ArrayList<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        knopfKey = new NamespacedKey(this, "sortier_knopf");
        originalKey = new NamespacedKey(this, "original_item");
        ton = getConfig().getBoolean("ton", true);

        for (Modus m : Modus.values()) {
            String pfad = "knoepfe." + m.configKey + ".";
            Material mat = Material.matchMaterial(getConfig().getString(pfad + "material", m.standard.name()));
            material[m.ordinal()] = (mat == null || mat.isAir() || !mat.isItem()) ? m.standard : mat;
            namen[m.ordinal()] = getConfig().getString(pfad + "name", m.name());
            lores.add(getConfig().getStringList(pfad + "lore"));
        }

        getServer().getPluginManager().registerEvents(this, this);
    }

    @Override
    public void onDisable() {
        // Offene Kisten aufräumen, damit kein Knopf zurückbleibt
        for (Player p : getServer().getOnlinePlayers()) {
            Inventory top = p.getOpenInventory().getTopInventory();
            if (istKiste(top)) {
                entferneKnoepfe(top);
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

    private Modus leseModus(ItemStack knopf) {
        if (!istKnopf(knopf)) {
            return null;
        }
        Byte b = knopf.getItemMeta().getPersistentDataContainer().get(knopfKey, PersistentDataType.BYTE);
        if (b == null || b < 0 || b >= Modus.values().length) {
            return null;
        }
        return Modus.values()[b];
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

    private ItemStack baueKnopf(Modus modus, ItemStack original) {
        int i = modus.ordinal();
        ItemStack item = new ItemStack(material[i]);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(FARBEN.deserialize(namen[i]).decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        for (String zeile : lores.get(i)) {
            lore.add(FARBEN.deserialize(zeile).decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(lore);
        meta.getPersistentDataContainer().set(knopfKey, PersistentDataType.BYTE, (byte) i);
        if (original != null && !original.getType().isAir()) {
            meta.getPersistentDataContainer().set(originalKey, PersistentDataType.BYTE_ARRAY,
                    original.serializeAsBytes());
        }
        item.setItemMeta(meta);
        return item;
    }

    private void entferneKnoepfe(Inventory inv) {
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
        if (!istKiste(inv) || inv.getSize() < 27) {
            return;
        }
        if (andereBetrachter(inv, player)) {
            return; // Knöpfe liegen schon, wenn ein anderer Spieler die Kiste offen hat
        }

        int start = inv.getSize() - KNOEPFE;
        for (Modus m : Modus.values()) {
            int slot = start + m.ordinal();
            ItemStack vorhanden = inv.getItem(slot);
            // Falls nach einem Absturz noch ein alter Knopf liegt, dessen Inhalt übernehmen
            ItemStack original = istKnopf(vorhanden) ? leseOriginal(vorhanden) : vorhanden;
            inv.setItem(slot, baueKnopf(m, original));
        }
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
        entferneKnoepfe(inv);
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
        Modus modus = leseModus(aktuell);
        if (modus == null) {
            // Auch ein Tausch per Zifferntaste auf einen Knopf-Slot ist verboten
            return;
        }
        // Den Knopf kann man nie herausnehmen, verschieben oder tauschen
        event.setCancelled(true);

        Inventory top = event.getView().getTopInventory();
        if (event.getClickedInventory() != top) {
            return;
        }
        if (event.getClick().isLeftClick()) {
            sortiereKiste(top, player, modus);
            klick(player);
        } else if (event.getClick().isRightClick()) {
            sortiereInventar(player, modus);
            klick(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrag(InventoryDragEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!istKiste(top)) {
            return;
        }
        for (int slot : event.getRawSlots()) {
            if (slot < top.getSize() && istKnopf(top.getItem(slot))) {
                event.setCancelled(true);
                return;
            }
        }
    }

    private void klick(Player player) {
        if (ton) {
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.2f);
        }
    }

    // ------------------------------------------------------------------
    // Schutz der Knöpfe (Trichter, Drops)
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

    private void sortiereKiste(Inventory inv, Player player, Modus modus) {
        int start = inv.getSize() - KNOEPFE;

        List<ItemStack> items = new ArrayList<>();
        for (int i = 0; i < start; i++) {
            items.add(inv.getItem(i));
        }
        // Was unter den Knöpfen versteckt liegt, kommt mit in die Sortierung
        for (int i = start; i < inv.getSize(); i++) {
            items.add(leseOriginal(inv.getItem(i)));
        }

        List<ItemStack> sortiert = mischeUndSortiere(items, modus);

        for (int i = 0; i < start; i++) {
            inv.setItem(i, i < sortiert.size() ? sortiert.get(i) : null);
        }
        // Passt nach dem Zusammenfügen etwas nicht mehr in die Kiste,
        // wird es in den Knöpfen aufbewahrt und beim Schließen zurückgelegt.
        for (Modus m : Modus.values()) {
            int index = start + m.ordinal();
            ItemStack rest = index < sortiert.size() ? sortiert.get(index) : null;
            inv.setItem(index, baueKnopf(m, rest));
        }
        for (int i = inv.getSize(); i < sortiert.size(); i++) {
            player.getWorld().dropItemNaturally(player.getLocation(), sortiert.get(i));
        }
    }

    private void sortiereInventar(Player player, Modus modus) {
        PlayerInventory pi = player.getInventory();
        // Nur das Hauptinventar (Slots 9-35), Hotbar und Rüstung bleiben unberührt
        List<ItemStack> items = new ArrayList<>();
        for (int i = 9; i <= 35; i++) {
            items.add(pi.getItem(i));
        }
        List<ItemStack> sortiert = mischeUndSortiere(items, modus);
        for (int i = 9; i <= 35; i++) {
            int index = i - 9;
            pi.setItem(i, index < sortiert.size() ? sortiert.get(index) : null);
        }
    }

    /** Fügt gleiche Gegenstände zu vollen Stapeln zusammen und sortiert nach dem gewählten Modus. */
    private List<ItemStack> mischeUndSortiere(List<ItemStack> items, Modus modus) {
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

        Comparator<ItemStack> nachName = Comparator
                .comparing((ItemStack i) -> i.getType().getKey().getKey())
                .thenComparingInt(i -> -i.getAmount());

        switch (modus) {
            case NAME -> gemischt.sort(nachName);
            case KATEGORIE -> gemischt.sort(Comparator
                    .comparingInt((ItemStack i) -> kategorie(i.getType()))
                    .thenComparing(nachName));
            case MENGE -> gemischt.sort(Comparator
                    .comparingInt((ItemStack i) -> -i.getAmount())
                    .thenComparing(nachName));
        }
        return gemischt;
    }

    /** Kleinere Zahl = weiter vorne. */
    private static int kategorie(Material m) {
        String n = m.name();
        if (n.endsWith("_SWORD") || n.endsWith("_PICKAXE") || n.endsWith("_AXE") || n.endsWith("_SHOVEL")
                || n.endsWith("_HOE") || n.equals("BOW") || n.equals("CROSSBOW") || n.equals("TRIDENT")
                || n.equals("MACE") || n.equals("SHIELD") || n.equals("FISHING_ROD") || n.equals("SHEARS")
                || n.equals("FLINT_AND_STEEL") || n.equals("ELYTRA")) {
            return 0; // Werkzeug und Waffen
        }
        if (n.endsWith("_HELMET") || n.endsWith("_CHESTPLATE") || n.endsWith("_LEGGINGS")
                || n.endsWith("_BOOTS") || n.equals("TURTLE_HELMET")) {
            return 1; // Rüstung
        }
        if (m.isEdible()) {
            return 2; // Essen
        }
        if (m.isBlock()) {
            return 3; // Blöcke
        }
        return 4; // alles andere
    }
}
