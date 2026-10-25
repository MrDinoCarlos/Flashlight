package es.mrdino.flashlight.pack;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ShaderComposerTest {
    private static final String ITEM="assets/minecraft/shaders/core/rendertype_item_entity_translucent_cull.";

    @Test void composesActualTrpAndStrobeShadersWithoutRemovingEitherRenderer() throws Exception {
        for(String host:new String[]{"trp","strobe"}) for(String type:new String[]{"vsh","fsh"}) {
            String original=Files.readString(Path.of("src/test/resources/compat",host,"item."+type));
            String patched=ShaderComposer.compose(original,type.equals("vsh"));
            assertTrue(patched.contains("#moj_import <flashlight:beam_"));
            assertTrue(patched.contains("flashlightHost"));
            assertTrue(patched.length()>original.length());
            assertEquals(patched,ShaderComposer.compose(patched,type.equals("vsh")),"Composition is idempotent");
            for(String line:original.lines().toList()) if(!line.startsWith("void main()")) assertTrue(patched.contains(line),"Original host line preserved: "+line);
            if(host.equals("trp")) {
                assertTrue(patched.contains("trpMarkerFlag")); assertTrue(patched.contains("trpStrobeMarker"));
                assertTrue(patched.contains("#define FLASHLIGHT_TRP_HOST"));
            }
        }
    }

    @Test void theIndependentOutlinePassDoesNotReplaceTheHostsTransparencyGraphOrTextures() throws Exception {
        byte[] flashlight;
        try(var stream=getClass().getResourceAsStream("/pack/FlashLight-ResourcePack.zip")) { assertNotNull(stream); flashlight=stream.readAllBytes(); }
        byte[] host=ShaderComposer.write(Map.of(ITEM+"vsh",Files.readAllBytes(Path.of("src/test/resources/compat/trp/item.vsh")),
                ITEM+"fsh",Files.readAllBytes(Path.of("src/test/resources/compat/trp/item.fsh")),
                "assets/minecraft/post_effect/transparency.json","original graph".getBytes(StandardCharsets.UTF_8),
                "assets/trp/textures/beam.png",new byte[]{1,2,3}));
        var assets=ShaderComposer.read(ShaderComposer.assetsOnly(flashlight));
        assertTrue(assets.keySet().stream().noneMatch(path->path.startsWith("assets/minecraft/shaders/core/")));
        var overlay=ShaderComposer.read(ShaderComposer.overlay(flashlight,host));
        assertTrue(new String(overlay.get(ITEM+"vsh"),StandardCharsets.UTF_8).contains("trpIsModernStrobeCarrierTexel"));
        assertTrue(overlay.containsKey("assets/flashlight/items/flashlight_on.json"));
        assertFalse(overlay.containsKey("assets/minecraft/post_effect/transparency.json"));
        assertTrue(overlay.containsKey("assets/minecraft/post_effect/entity_outline.json"));
        assertTrue(overlay.containsKey("assets/minecraft/shaders/core/rendertype_outline.vsh"));
        assertTrue(overlay.containsKey("assets/flashlight/shaders/post/spotlight.fsh"));
        assertFalse(overlay.containsKey("assets/trp/textures/beam.png"));
        assertTrue(overlay.keySet().stream().noneMatch(path->path.startsWith("assets/minecraft/items/") || path.startsWith("assets/minecraft/models/")));
    }
}
