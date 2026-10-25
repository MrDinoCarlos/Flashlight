package es.mrdino.flashlight.pack;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.zip.ZipInputStream;
import com.google.gson.JsonParser;
import static org.junit.jupiter.api.Assertions.*;

class BundledPackTest {
    @Test void shipsTheProvidedFlashlightWithoutFakeArmsOrVanillaItemOverrides() throws Exception {
        try (var source = getClass().getResourceAsStream("/pack/FlashLight-ResourcePack.zip")) {
            assertNotNull(source);
            var paths = new HashSet<String>();
            try (var zip = new ZipInputStream(source)) {
                java.util.zip.ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    String name = entry.getName();
                    assertTrue(paths.add(name));
                    assertFalse(name.contains("/arm_"));
                    assertFalse(name.startsWith("assets/minecraft/items/"));
                    assertFalse(name.startsWith("assets/minecraft/models/"));
                    if (name.endsWith("flashlight_on.json") && name.contains("/models/")) {
                        String model = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                        assertTrue(model.contains("imported_on"));
                        assertFalse(model.contains("#skin"));
                    } else if (name.equals("assets/minecraft/post_effect/entity_outline.json")) {
                        var graph=JsonParser.parseString(new String(zip.readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();
                        assertEquals(5,graph.getAsJsonArray("passes").size());
                        var transport=graph.getAsJsonObject("targets").getAsJsonObject("flashlight_data");
                        assertEquals(27,transport.get("width").getAsInt()); assertEquals(160,transport.get("height").getAsInt());
                        for(var stage:graph.getAsJsonArray("passes"))
                            assertNotEquals("minecraft:main",stage.getAsJsonObject().get("output").getAsString(),
                                    "Outline post passes must never clear the scene's depth buffer");
                    } else if (name.endsWith(".json") && name.contains("/models/")) {
                        var model = JsonParser.parseString(new String(zip.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                        model.getAsJsonObject("textures").entrySet().forEach(texture -> {
                            String identifier = texture.getValue().getAsString();
                            String local = identifier.substring(identifier.indexOf(':') + 1);
                            assertTrue(local.startsWith("item/") || local.startsWith("block/"),
                                    "Minecraft's item atlas must load " + identifier);
                        });
                    }
                }
            }
            assertTrue(paths.contains("pack.mcmeta"));
            assertTrue(paths.contains("assets/flashlight/items/flashlight_on.json"));
            assertTrue(paths.contains("assets/minecraft/shaders/core/entity.vsh"));
            assertTrue(paths.contains("assets/minecraft/shaders/core/rendertype_item_entity_translucent_cull.fsh"));
            assertTrue(paths.contains("assets/flashlight/shaders/include/beam_fragment.glsl"));
            assertTrue(paths.contains("assets/flashlight/items/beam/volume_1_0_20.json"));
            assertTrue(paths.contains("assets/flashlight/items/beam/volume_0_both_20.json"));
            assertTrue(paths.contains("assets/flashlight/items/beam/surface_h_0_0.json"));
            assertTrue(paths.contains("assets/flashlight/shaders/include/beam_proxy.glsl"));
            assertFalse(paths.contains("assets/flashlight/textures/item/beam/volume_1_0_20.png"),"No duplicate legacy volume sprites");
            assertTrue(paths.contains("assets/flashlight/textures/item/beam/impact.png"));
            assertTrue(paths.contains("assets/flashlight/flashlight_pose.json"));
            assertTrue(paths.contains("assets/flashlight/items/beam/entity_control.json"));
            assertTrue(paths.contains("assets/flashlight/shaders/post/spotlight.fsh"));
            assertFalse(paths.contains("assets/flashlight/shaders/post/copy_scene.fsh"));
            assertFalse(paths.contains("assets/flashlight/shaders/post/clean_control.fsh"));
            assertTrue(paths.contains("assets/flashlight/shaders/post/control_copy.vsh"));
            assertTrue(paths.contains("assets/flashlight/shaders/post/outline.fsh"));
            assertTrue(paths.contains("assets/minecraft/post_effect/entity_outline.json"));
            assertTrue(paths.stream().noneMatch(name -> name.startsWith("shaders/") || name.endsWith(".class")));
        }
    }
}
