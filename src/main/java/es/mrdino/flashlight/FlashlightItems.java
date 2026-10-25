package es.mrdino.flashlight;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.UUID;
import java.util.Objects;

public final class FlashlightItems {
    private final NamespacedKey kindKey, idKey, chargeKey, enabledKey;

    public FlashlightItems(Plugin plugin) {
        kindKey = new NamespacedKey(plugin, "kind");
        idKey = new NamespacedKey(plugin, "id");
        chargeKey = new NamespacedKey(plugin, "charge");
        enabledKey = new NamespacedKey(plugin, "enabled");
    }

    public ItemStack flashlight() {
        ItemStack item = new ItemStack(Material.IRON_HORSE_ARMOR);
        ItemMeta meta = Objects.requireNonNull(Bukkit.getItemFactory().getItemMeta(item.getType()));
        meta.getPersistentDataContainer().set(kindKey, PersistentDataType.STRING, "flashlight");
        meta.setMaxStackSize(1);
        meta.setUnbreakable(true);
        item.setItemMeta(meta);
        update(item, false, 1, "off");
        return item;
    }

    public ItemStack battery(int amount) {
        ItemStack item = new ItemStack(Material.PRISMARINE_SHARD, amount);
        ItemMeta meta = Objects.requireNonNull(Bukkit.getItemFactory().getItemMeta(item.getType()));
        meta.displayName(text("9V Battery", NamedTextColor.GOLD));
        meta.lore(List.of(text("Flashlight replacement battery", NamedTextColor.GRAY)));
        meta.setItemModel(new NamespacedKey("flashlight", "battery"));
        meta.getPersistentDataContainer().set(kindKey, PersistentDataType.STRING, "battery");
        item.setItemMeta(meta);
        return item;
    }

    public boolean isFlashlight(ItemStack item) { return isKind(item, "flashlight"); }
    public boolean isBattery(ItemStack item) { return isKind(item, "battery"); }

    private boolean isKind(ItemStack item, String kind) {
        return item != null && item.hasItemMeta()
                && kind.equals(item.getItemMeta().getPersistentDataContainer().get(kindKey, PersistentDataType.STRING));
    }

    public String identity(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        String id = meta.getPersistentDataContainer().get(idKey, PersistentDataType.STRING);
        if (id == null) {
            id = UUID.randomUUID().toString();
            meta.getPersistentDataContainer().set(idKey, PersistentDataType.STRING, id);
            item.setItemMeta(meta);
        }
        return id;
    }

    public String existingIdentity(ItemStack item) {
        return isFlashlight(item) ? item.getItemMeta().getPersistentDataContainer()
                .get(idKey, PersistentDataType.STRING) : null;
    }

    public boolean enabled(ItemStack item) {
        return isFlashlight(item) && item.getItemMeta().getPersistentDataContainer()
                .getOrDefault(enabledKey, PersistentDataType.BYTE, (byte) 0) == 1;
    }

    public double charge(ItemStack item) {
        double charge = item.getItemMeta().getPersistentDataContainer().getOrDefault(chargeKey, PersistentDataType.DOUBLE, 1.0);
        return Double.isFinite(charge) ? Math.clamp(charge, 0, 1) : 1;
    }

    public void update(ItemStack item, boolean enabled, double charge, String visual) {
        update(item, enabled, charge, visual, enabled && !visual.equals("off") ? 1 : 0, null);
    }

    public void update(ItemStack item, boolean enabled, double charge, String visual,
                       double intensity, FlashlightSettings settings) {
        ItemMeta meta = item.getItemMeta();
        meta.displayName(text("FlashLight", NamedTextColor.WHITE));
        int percent = (int) Math.ceil(charge * 100);
        meta.lore(List.of(text("Battery: " + percent + "%", NamedTextColor.DARK_GRAY),
                text("Right-click to toggle", NamedTextColor.GRAY),
                text("Sneak + right-click to replace battery", NamedTextColor.DARK_GRAY)));
        meta.setItemModel(new NamespacedKey("flashlight", "flashlight_" + visual));
        meta.getPersistentDataContainer().set(enabledKey, PersistentDataType.BYTE, enabled ? (byte) 1 : (byte) 0);
        meta.getPersistentDataContainer().set(chargeKey, PersistentDataType.DOUBLE, charge);
        // Vanilla components carry exact brightness to the client, including partially dim pulses.
        var data = meta.getCustomModelDataComponent();
        data.setFloats(List.of((float) Math.clamp(intensity, 0, 1),
                settings == null ? 20f : (float) settings.range(),
                settings == null ? 28f : (float) settings.coneAngle(),
                settings == null ? .8f : settings.brightness() / 15f,
                settings == null ? .38f : (float) settings.smoothing(),
                settings == null ? .003f : (float) settings.breathing(),
                settings == null ? .009f : (float) settings.walkingSway()));
        meta.setCustomModelDataComponent(data);
        item.setItemMeta(meta);
    }

    public static Component text(String text, NamedTextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }
}
