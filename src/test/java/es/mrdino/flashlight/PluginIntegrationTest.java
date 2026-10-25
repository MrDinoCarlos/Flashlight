package es.mrdino.flashlight;

import org.bukkit.Material;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MainHand;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.NamespacedKey;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Light;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.block.data.BlockDataMock;
import org.mockbukkit.mockbukkit.inventory.ItemFactoryMock;
import org.mockbukkit.mockbukkit.inventory.meta.ItemMetaMock;

import java.nio.file.Files;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.bukkit.configuration.file.YamlConfiguration;

import static org.junit.jupiter.api.Assertions.*;

class PluginIntegrationTest {
    // MockBukkit 4.30 has no lifecycle command registrar. The actual Brigadier tree has separate tests.
    public static class TestPlugin extends FlashlightPlugin {
        @Override protected void registerCommandTree(FlashlightCommand command) { }
    }
    private static final class RightHandedPlayerMock extends PlayerMock {
        RightHandedPlayerMock(ServerMock server) { super(server, "Explorer", java.util.UUID.randomUUID()); }
        int messages, actionBars;
        @Override public MainHand getMainHand() { return MainHand.RIGHT; }
        @Override public void sendMessage(net.kyori.adventure.text.Component message) { messages++; }
        @Override public void sendActionBar(net.kyori.adventure.text.Component message) { actionBars++; }
    }
    // MockBukkit 4.30 targets 1.21.4 but lacks a typed LIGHT BlockData implementation.
    private static final class LightDataMock extends BlockDataMock implements Light {
        private int level = 15;
        private boolean waterlogged;
        LightDataMock() { super(Material.LIGHT); }
        @Override public int getLevel() { return level; }
        @Override public void setLevel(int level) { this.level = level; }
        @Override public int getMaximumLevel() { return 15; }
        @Override public int getMinimumLevel() { return 0; }
        @Override public boolean isWaterlogged() { return waterlogged; }
        @Override public void setWaterlogged(boolean waterlogged) { this.waterlogged = waterlogged; }
    }

    private static final class LightServerMock extends ServerMock {
        private final ItemFactoryMock itemFactory = new ItemFactoryMock() {
            private boolean custom(Material material) {
                return material == Material.IRON_HORSE_ARMOR || material == Material.PRISMARINE_SHARD;
            }
            @Override public ItemMeta getItemMeta(Material material) {
                return custom(material) ? new ModelMetaMock() : super.getItemMeta(material);
            }
            @Override public ItemMeta asMetaFor(ItemMeta meta, Material material) {
                return custom(material) ? new ModelMetaMock(meta) : super.asMetaFor(meta, material);
            }
        };
        @Override public ItemFactoryMock getItemFactory() { return itemFactory; }
        @Override public BlockData createBlockData(Material material) {
            return material == Material.LIGHT ? new LightDataMock() : super.createBlockData(material);
        }
    }

    // The same MockBukkit release also leaves the new item_model accessors unimplemented.
    private static final class ModelMetaMock extends ItemMetaMock {
        private NamespacedKey model;
        private java.util.List<Float> floats = java.util.List.of();
        private java.util.List<org.bukkit.Color> colors = java.util.List.of();
        ModelMetaMock() { }
        ModelMetaMock(ItemMeta meta) { super(meta); if (meta instanceof ModelMetaMock typed) { model = typed.model; floats = typed.floats; colors = typed.colors; } }
        @Override public void setItemModel(NamespacedKey model) { this.model = model; }
        @Override public NamespacedKey getItemModel() { return model; }
        @Override public boolean hasItemModel() { return model != null; }
        @Override public org.bukkit.inventory.meta.components.CustomModelDataComponent getCustomModelDataComponent() {
            return new ModelData(floats, colors);
        }
        @Override public void setCustomModelDataComponent(org.bukkit.inventory.meta.components.CustomModelDataComponent data) {
            floats = data == null ? java.util.List.of() : java.util.List.copyOf(data.getFloats());
            colors = data == null ? java.util.List.of() : java.util.List.copyOf(data.getColors());
        }
    }

