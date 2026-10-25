package es.mrdino.flashlight.light;

import org.bukkit.Material;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Stored IN the chunk, so Minecraft saves the recovery data with its light blocks. */
public final class LightLedger {
    public record Entry(int localX, int y, int localZ, Material original) { }

    private LightLedger() { }

    public static String encode(Collection<Entry> entries) {
        StringBuilder text = new StringBuilder("1\n");
        for (Entry entry : entries) {
            text.append(entry.localX()).append(',').append(entry.y()).append(',')
                    .append(entry.localZ()).append(',').append(entry.original().name()).append('\n');
        }
        return text.toString();
    }

    public static List<Entry> decode(String text, int minY, int maxY) {
        if (text == null || !text.startsWith("1\n")) return List.of();
        List<Entry> entries = new ArrayList<>();
        for (String line : text.substring(2).split("\n")) {
            String[] parts = line.split(",");
            if (parts.length != 4) continue;
            try {
                int x = Integer.parseInt(parts[0]), y = Integer.parseInt(parts[1]), z = Integer.parseInt(parts[2]);
                Material original = Material.valueOf(parts[3]);
                if (x >= 0 && x < 16 && z >= 0 && z < 16 && y >= minY && y < maxY
                        && (original == Material.AIR || original == Material.CAVE_AIR)) {
                    entries.add(new Entry(x, y, z, original));
                }
            } catch (IllegalArgumentException ignored) {
                // One damaged entry must not prevent recovery of the rest of the chunk.
            }
        }
        return entries;
    }
}
