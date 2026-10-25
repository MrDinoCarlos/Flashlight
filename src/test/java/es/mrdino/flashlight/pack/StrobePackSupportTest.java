package es.mrdino.flashlight.pack;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StrobePackSupportTest {
    @Test void preservesStrobeCarrierShaderAndUsesItsExactPrivateInclude() throws Exception {
        String prefix="assets/minecraft/shaders/";
        byte[] host=ShaderComposer.write(Map.of(prefix+"core/rendertype_item_entity_translucent_cull.vsh",Files.readAllBytes(Path.of("src/test/resources/compat/strobe/item.vsh")),
                prefix+"core/rendertype_item_entity_translucent_cull.fsh",Files.readAllBytes(Path.of("src/test/resources/compat/strobe/item.fsh")),
                prefix+"include/utils.glsl",Files.readAllBytes(Path.of("src/test/resources/compat/strobe/utils.glsl"))));
        Plugin strobe=mock(Plugin.class);
        when(strobe.getResource("embedded/StrobeLights-ResourcePack-1.21.4.zip")).thenReturn(new ByteArrayInputStream(host));
        byte[] flashlight;
        try(var input=getClass().getResourceAsStream("/pack/FlashLight-ResourcePack.zip")) { assertNotNull(input); flashlight=input.readAllBytes(); }
        var entries=ShaderComposer.read(StrobePackSupport.compose(strobe,flashlight));
        String vertex=new String(entries.get(prefix+"core/rendertype_item_entity_translucent_cull.vsh"),StandardCharsets.UTF_8);
        assertTrue(vertex.contains("isSourceLight")); assertTrue(vertex.contains("flashlightVertex"));
        assertTrue(vertex.contains("<flashlight:strobe_utils.glsl>"));
        assertArrayEquals(Files.readAllBytes(Path.of("src/test/resources/compat/strobe/utils.glsl")),entries.get("assets/flashlight/shaders/include/strobe_utils.glsl"));
        assertFalse(entries.containsKey("assets/minecraft/shaders/post/transparency.json"));
    }
}
