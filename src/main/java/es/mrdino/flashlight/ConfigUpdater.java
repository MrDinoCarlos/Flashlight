package es.mrdino.flashlight;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

/** Add missing defaults without resetting existing values, unknown keys or comments. */
public final class ConfigUpdater {
    private ConfigUpdater() { }

    public static boolean update(Path file, InputStream resource) throws IOException, InvalidConfigurationException {
        YamlConfiguration defaults = new YamlConfiguration();
        defaults.options().parseComments(true);
        try (var reader = new InputStreamReader(Objects.requireNonNull(resource), StandardCharsets.UTF_8)) {
            defaults.load(reader);
        }
        byte[] previous = Files.readAllBytes(file);
        YamlConfiguration existing = new YamlConfiguration();
        existing.options().parseComments(true);
        // Strict parsing: an invalid file must never be replaced with the defaults.
        String original=new String(previous,StandardCharsets.UTF_8);
        String migrated=original.replace("NIGHT"+"FALL","FlashLight");
        existing.loadFromString(migrated);
        boolean changed = merge(existing, defaults) || !original.equals(migrated);
        if (!changed) return false;
        Path backup = file.resolveSibling(file.getFileName() + ".before-update.bak");
        if (!Files.exists(backup)) Files.write(backup, previous, java.nio.file.StandardOpenOption.CREATE_NEW);
        Path temporary = Files.createTempFile(file.getParent(), "config-update-", ".tmp");
        try {
            Files.writeString(temporary, existing.saveToString(), StandardCharsets.UTF_8);
            try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ignored) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
        return true;
    }

    private static boolean merge(ConfigurationSection existing, ConfigurationSection defaults) {
        boolean changed = false;
        for (String key : defaults.getKeys(false)) {
            Object value = defaults.get(key);
            if (existing.contains(key, true)) {
                if (value instanceof ConfigurationSection section && existing.isConfigurationSection(key)) {
                    changed |= merge(Objects.requireNonNull(existing.getConfigurationSection(key)), section);
                }
                continue;
            }
            if (value instanceof ConfigurationSection section) {
                merge(existing.createSection(key), section);
            } else existing.set(key, value);
            existing.setComments(key, defaults.getComments(key));
            existing.setInlineComments(key, defaults.getInlineComments(key));
            changed = true;
        }
        return changed;
    }
}