    private static final class ModelData implements org.bukkit.inventory.meta.components.CustomModelDataComponent {
        private java.util.List<Float> floats;
        private java.util.List<org.bukkit.Color> colors;
        ModelData(java.util.List<Float> floats, java.util.List<org.bukkit.Color> colors) { this.floats = java.util.List.copyOf(floats); this.colors = java.util.List.copyOf(colors); }
        @Override public java.util.List<Float> getFloats() { return floats; }
        @Override public void setFloats(java.util.List<Float> floats) { this.floats = java.util.List.copyOf(floats); }
        @Override public java.util.List<Boolean> getFlags() { return java.util.List.of(); }
        @Override public void setFlags(java.util.List<Boolean> values) { }
        @Override public java.util.List<String> getStrings() { return java.util.List.of(); }
        @Override public void setStrings(java.util.List<String> values) { }
        @Override public java.util.List<org.bukkit.Color> getColors() { return colors; }
        @Override public void setColors(java.util.List<org.bukkit.Color> values) { colors = java.util.List.copyOf(values); }
        @Override public java.util.Map<String, Object> serialize() { return java.util.Map.of("floats", floats); }
    }

    private ServerMock server;
    private FlashlightPlugin plugin;
    private RightHandedPlayerMock player;

    @BeforeEach
    void setup() {
        server = MockBukkit.mock(new LightServerMock());
        var world = server.addSimpleWorld("world");
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) world.loadChunk(x, z);
        }
        var config = YamlConfiguration.loadConfiguration(new InputStreamReader(
                java.util.Objects.requireNonNull(FlashlightPlugin.class.getResourceAsStream("/config.yml")), StandardCharsets.UTF_8));
        config.set("flicker.enabled", false);
        config.set("effects.sounds", false);
        // MockBukkit 4.30 has no entity visibility API. Multiplayer controls
        // have separate Mockito/GPU/native-Paper coverage.
        config.set("light.local-camera-tracking", false);
        config.set("light.shadows.enabled", false);
        plugin = MockBukkit.loadWithConfig(TestPlugin.class, config);
        player = new RightHandedPlayerMock(server);
        server.addPlayer(player);
        player.teleport(new org.bukkit.Location(world, 0, 64, 0));
        player.setOp(true);
    }

    @AfterEach
    void cleanup() { MockBukkit.unmock(); }

    @Test void independentFlashlightsKeepTheirControllerWhenAnotherPlayerTurnsOff() {
        var other=new PlayerMock(server,"Observer",java.util.UUID.randomUUID()) {
            @Override public MainHand getMainHand() { return MainHand.RIGHT; }
        };
        server.addPlayer(other); other.teleport(player.getLocation().add(3,0,0));
        var beams=plugin.service().beams();
        beams.update(player,EquipmentSlot.HAND,1,plugin.settings(),1);
        var first=player.getWorld().getEntitiesByClass(org.bukkit.entity.ItemDisplay.class).iterator().next();
        beams.update(other,EquipmentSlot.HAND,1,plugin.settings(),1);
        var second=player.getWorld().getEntitiesByClass(org.bukkit.entity.ItemDisplay.class).stream()
                .filter(entity->!entity.getUniqueId().equals(first.getUniqueId())).findFirst().orElseThrow();
        var color=second.getGlowColorOverride();
        assertNotEquals(first.getGlowColorOverride().getRed()&31,color.getRed()&31);
        beams.remove(player.getUniqueId());
        beams.update(other,EquipmentSlot.HAND,1,plugin.settings(),2);
        assertTrue(second.isValid()); assertEquals(color,second.getGlowColorOverride());
        assertEquals(1,player.getWorld().getEntitiesByClass(org.bukkit.entity.ItemDisplay.class).size());
        beams.update(player,EquipmentSlot.HAND,1,plugin.settings(),2);
        assertEquals(2,player.getWorld().getEntitiesByClass(org.bukkit.entity.ItemDisplay.class).size());
        assertFalse(player.isGlowing()); assertFalse(other.isGlowing());
    }

    @Test
    void startsRegistersCommandsAndExportsTheBundledPack() {
        assertTrue(plugin.isEnabled());
        assertNotNull(plugin.getCommand("flashlight"));
        assertTrue(Files.isRegularFile(plugin.getDataFolder().toPath().resolve("FlashLight-ResourcePack.zip")));
        assertTrue(server.dispatchCommand(player, "flashlight give"));
        assertTrue(plugin.items().isFlashlight(player.getInventory().getItem(0)));
        assertNotNull(server.getRecipe(new org.bukkit.NamespacedKey(plugin, "flashlight")));
    }

    @Test void reloadingBeamVisibilityReachesThePackAndKeepsDirectLightPower() throws Exception {
        var beams=plugin.service().beams();
        for(double visibility:new double[]{0,2.5}) {
            plugin.getConfig().set("light.beam-visibility",visibility); plugin.saveConfig();
            plugin.reloadFlashlight();
            assertEquals(visibility,plugin.settings().beamVisibility());
            beams.update(player,EquipmentSlot.HAND,1,plugin.settings(),1);
            var display=player.getWorld().getEntitiesByClass(org.bukkit.entity.ItemDisplay.class).iterator().next();
            assertEquals(1+visibility,display.getTransformation().getScale().x(),.00001);
            assertEquals(.8,beamIntensity(),1.0/255);
            assertFalse(player.isGlowing());
        }
    }

    @Test
    void rightClickInAirCanTurnOnAndOffAndDoesNotUseHorseArmor() {
        server.dispatchCommand(player, "flashlight give");
        player.messages = player.actionBars = 0;
        ItemStack item = player.getInventory().getItemInMainHand();
        PlayerInteractEvent on = new PlayerInteractEvent(player, Action.RIGHT_CLICK_AIR,
                item, null, null, EquipmentSlot.HAND);
        server.getPluginManager().callEvent(on);
        assertTrue(plugin.items().enabled(player.getInventory().getItemInMainHand()));
        assertEquals(org.bukkit.event.Event.Result.DENY, on.useItemInHand());
        server.getScheduler().performTicks(8);
        assertEquals(0, plugin.lights().activeCount());
        assertEquals(1, plugin.service().beams().activeCount());
        assertEquals(1, player.getWorld().getEntities().stream().filter(org.bukkit.entity.ItemDisplay.class::isInstance).count());
        assertEquals(1,plugin.service().beams().screenLightCount(player.getUniqueId()));
        assertFalse(player.isGlowing());
        ItemStack held = player.getInventory().getItemInMainHand();
        PlayerInteractEvent off = new PlayerInteractEvent(player, Action.RIGHT_CLICK_AIR,
                held, null, null, EquipmentSlot.HAND);
        server.getPluginManager().callEvent(off);
        server.getScheduler().performTicks(2);
        assertFalse(plugin.items().enabled(player.getInventory().getItemInMainHand()));
        assertEquals(0, plugin.lights().activeCount());
        assertEquals(0, plugin.service().beams().activeCount());
        assertTrue(player.getWorld().getEntities().stream().noneMatch(org.bukkit.entity.ItemDisplay.class::isInstance));
        assertEquals(0, player.messages);
        assertEquals(0, player.actionBars);
    }

    @Test
    void consumesExactlyOneCustomBatteryAndOrdinaryShardsDoNotWork() {
        server.dispatchCommand(player, "flashlight give");
        ItemStack flashlight = player.getInventory().getItemInMainHand();
        plugin.items().update(flashlight, false, .25, "off");
        player.getInventory().setItemInMainHand(flashlight);
        player.getInventory().setItem(1, new ItemStack(Material.PRISMARINE_SHARD, 4));
        assertFalse(plugin.service().recharge(player, false));
        player.getInventory().setItem(2, plugin.items().battery(3));
        assertTrue(plugin.service().recharge(player, false));
        assertEquals(1, plugin.items().charge(player.getInventory().getItemInMainHand()));
        assertEquals(2, player.getInventory().getItem(2).getAmount());
        assertEquals(4, player.getInventory().getItem(1).getAmount());
    }

    @Test
    void shutdownPersistsChargeWithoutCreatingWorldLights() {
        server.dispatchCommand(player, "flashlight give");
        plugin.service().toggle(player, EquipmentSlot.HAND);
        server.getScheduler().performTicks(8);
        assertEquals(0, plugin.lights().activeCount());
        server.getPluginManager().disablePlugin(plugin);
        assertEquals(0, plugin.lights().activeCount());
        assertFalse(plugin.items().enabled(player.getInventory().getItemInMainHand()));
        assertTrue(plugin.items().charge(player.getInventory().getItemInMainHand()) < 1);
    }

    @Test
    void customModelDoesNotChangeOrdinaryMinecraftItems() {
        ItemStack ordinary = new ItemStack(Material.IRON_HORSE_ARMOR);
        ItemStack flashlight = plugin.items().flashlight();
        assertFalse(plugin.items().isFlashlight(ordinary));
        assertTrue(ordinary.getItemMeta().getPersistentDataContainer().isEmpty());
        assertNull(ordinary.getItemMeta().displayName());
        assertEquals(new NamespacedKey("flashlight", "flashlight_off"), flashlight.getItemMeta().getItemModel());
        assertEquals(Material.IRON_HORSE_ARMOR, ordinary.getType());
        assertEquals(Material.IRON_HORSE_ARMOR, flashlight.getType());
    }

    @Test
    void transferredItemsKeepTheConsumedChargeAndSwitchOff() {
        server.dispatchCommand(player, "flashlight give");
        plugin.service().toggle(player, EquipmentSlot.HAND);
        server.getScheduler().performTicks(8);
        ItemStack dropped = player.getInventory().getItemInMainHand();
        player.getInventory().setItemInMainHand(null);
        plugin.service().stop(player, java.util.List.of(dropped));
        assertFalse(plugin.items().enabled(dropped));
        assertTrue(plugin.items().charge(dropped) < 1);
    }

    private float intensity() {
        return player.getInventory().getItemInMainHand().getItemMeta().getCustomModelDataComponent().getFloats().getFirst();
    }

    @Test
    void namedCommandsControlDurationStrengthAndPulseWithoutMessagingTheTarget() {
        server.dispatchCommand(player, "flashlight give");
        var console = server.getConsoleSender();
        server.dispatchCommand(console, "flashlight on Explorer");
        server.dispatchCommand(console, "flashlight flicker Explorer 1s 85 4t");
        assertEquals(.15 * .8, beamIntensity(), 1.0 / 255);
        player.messages = player.actionBars = 0;
        server.getScheduler().performTicks(1);
        assertEquals(.15, intensity(), .0001);
        server.getScheduler().performTicks(2);
        assertEquals(.15, intensity(), .0001);
        server.getScheduler().performTicks(1);
        assertEquals(1, intensity());
        server.getScheduler().performTicks(16);
        assertEquals(1, intensity());
        assertTrue(plugin.items().enabled(player.getInventory().getItemInMainHand()));
        assertEquals(0, player.messages);
        assertEquals(0, player.actionBars);
    }

    @Test
    void timedPowerOffLocksTheSwitchAndRestoresTheSameLamp() {
        server.dispatchCommand(player, "flashlight give");
        server.dispatchCommand(server.getConsoleSender(), "flashlight on Explorer");
        server.getScheduler().performTicks(5);
        server.dispatchCommand(server.getConsoleSender(), "flashlight off Explorer 10t");
        assertFalse(plugin.items().enabled(player.getInventory().getItemInMainHand()));
        double pausedCharge = plugin.items().charge(player.getInventory().getItemInMainHand());
        plugin.service().toggle(player, EquipmentSlot.HAND);
        assertFalse(plugin.items().enabled(player.getInventory().getItemInMainHand()));
        server.getScheduler().performTicks(9);
        assertFalse(plugin.items().enabled(player.getInventory().getItemInMainHand()));
        assertEquals(pausedCharge, plugin.items().charge(player.getInventory().getItemInMainHand()));
        server.getScheduler().performTicks(1);
        assertTrue(plugin.items().enabled(player.getInventory().getItemInMainHand()));
        assertTrue(intensity() > 0);
    }

    @Test
    void timedPowerOnRestoresOffAndPermanentCommandsCancelTheTimer() {
        server.dispatchCommand(player, "flashlight give");
        assertTrue(plugin.service().setPower(player, true, 4));
        server.getScheduler().performTicks(4);
        assertFalse(plugin.items().enabled(player.getInventory().getItemInMainHand()));
        plugin.service().setPower(player, true, 0);
        server.getScheduler().performTicks(2);
        plugin.service().setPower(player, false, 4);
        plugin.service().setPower(player, false, 0);
        server.getScheduler().performTicks(8);
        assertFalse(plugin.items().enabled(player.getInventory().getItemInMainHand()));
    }

    @Test
    void expirationDoesNotTurnOnAReplacementItem() {
        server.dispatchCommand(player, "flashlight give");
        plugin.service().setPower(player, true, 0);
        server.getScheduler().performTicks(2);
        plugin.service().setPower(player, false, 5);
        ItemStack original = player.getInventory().getItemInMainHand();
        player.getInventory().setItem(1, original);
        player.getInventory().setItemInMainHand(plugin.items().flashlight());
        server.getScheduler().performTicks(6);
        assertFalse(plugin.items().enabled(player.getInventory().getItemInMainHand()));
        assertFalse(plugin.items().enabled(player.getInventory().getItem(1)));
    }

    @Test
    void invalidUseCannotLeaveClientBrightnessEnabledWithoutAnActiveSession() {
        var lamp = plugin.items().flashlight();
        plugin.items().update(lamp, true, 1, "on");
        player.getInventory().setItemInMainHand(lamp);
        player.setGameMode(org.bukkit.GameMode.SPECTATOR);
        server.getScheduler().performTicks(1);
        assertFalse(plugin.items().enabled(player.getInventory().getItemInMainHand()));
        assertEquals(0, intensity());
    }

    @Test
    void aOneTickCommandFaultDimsImmediatelyAndRecoversOnTime() {
        player.getInventory().setItemInMainHand(plugin.items().flashlight());
        plugin.service().setPower(player, true, 0);
        server.dispatchCommand(server.getConsoleSender(), "flashlight flicker Explorer 1t 100");
        assertEquals(0, intensity());
        assertTrue(plugin.items().enabled(player.getInventory().getItemInMainHand()));
        server.getScheduler().performTicks(1);
        assertEquals(1, intensity());
        assertEquals(.8, beamIntensity(), 1.0 / 255);
    }

    private double beamIntensity() {
        var display = player.getWorld().getEntities().stream().filter(org.bukkit.entity.ItemDisplay.class::isInstance)
                .map(org.bukkit.entity.ItemDisplay.class::cast).filter(entity -> !entity.getItemStack().getType().isAir())
                .filter(entity -> entity.getItemStack().getItemMeta().getItemModel().getKey().equals("beam/entity_control"))
                .findFirst().orElseThrow();
        var color = display.getGlowColorOverride();
        int word = color.getRed() | color.getGreen() << 8 | color.getBlue() << 16;
        return (word >>> 5 & 255) / 255.0;
    }

    @Test
    void invalidArgumentsDoNotChangeTheLightAndNonAdminsCannotControlPlayers() {
        server.dispatchCommand(player, "flashlight give");
        server.dispatchCommand(player, "flashlight on Explorer");
        for (String args : java.util.List.of("off Explorer -5s", "off Explorer 999999999s",
                "flicker Explorer 2s NaN", "flicker Explorer 2s 101", "flicker Explorer 2s 80 1t")) {
            assertTrue(server.dispatchCommand(player, "flashlight " + args));
            assertTrue(plugin.items().enabled(player.getInventory().getItemInMainHand()));
        }
        player.setOp(false);
        server.dispatchCommand(player, "flashlight off Explorer");
        assertTrue(plugin.items().enabled(player.getInventory().getItemInMainHand()));
    }

    @Test
    void selectorsUseTheOriginalCommandBlockContextAndFilterNonPlayers() {
        var sender = org.mockito.Mockito.mock(org.bukkit.command.BlockCommandSender.class);
        org.mockito.Mockito.when(sender.hasPermission("flashlight.admin")).thenReturn(true);
        player.getInventory().setItemInMainHand(plugin.items().flashlight());
        var second = server.addPlayer("Second");
        second.getInventory().setItemInMainHand(plugin.items().flashlight());
        try (var bukkit = org.mockito.Mockito.mockStatic(org.bukkit.Bukkit.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
            bukkit.when(() -> org.bukkit.Bukkit.selectEntities(sender, "@a[distance=..10]"))
                    .thenReturn(java.util.List.of(player, second, org.mockito.Mockito.mock(org.bukkit.entity.Entity.class)));
            assertTrue(plugin.getCommand("flashlight").execute(sender, "flashlight", new String[]{"on", "@a[distance=..10]"}));
            assertTrue(plugin.items().enabled(player.getInventory().getItemInMainHand()));
            assertTrue(plugin.items().enabled(second.getInventory().getItemInMainHand()));
            bukkit.verify(() -> org.bukkit.Bukkit.selectEntities(sender, "@a[distance=..10]"));
        }
    }
}
