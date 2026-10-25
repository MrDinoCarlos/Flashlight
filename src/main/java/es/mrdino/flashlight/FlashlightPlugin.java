package es.mrdino.flashlight;

import es.mrdino.flashlight.light.LightEngine;
import es.mrdino.flashlight.pack.PackService;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.Material;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.util.Objects;
import java.util.logging.Level;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.util.List;

public class FlashlightPlugin extends JavaPlugin {
    private FlashlightSettings settings;
    private FlashlightItems items;
    private LightEngine lights;
    private FlashlightService service;
    private PackService packs;
    private NamespacedKey flashlightRecipe, batteryRecipe;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        try {
            ServerCompatibility.check(this);
            if (ConfigUpdater.update(getDataFolder().toPath().resolve("config.yml"), getResource("config.yml"))) reloadConfig();
            settings = FlashlightSettings.load(getConfig());
            items = new FlashlightItems(this);
            lights = new LightEngine(new NamespacedKey(this, "temporary_lights"));
            for (World world : Bukkit.getWorlds()) {
                for (var chunk : world.getLoadedChunks()) lights.recover(chunk);
            }
            packs = new PackService(this);
            service = new FlashlightService(this, items, lights);
            flashlightRecipe = new NamespacedKey(this, "flashlight");
            batteryRecipe = new NamespacedKey(this, "battery");
            recipes();
            FlashlightCommand command = new FlashlightCommand(this);
            Objects.requireNonNull(getCommand("flashlight")).setExecutor(command);
            Objects.requireNonNull(getCommand("flashlight")).setTabCompleter(command);
            registerCommandTree(command);
            getServer().getPluginManager().registerEvents(new FlashlightListener(this), this);
            getLogger().info("FlashLight ready. Beam shaders are included in the resource pack.");
        } catch (Exception exception) {
            getLogger().log(Level.SEVERE, "Could not start FlashLight.", exception);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    public void reloadFlashlight() throws IOException, org.bukkit.configuration.InvalidConfigurationException {
        ConfigUpdater.update(getDataFolder().toPath().resolve("config.yml"), getResource("config.yml"));
        reloadConfig();
        FlashlightSettings replacement = FlashlightSettings.load(getConfig());
        service.shutdown();
        packs.close();
        settings = replacement;
        packs = new PackService(this);
        service = new FlashlightService(this, items, lights);
        recipes();
    }

    protected void registerCommandTree(FlashlightCommand command) {
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
                event.registrar().register(new FlashlightCommandTree(command).build(),
                        "Control flashlights, timed power and electrical faults.", List.of("fl")));
    }

    private void recipes() {
        Bukkit.removeRecipe(flashlightRecipe);
        Bukkit.removeRecipe(batteryRecipe);
        if (settings.flashlightCrafting()) {
            ShapedRecipe recipe = new ShapedRecipe(flashlightRecipe, items.flashlight());
            recipe.shape(" G ", "IRI", " C ");
            recipe.setIngredient('G', Material.GLASS_PANE);
            recipe.setIngredient('I', Material.IRON_INGOT);
            recipe.setIngredient('R', Material.REDSTONE);
            recipe.setIngredient('C', Material.COPPER_INGOT);
            Bukkit.addRecipe(recipe);
        }
        if (settings.batteryCrafting()) {
            ShapelessRecipe recipe = new ShapelessRecipe(batteryRecipe, items.battery(1));
            recipe.addIngredient(Material.COPPER_INGOT);
            recipe.addIngredient(Material.REDSTONE);
            recipe.addIngredient(Material.IRON_NUGGET);
            Bukkit.addRecipe(recipe);
        }
    }

    @Override
    public void onDisable() {
        if (service != null) service.shutdown();
        else if (lights != null) lights.clear();
        if (packs != null) packs.close();
        if (flashlightRecipe != null) Bukkit.removeRecipe(flashlightRecipe);
        if (batteryRecipe != null) Bukkit.removeRecipe(batteryRecipe);
    }

    public FlashlightSettings settings() { return settings; }
    public FlashlightItems items() { return items; }
    public FlashlightService service() { return service; }
    public LightEngine lights() { return lights; }
    public PackService packs() { return packs; }
}
