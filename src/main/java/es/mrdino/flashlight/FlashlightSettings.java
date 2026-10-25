package es.mrdino.flashlight;

import org.bukkit.configuration.file.FileConfiguration;

import java.net.URI;
import java.util.Set;

public record FlashlightSettings(
        int updateInterval, double range, double coneAngle, int brightness, double beamVisibility,
        double spacing, int maxSources, double smoothing, double breathing, double walkingSway,
        boolean directFollow, int maxSurfacePatches, int surfaceCellBudget,
        boolean waterproof, Set<String> disabledWorlds, boolean flickerEnabled,
        int flickerMinInterval, int flickerMaxInterval, int flickerMinDuration, int flickerMaxDuration,
        double flickerStrength,
        double lowBatteryMultiplier, boolean batteryEnabled, int batteryDurationTicks,
        double lowBatteryThreshold, boolean batteryCrafting, boolean flashlightCrafting,
        boolean sounds,
        String packUrl, String packSha1, boolean packOnJoin, boolean packRequired, String packPrompt,
        Tracking tracking, Shadows shadows, Glare glare
) {
    public record Tracking(boolean shared, boolean localCamera, int interpolationTicks) { }
    public record Shadows(boolean enabled, int maxCasters, int updateInterval) { }
    public record Glare(boolean enabled, double strength, int maxDistance) { }
    public static FlashlightSettings load(FileConfiguration config) {
        int minInterval = clamp(config.getInt("flicker.min-interval-seconds", 25), 2, 3600) * 20;
        int maxInterval = Math.max(minInterval,
                clamp(config.getInt("flicker.max-interval-seconds", 65), 2, 3600) * 20);
        int minDuration = clamp(config.getInt("flicker.min-duration-ticks", 12), 4, 100);
        int maxDuration = Math.max(minDuration,
                clamp(config.getInt("flicker.max-duration-ticks", 28), 4, 100));
        String url = config.getString("resource-pack.url", "").trim();
        String sha1 = config.getString("resource-pack.sha1", "").trim();
        if (!url.isEmpty()) {
            URI uri = URI.create(url);
            if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null) {
                throw new IllegalArgumentException("resource-pack.url must be a direct HTTP(S) URL to the ZIP.");
            }
        }
        if (!sha1.isEmpty() && !sha1.matches("[a-fA-F0-9]{40}")) {
            throw new IllegalArgumentException("resource-pack.sha1 must contain 40 hexadecimal characters.");
        }
        return new FlashlightSettings(
                clamp(config.getInt("light.update-interval-ticks", 2), 1, 5),
                finite(config.getDouble("light.range", 20), 4, 32, 20),
                finite(config.getDouble("light.cone-angle-degrees", 28), 4, 65, 28),
                clamp(config.getInt("light.max-brightness", 12), 4, 15),
                finite(config.getDouble("light.beam-visibility", 1), 0, 3, 1),
                finite(config.getDouble("light.sample-spacing", 3), 1.5, 6, 3),
                clamp(config.getInt("light.max-sources-per-player", 64), 8, 128),
                finite(config.getDouble("light.follow-smoothing", .38), .05, 1, .38),
                finite(config.getDouble("light.breathing-amount", .003), 0, .03, .003),
                finite(config.getDouble("light.walking-sway-amount", .009), 0, .05, .009),
                config.getBoolean("light.follow-player-movement", true),
                clamp(config.getInt("light.max-surface-patches", 12), 2, 24),
                clamp(config.getInt("light.surface-cell-budget", 128), 32, 256),
                config.getBoolean("light.waterproof", false),
                Set.copyOf(config.getStringList("light.disabled-worlds")),
                config.getBoolean("flicker.enabled", true), minInterval, maxInterval, minDuration, maxDuration,
                finite(config.getDouble("flicker.strength-percent", 100), 0, 100, 100) / 100.0,
                finite(config.getDouble("flicker.low-battery-multiplier", 2.5), 1, 10, 2.5),
                config.getBoolean("battery.enabled", true),
                clamp(config.getInt("battery.duration-seconds", 900), 10, 86400) * 20,
                finite(config.getDouble("battery.low-threshold-percent", 20), 1, 90, 20) / 100.0,
                config.getBoolean("battery.crafting-enabled", true),
                config.getBoolean("crafting.flashlight-enabled", true),
                config.getBoolean("effects.sounds", true),
                url, sha1, config.getBoolean("resource-pack.send-on-join", false),
                config.getBoolean("resource-pack.required", false),
                config.getString("resource-pack.prompt", "Load FlashLight flashlight models and lighting."),
                new Tracking(config.getBoolean("light.shared-light", true),
                        config.getBoolean("light.local-camera-tracking", true),
                        clamp(config.getInt("light.interpolation-ticks", 2), 1, 5)),
                new Shadows(config.getBoolean("light.shadows.enabled", true),
                        clamp(config.getInt("light.shadows.max-casters", 8), 1, 8),
                        clamp(config.getInt("light.shadows.update-interval-ticks", 2), 1, 10)),
                new Glare(config.getBoolean("light.glare.enabled", true),
                        finite(config.getDouble("light.glare.strength", .65), 0, 1, .65),
                        clamp(config.getInt("light.glare.max-distance", 12), 1, 32)));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double finite(double value, double min, double max, double fallback) {
        return Double.isFinite(value) ? Math.max(min, Math.min(max, value)) : fallback;
    }
}
