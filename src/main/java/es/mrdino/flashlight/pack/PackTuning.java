package es.mrdino.flashlight.pack;

import es.mrdino.flashlight.FlashlightSettings;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;

/** Carries configurable glare in four tiny data sprites inside the normal pack. */
final class PackTuning {
    private PackTuning() { }
    static byte[] apply(byte[] pack,FlashlightSettings.Glare glare) throws IOException {
        var entries=ShaderComposer.read(pack);
        int strength=glare.enabled() ? (int)Math.round(glare.strength()*255) : 0;
        int color=0xff000000 | strength<<16 | glare.maxDistance()<<8;
        boolean changed=false;
        for(String model:new String[]{"entity_control","entity_control_right","entity_control_left","shadow_control"}) {
            String path="assets/flashlight/textures/item/beam/"+model+".png";
            byte[] source=entries.get(path);
            if(source==null) throw new IOException("The resource pack is missing "+path);
            var image=ImageIO.read(new ByteArrayInputStream(source));
            if(image==null) throw new IOException("Invalid control data sprite: "+path);
            if(image.getRGB(8,8)==color) continue;
            for(int y=4;y<12;y++) for(int x=4;x<12;x++) image.setRGB(x,y,color);
            var bytes=new ByteArrayOutputStream(); ImageIO.write(image,"PNG",bytes);
            entries.put(path,bytes.toByteArray()); changed=true;
        }
        return changed ? ShaderComposer.write(entries) : pack;
    }
}
