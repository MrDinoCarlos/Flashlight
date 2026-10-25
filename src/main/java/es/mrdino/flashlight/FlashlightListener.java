package es.mrdino.flashlight;

import es.mrdino.flashlight.pack.PackService;
import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.Arrays;
import java.util.List;

public final class FlashlightListener implements Listener {
    private final FlashlightPlugin plugin;

    public FlashlightListener(FlashlightPlugin plugin) { this.plugin = plugin; }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void interact(PlayerInteractEvent event) {
        if (event.getHand() == null || !plugin.items().isFlashlight(event.getItem())) return;
        // Vanilla marks right-click-in-air as cancelled; do NOT use ignoreCancelled here.
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        // One mouse click produces both hand events. Give the main-hand flashlight priority.
        if (event.getHand() == EquipmentSlot.OFF_HAND
                && plugin.items().isFlashlight(event.getPlayer().getInventory().getItemInMainHand())) return;
        event.setUseItemInHand(Event.Result.DENY);
        event.setUseInteractedBlock(Event.Result.DENY);
        if (!event.getPlayer().hasPermission("flashlight.use")) return;
        if (event.getPlayer().isSneaking()) plugin.service().recharge(event.getPlayer(), false);
        else plugin.service().toggle(event.getPlayer(), event.getHand());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void entityInteract(PlayerInteractEntityEvent event) {
        ItemStack item = event.getHand() == EquipmentSlot.HAND
                ? event.getPlayer().getInventory().getItemInMainHand()
                : event.getPlayer().getInventory().getItemInOffHand();
        if (plugin.items().isFlashlight(item)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void held(PlayerItemHeldEvent event) { plugin.service().stop(event.getPlayer()); }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void swap(PlayerSwapHandItemsEvent event) {
        plugin.service().stop(event.getPlayer(), Arrays.asList(event.getMainHandItem(), event.getOffHandItem()));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void drop(PlayerDropItemEvent event) {
        ItemStack dropped = event.getItemDrop().getItemStack();
        plugin.service().stop(event.getPlayer(), List.of(dropped));
        if (plugin.items().isFlashlight(dropped)) {
            plugin.items().update(dropped, false, plugin.items().charge(dropped), "off");
            event.getItemDrop().setItemStack(dropped);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void inventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof org.bukkit.entity.Player player) plugin.service().stop(player);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void inventoryDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof org.bukkit.entity.Player player) plugin.service().stop(player);
    }

    @EventHandler
    public void preventCustomIngredients(PrepareItemCraftEvent event) {
        // Prevent a flashlight becoming ordinary iron/horse armor or a pile of full batteries.
        for (ItemStack ingredient : event.getInventory().getMatrix()) {
            if (plugin.items().isFlashlight(ingredient) || plugin.items().isBattery(ingredient)) {
                event.getInventory().setResult(null);
                return;
            }
        }
    }

    @EventHandler
    public void anvil(PrepareAnvilEvent event) {
        if (plugin.items().isFlashlight(event.getInventory().getItem(0))
                || plugin.items().isFlashlight(event.getInventory().getItem(1))) event.setResult(null);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void teleport(PlayerTeleportEvent event) { plugin.service().stop(event.getPlayer()); }

    @EventHandler
    public void worldChange(PlayerChangedWorldEvent event) { plugin.service().stop(event.getPlayer()); }

    @EventHandler
    public void death(PlayerDeathEvent event) {
        plugin.service().stop(event.getEntity(), event.getDrops());
        for (ItemStack item : event.getDrops()) {
            if (plugin.items().isFlashlight(item)) plugin.items().update(item, false, plugin.items().charge(item), "off");
        }
    }

    @EventHandler
    public void quit(PlayerQuitEvent event) {
        plugin.service().forget(event.getPlayer());
        plugin.packs().forget(event.getPlayer());
    }

    @EventHandler
    public void join(PlayerJoinEvent event) {
        if (plugin.settings().packOnJoin()) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (event.getPlayer().isOnline()) plugin.packs().request(event.getPlayer(), true);
            }, 30);
        }
    }

    @EventHandler
    public void chunkLoad(ChunkLoadEvent event) { plugin.lights().recover(event.getChunk()); }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void chunkUnload(ChunkUnloadEvent event) { plugin.lights().unload(event.getChunk()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void blockPlace(org.bukkit.event.block.BlockPlaceEvent event) { plugin.service().beams().invalidate(event.getBlock().getLocation()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void blockBreak(org.bukkit.event.block.BlockBreakEvent event) { plugin.service().beams().invalidate(event.getBlock().getLocation()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void blockPhysics(org.bukkit.event.block.BlockPhysicsEvent event) { plugin.service().beams().invalidate(event.getBlock().getLocation()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void pistonExtend(org.bukkit.event.block.BlockPistonExtendEvent event) { plugin.service().beams().invalidate(event.getBlock().getLocation()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void pistonRetract(org.bukkit.event.block.BlockPistonRetractEvent event) { plugin.service().beams().invalidate(event.getBlock().getLocation()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void blockFlow(org.bukkit.event.block.BlockFromToEvent event) { plugin.service().beams().invalidate(event.getToBlock().getLocation()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void blockExplosion(org.bukkit.event.block.BlockExplodeEvent event) { plugin.service().beams().invalidate(event.getBlock().getLocation()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void entityExplosion(org.bukkit.event.entity.EntityExplodeEvent event) { plugin.service().beams().invalidate(event.getLocation()); }

    @EventHandler
    public void packStatus(PlayerResourcePackStatusEvent event) {
        if (!PackService.isOurPack(event.getID())) return;
        switch (event.getStatus()) {
            case FAILED_DOWNLOAD, FAILED_RELOAD, INVALID_URL -> plugin.getLogger().warning(
                    "Resource pack failed for " + event.getPlayer().getName() + ": " + event.getStatus());
            default -> { }
        }
    }
}
