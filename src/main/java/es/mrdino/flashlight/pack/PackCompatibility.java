package es.mrdino.flashlight.pack;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Resolves pack overlays before handing assets to a shared pack provider. */
public final class PackCompatibility {
    private PackCompatibility() { }

    public static int format(String minecraft) {
        return switch (minecraft) {
            case "1.21.4" -> 46;
            case "1.21.5" -> 55;
            case "1.21.6" -> 63;
            case "1.21.7", "1.21.8" -> 64;
            case "1.21.9", "1.21.10" -> 69;
            case "1.21.11" -> 75;
            case "26.1", "26.1.1", "26.1.2" -> 84;
            case "26.2" -> 88;
            case "26.3" -> 97;
            default -> throw new IllegalArgumentException("Unsupported Minecraft version: " + minecraft);
        };
    }

    public static int major(JsonElement value) {
        return value.isJsonArray() ? value.getAsJsonArray().get(0).getAsInt() : value.getAsInt();
    }

    public static byte[] forMinecraft(byte[] pack, String minecraft) throws IOException {
        int format = format(minecraft);
        Map<String, byte[]> source = ShaderComposer.read(pack);
        JsonObject metadata = JsonParser.parseString(new String(source.get("pack.mcmeta"), StandardCharsets.UTF_8)).getAsJsonObject();
        if (!metadata.has("overlays")) return pack;
        List<String> directories = new ArrayList<>(), selected = new ArrayList<>();
        for (var value : metadata.getAsJsonObject("overlays").getAsJsonArray("entries")) {
            var overlay = value.getAsJsonObject();
            String directory = overlay.get("directory").getAsString();
            if (!directory.matches("[a-z0-9_.-]+")) throw new IOException("Invalid overlay directory: " + directory);
            directories.add(directory + "/");
            if (format >= major(overlay.get("min_format")) && format <= major(overlay.get("max_format"))) selected.add(directory + "/");
        }
        Map<String, byte[]> result = new LinkedHashMap<>();
        for (var entry : source.entrySet()) {
            if (directories.stream().noneMatch(entry.getKey()::startsWith)) result.put(entry.getKey(), entry.getValue());
        }
        for (String directory : selected) {
            for (var entry : source.entrySet()) if (entry.getKey().startsWith(directory))
                result.put(entry.getKey().substring(directory.length()), entry.getValue());
        }
        metadata.remove("overlays");
        var info = metadata.getAsJsonObject("pack");
        info.addProperty("min_format", format);
        info.addProperty("max_format", format);
        if (format < 65) {
            info.addProperty("pack_format", format);
            info.addProperty("supported_formats", format);
        } else {
            info.remove("pack_format");
            info.remove("supported_formats");
        }
        result.put("pack.mcmeta", metadata.toString().getBytes(StandardCharsets.UTF_8));
        return ShaderComposer.write(result);
    }
}
