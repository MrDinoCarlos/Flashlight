package es.mrdino.flashlight;

import es.mrdino.flashlight.light.LightEngine;
import es.mrdino.flashlight.light.BeamRenderer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.List;

public final class FlashlightService {
    public record Held(ItemStack item, EquipmentSlot hand) { }

    private static final class Session {
        final String itemId;
        final FlashlightState state;
        String visual = "";
        double intensity = -1;

        Session(String itemId, FlashlightState state) {
            this.itemId = itemId;
            this.state = state;
        }
    }

    private final FlashlightPlugin plugin;
    private final FlashlightItems items;
    private final LightEngine lights;
    private final BeamRenderer beams;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Map<UUID, Long> lastToggle = new HashMap<>();
    private final Map<UUID, PowerOverride> powerOverrides = new HashMap<>();
    private record PowerOverride(long until, String itemId, boolean restoreEnabled, boolean forcedEnabled) { }
    private final BukkitTask task;
    private long tick;

    public FlashlightService(FlashlightPlugin plugin, FlashlightItems items, LightEngine lights) {
        this.plugin = plugin;
        this.items = items;
        this.lights = lights;
        beams = new BeamRenderer(plugin);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1, 1);
    }

    public Held held(Player player) {
        ItemStack main = player.getInventory().getItemInMainHand();
        ItemStack off = player.getInventory().getItemInOffHand();
        if (items.enabled(main)) return new Held(main, EquipmentSlot.HAND);
        if (items.enabled(off)) return new Held(off, EquipmentSlot.OFF_HAND);
        if (items.isFlashlight(main)) return new Held(main, EquipmentSlot.HAND);
        if (items.isFlashlight(off)) return new Held(off, EquipmentSlot.OFF_HAND);
        return null;
    }

    public void toggle(Player player, EquipmentSlot hand) {
        if (!player.hasPermission("flashlight.use")) return;
        if (powerOverrides.containsKey(player.getUniqueId())) return;
        if (tick - lastToggle.getOrDefault(player.getUniqueId(), -100L) < 5) return;
        lastToggle.put(player.getUniqueId(), tick);
        ItemStack item = hand == EquipmentSlot.HAND ? player.getInventory().getItemInMainHand()
                : player.getInventory().getItemInOffHand();
        if (!items.isFlashlight(item)) return;
        boolean turnOn = !items.enabled(item);
        stop(player);
        // Exactly one active flashlight per player, including the offhand.
        for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
            ItemStack other = player.getInventory().getItem(slot);
            if (items.enabled(other)) {
                items.update(other, false, items.charge(other), "off");
                player.getInventory().setItem(slot, other);
            }
        }
        item = hand == EquipmentSlot.HAND ? player.getInventory().getItemInMainHand()
                : player.getInventory().getItemInOffHand();
        if (turnOn && plugin.settings().batteryEnabled() && items.charge(item) <= 1.0e-9) {
            sound(player, Sound.BLOCK_DISPENSER_FAIL, .45f, .8f);
            return;
        }
        items.identity(item);
        items.update(item, turnOn, items.charge(item), turnOn ? "on" : "off");
        storeHeld(player, new Held(item, hand));
        sound(player, Sound.BLOCK_LEVER_CLICK, .45f, turnOn ? 1.45f : .9f);
    }

    public boolean recharge(Player player, boolean free) {
        Held held = held(player);
        if (held == null) {
            return false;
        }
        if (!free && !plugin.settings().batteryEnabled()) {
            return false;
        }
        Session session = sessions.get(player.getUniqueId());
        double charge = session == null ? items.charge(held.item()) : session.state.battery();
        if (charge >= .999 && !free) {
            return false;
        }
        if (!free) {
            int slot = -1;
            for (int i = 0; i < player.getInventory().getSize(); i++) {
                if (items.isBattery(player.getInventory().getItem(i))) { slot = i; break; }
            }
            if (slot < 0) {
                sound(player, Sound.BLOCK_DISPENSER_FAIL, .3f, .7f);
                return false;
            }
            ItemStack battery = player.getInventory().getItem(slot);
            if (battery.getAmount() == 1) player.getInventory().setItem(slot, null);
            else {
                battery.setAmount(battery.getAmount() - 1);
                player.getInventory().setItem(slot, battery);
            }
        }
        if (session != null) session.state.recharge();
        boolean enabled = items.enabled(held.item());
        double intensity = !enabled ? 0 : session == null ? 1 : session.state.intensity();
        items.update(held.item(), enabled, 1, intensity < .3 ? "off" : "on", intensity, plugin.settings());
        storeHeld(player, held);
        sound(player, Sound.ITEM_ARMOR_EQUIP_IRON, .5f, 1.4f);
        return true;
    }

    public boolean forceFlicker(Player player) {
        Session session = activeSession(player);
        if (session == null) return false;
        session.state.forceFlicker(tick, plugin.settings());
        syncVisual(player, held(player), session);
        return true;
    }

    public boolean forceFlicker(Player player, int duration, double strength, int pulseTicks) {
        Session session = activeSession(player);
        if (session == null) return false;
        session.state.forceFlicker(tick, duration, strength, pulseTicks);
        syncVisual(player, held(player), session);
        return true;
    }

    /** A timed command locks the switch, then restores this same held item's previous state. */
    public boolean setPower(Player player, boolean enabled, int durationTicks) {
        Held selected = held(player);
        if (selected == null || enabled && !canRun(player, selected)) return false;
        String identity = items.identity(selected.item());
        storeHeld(player, selected);
        boolean previous = items.enabled(selected.item());
        PowerOverride old = powerOverrides.remove(player.getUniqueId());
        if (old != null && old.itemId().equals(identity)) previous = old.restoreEnabled();
        applyPower(player, selected.hand(), enabled);
        if (durationTicks > 0) {
            powerOverrides.put(player.getUniqueId(), new PowerOverride(tick + durationTicks, identity, previous, enabled));
        }
        lastToggle.remove(player.getUniqueId());
        return true;
    }

    private void applyPower(Player player, EquipmentSlot hand, boolean enabled) {
        stop(player);
        for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
            ItemStack item = player.getInventory().getItem(slot);
            if (items.enabled(item)) {
                items.update(item, false, items.charge(item), "off");
                player.getInventory().setItem(slot, item);
            }
        }
        ItemStack item = hand == EquipmentSlot.HAND ? player.getInventory().getItemInMainHand()
                : player.getInventory().getItemInOffHand();
        if (!items.isFlashlight(item)) return;
        items.identity(item);
        items.update(item, enabled, items.charge(item), enabled ? "on" : "off",
                enabled ? 1 : 0, plugin.settings());
        storeHeld(player, new Held(item, hand));
    }

    private boolean canRun(Player player, Held selected) {
        FlashlightSettings settings = plugin.settings();
        return player.hasPermission("flashlight.use") && !player.isDead()
                && player.getGameMode() != GameMode.SPECTATOR
                && !settings.disabledWorlds().contains(player.getWorld().getName())
                && (!settings.batteryEnabled() || items.charge(selected.item()) > 1.0e-9);
    }

    private Session activeSession(Player player) {
        Held selected = held(player);
        if (selected == null || !items.enabled(selected.item()) || !canRun(player, selected)) return null;
        String identity = items.identity(selected.item());
        Session session = sessions.get(player.getUniqueId());
        if (session != null && !session.itemId.equals(identity)) {
            stop(player);
            session = null;
        }
        if (session == null) {
            FlashlightState state = new FlashlightState(items.charge(selected.item()), new Random());
            state.start(tick, plugin.settings());
            session = new Session(identity, state);
            sessions.put(player.getUniqueId(), session);
            storeHeld(player, selected);
        }
        return session;
    }

    private void updateOverride(Player player) {
        PowerOverride override = powerOverrides.get(player.getUniqueId());
        if (override == null) return;
        Held selected = held(player);
        if (tick >= override.until()) {
            powerOverrides.remove(player.getUniqueId());
            if (selected != null && override.itemId().equals(items.existingIdentity(selected.item()))) {
                applyPower(player, selected.hand(), override.restoreEnabled() && canRun(player, selected));
            }
        } else if (selected != null) {
            boolean enabled = override.forcedEnabled()
                    && override.itemId().equals(items.existingIdentity(selected.item())) && canRun(player, selected);
            if (items.enabled(selected.item()) != enabled) applyPower(player, selected.hand(), enabled);
        }
    }

    private void tick() {
        tick++;
        FlashlightSettings settings = plugin.settings();
        for (Player player : Bukkit.getOnlinePlayers()) {
            updateOverride(player);
            Held held = held(player);
            if (held == null || !items.enabled(held.item()) || !player.hasPermission("flashlight.use")
                    || player.isDead() || player.getGameMode() == GameMode.SPECTATOR
                    || settings.disabledWorlds().contains(player.getWorld().getName())) {
                stop(player);
                continue;
            }
            Session session = activeSession(player);
            if (session == null) { stop(player); continue; }
            held = held(player);
            if (!session.state.tick(tick, settings)) {
                items.update(held.item(), false, 0, "off");
                storeHeld(player, held);
                sound(player, Sound.BLOCK_REDSTONE_TORCH_BURNOUT, .3f, 1.3f);
                stop(player);
                continue;
            }
            syncVisual(player, held, session);
            if (session.state.pulsedAt(tick) && session.state.intensity() < .5) {
                sound(player, Sound.BLOCK_REDSTONE_TORCH_BURNOUT, .055f, 1.6f);
            }
        }
        beams.updateShadows(Bukkit.getOnlinePlayers(), settings, tick);
    }

    private void syncVisual(Player player, Held held, Session session) {
        FlashlightSettings settings = plugin.settings();
        boolean submerged = !settings.waterproof() && player.getEyeLocation().getBlock().isLiquid();
        String visual = submerged || session.state.intensity() < .3 ? "off"
                : session.state.battery() <= settings.lowBatteryThreshold() && settings.batteryEnabled() ? "low" : "on";
        double intensity = submerged ? 0 : session.state.intensity();
        beams.update(player, held.hand(), intensity, settings, tick);
        if (!visual.equals(session.visual) || intensity != session.intensity || tick % 20 == 0) {
            items.update(held.item(), true, session.state.battery(), visual, intensity, settings);
            storeHeld(player, held);
            session.visual = visual;
            session.intensity = intensity;
        }
    }

    public void stop(Player player) {
        stop(player, List.of());
    }

    public void stop(Player player, Iterable<ItemStack> transferredItems) {
        beams.remove(player.getUniqueId());
        Session session = sessions.remove(player.getUniqueId());
        for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
            ItemStack item = player.getInventory().getItem(slot);
            boolean tracked = session != null && session.itemId.equals(items.existingIdentity(item));
            if (tracked || items.enabled(item)) {
                items.update(item, false, tracked ? session.state.battery() : items.charge(item), "off");
                player.getInventory().setItem(slot, item);
            }
        }
        for (ItemStack item : transferredItems) {
            boolean tracked = session != null && session.itemId.equals(items.existingIdentity(item));
            if (tracked || items.enabled(item)) {
                items.update(item, false, tracked ? session.state.battery() : items.charge(item), "off");
            }
        }
    }

    public void forget(Player player) {
        stop(player);
        lastToggle.remove(player.getUniqueId());
        powerOverrides.remove(player.getUniqueId());
    }

    public void stopAll() {
        for (Player player : Bukkit.getOnlinePlayers()) stop(player);
        powerOverrides.clear();
        beams.clear();
        lights.clear();
    }

    public void shutdown() { task.cancel(); stopAll(); }

    public BeamRenderer beams() { return beams; }

    private void storeHeld(Player player, Held held) {
        // Bukkit implementations may return copies; always commit modified metadata explicitly.
        if (held.hand() == EquipmentSlot.HAND) player.getInventory().setItemInMainHand(held.item());
        else player.getInventory().setItemInOffHand(held.item());
    }

    private void sound(Player player, Sound sound, float volume, float pitch) {
        if (plugin.settings().sounds()) player.getWorld().playSound(player.getLocation(), sound, volume, pitch);
    }
}
