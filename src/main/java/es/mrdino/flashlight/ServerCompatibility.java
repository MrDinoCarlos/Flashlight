package es.mrdino.flashlight;

import java.io.IOException;
import java.util.Arrays;
import java.util.Properties;
import org.bukkit.plugin.Plugin;

/** Each distribution declares the exact native client/server versions it targets. */
public final class ServerCompatibility {
    private ServerCompatibility() { }

    public static void check(Plugin plugin) throws IOException {
        try (var resource = plugin.getResource("compatibility.properties")) {
            if (resource == null) return; // Development and MockBukkit classpath.
            var properties = new Properties();
            properties.load(resource);
            String minecraft = plugin.getServer().getMinecraftVersion();
            String versions = properties.getProperty("minecraft.versions", "");
            if (!Arrays.asList(versions.split(",")).contains(minecraft)) {
                throw new IllegalStateException("This FlashLight JAR targets "
                        + properties.getProperty("minecraft.branch") + " (" + versions + "), but the server runs "
                        + minecraft + ". Install the JAR for this server version.");
            }
            plugin.getLogger().info("Minecraft " + minecraft + "; FlashLight "
                    + properties.getProperty("minecraft.branch") + " resource pack.");
        }
    }
}
