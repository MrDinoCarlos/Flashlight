package es.mrdino.flashlight;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.ByteArrayInputStream;
import static org.junit.jupiter.api.Assertions.*;

class ConfigUpdaterTest {
    @TempDir Path directory;
    private java.io.InputStream defaults() {
        return new ByteArrayInputStream(("# Defaults\nlight:\n  range: 20\n  # New setting\n  cone-angle: 28\n"
                + "effects:\n  sounds: true\n  amount: 4\nworlds: [world]\n").getBytes(StandardCharsets.UTF_8));
    }

    @Test void shippingFollowAndSurfaceDefaultsAreAddedWithoutOverwritingOldTuning() throws Exception {
        Path file=directory.resolve("config.yml");
        Files.writeString(file,"# My lamp\nlight:\n  range: 14.0\n  follow-smoothing: 0.18\n  max-surface-patches: 3\n");
        try(var source=getClass().getResourceAsStream("/config.yml")) { assertTrue(ConfigUpdater.update(file,source)); }
        var settings=FlashlightSettings.load(YamlConfiguration.loadConfiguration(file.toFile()));
        assertTrue(settings.directFollow()); assertEquals(.18,settings.smoothing());
        assertEquals(14,settings.range()); assertEquals(3,settings.maxSurfacePatches()); assertEquals(128,settings.surfaceCellBudget());
        assertTrue(Files.readString(file).contains("My lamp"));
    }

    @Test void newEntityAndNexoDefaultsAndTheRequestedRenameKeepCustomValues() throws Exception {
        Path file=directory.resolve("config.yml");
        String old="# NIGHT"+"FALL config\nlight:\n  range: 13.5\nresource-pack:\n  prompt: 'Load NIGHT"+"FALL flashlight models and lighting.'\n  nexo:\n    auto-rebuild: false\ncustom: retained\n";
        Files.writeString(file,old);
        try(var source=getClass().getResourceAsStream("/config.yml")) { assertTrue(ConfigUpdater.update(file,source)); }
        var config=YamlConfiguration.loadConfiguration(file.toFile());
        assertTrue(config.getBoolean("light.entities.enabled")); assertTrue(config.getBoolean("resource-pack.nexo.enabled"));
        assertEquals(1,config.getDouble("light.beam-visibility"));
        assertEquals("AUTO",config.getString("resource-pack.nexo.integration"));
        assertFalse(config.getBoolean("resource-pack.nexo.auto-rebuild")); assertEquals(13.5,config.getDouble("light.range"));
        assertEquals("Load FlashLight flashlight models and lighting.",config.getString("resource-pack.prompt"));
        assertEquals("retained",config.getString("custom"));
        assertFalse(Files.readString(file).contains("NIGHT"+"FALL"));
        assertEquals(old,Files.readString(directory.resolve("config.yml.before-update.bak")));
    }

    @Test void addsOnlyMissingOptionsAndPreservesExistingSettingsCommentsAndUnknownKeys() throws Exception {
        Path file = directory.resolve("config.yml");
        String old = "# My settings\nlight:\n  range: 8.5 # Custom range\neffects:\n  sounds: false\n  amount: 0\nworlds: []\ncustom-key: keep-me\n";
        Files.writeString(file, old);
        assertTrue(ConfigUpdater.update(file, defaults()));
        var config = YamlConfiguration.loadConfiguration(file.toFile());
        assertEquals(8.5, config.getDouble("light.range"));
        assertEquals(28, config.getInt("light.cone-angle"));
        assertFalse(config.getBoolean("effects.sounds"));
        assertEquals(0, config.getInt("effects.amount"));
        assertTrue(config.getStringList("worlds").isEmpty());
        assertEquals("keep-me", config.getString("custom-key"));
        assertTrue(Files.readString(file).contains("My settings"));
        assertTrue(Files.readString(file).contains("Custom range"));
        assertEquals(old, Files.readString(directory.resolve("config.yml.before-update.bak")));
        byte[] updated = Files.readAllBytes(file);
        assertFalse(ConfigUpdater.update(file, defaults()));
        assertArrayEquals(updated, Files.readAllBytes(file));
    }

    @Test void malformedYamlIsNeverReplacedWithDefaults() throws Exception {
        Path file = directory.resolve("config.yml");
        String malformed = "light: [not closed\n";
        Files.writeString(file, malformed);
        assertThrows(org.bukkit.configuration.InvalidConfigurationException.class, () -> ConfigUpdater.update(file, defaults()));
        assertEquals(malformed, Files.readString(file));
        assertFalse(Files.exists(directory.resolve("config.yml.before-update.bak")));
    }

    @Test void aScalarSectionIsPreservedRatherThanOverwrittenByNewDefaults() throws Exception {
        Path file = directory.resolve("config.yml");
        Files.writeString(file, "light: my-custom-value\n");
        ConfigUpdater.update(file, defaults());
        assertEquals("my-custom-value", YamlConfiguration.loadConfiguration(file.toFile()).getString("light"));
    }
}
