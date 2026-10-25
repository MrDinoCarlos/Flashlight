package es.mrdino.flashlight.pack;

import es.mrdino.flashlight.FlashlightSettings;
import java.io.ByteArrayInputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PackTuningTest {
    @Test void glareOptionsReachTheGpuDataSpritesWithoutChangingModelsOrShaders() throws Exception {
        byte[] original;
        try(var input=getClass().getResourceAsStream("/pack/FlashLight-ResourcePack.zip")) { assertNotNull(input); original=input.readAllBytes(); }
        var before=ShaderComposer.read(original);
        byte[] tuned=PackTuning.apply(original,new FlashlightSettings.Glare(true,.3,7));
        var after=ShaderComposer.read(tuned);
        for(String name:new String[]{"entity_control","entity_control_right","entity_control_left","shadow_control"}) {
            var image=ImageIO.read(new ByteArrayInputStream(after.get("assets/flashlight/textures/item/beam/"+name+".png")));
            assertEquals(77,(image.getRGB(8,8)>>>16)&255); assertEquals(7,(image.getRGB(8,8)>>>8)&255);
            assertArrayEquals(before.get("assets/flashlight/models/beam/"+name+".json"),after.get("assets/flashlight/models/beam/"+name+".json"));
        }
        assertArrayEquals(tuned,PackTuning.apply(tuned,new FlashlightSettings.Glare(true,.3,7)),"Unchanged options cannot trigger repeated shared-pack rebuilds");
        var disabled=ShaderComposer.read(PackTuning.apply(tuned,new FlashlightSettings.Glare(false,.3,7)));
        var image=ImageIO.read(new ByteArrayInputStream(disabled.get("assets/flashlight/textures/item/beam/entity_control.png")));
        assertEquals(0,(image.getRGB(8,8)>>>16)&255);
        assertArrayEquals(before.get("assets/flashlight/shaders/post/spotlight.fsh"),after.get("assets/flashlight/shaders/post/spotlight.fsh"));
    }
}
