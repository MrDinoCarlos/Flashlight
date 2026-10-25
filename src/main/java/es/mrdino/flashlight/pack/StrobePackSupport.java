package es.mrdino.flashlight.pack;

import java.io.IOException;
import org.bukkit.plugin.Plugin;

/** StrobeLights standalone: retain its exact 1.21.4 item/carrier shader in our appended pack. */
final class StrobePackSupport {
    static byte[] compose(Plugin strobe,byte[] flashlight) throws IOException {
        return compose(strobe, flashlight, "1.21.4");
    }
    static byte[] compose(Plugin strobe,byte[] flashlight,String minecraft) throws IOException {
        String profile=switch(minecraft) {
            case "1.21.7","1.21.8" -> "1.21.6";
            case "1.21.10","1.21.11" -> "1.21.9";
            case "26.1.1","26.1.2" -> "26.1";
            default -> minecraft;
        };
        var candidate=strobe.getResource("embedded/StrobeLights-ResourcePack-"+minecraft+".zip");
        if(candidate==null && !profile.equals(minecraft)) candidate=strobe.getResource("embedded/StrobeLights-ResourcePack-"+profile+".zip");
        try(var source=candidate) {
            if(source==null) throw new IOException("StrobeLights has no Minecraft "+minecraft+" embedded pack; install its matching version");
            byte[] host=source.readAllBytes();
            var result=ShaderComposer.read(ShaderComposer.overlay(flashlight,host));
            // The host's functions are private to that shader. Keep their exact include
            // with it, even if another pack also provides minecraft:utils.glsl.
            var entries=ShaderComposer.read(host);
            String utils="assets/minecraft/shaders/include/utils.glsl";
            if(entries.containsKey(utils)) {
                result.put("assets/flashlight/shaders/include/strobe_utils.glsl",entries.get(utils));
                for(var entry:result.entrySet()) if(entry.getKey().startsWith("assets/minecraft/shaders/core/")) {
                    String shader=new String(entry.getValue(),java.nio.charset.StandardCharsets.UTF_8);
                    entry.setValue(shader.replace("<minecraft:utils.glsl>","<flashlight:strobe_utils.glsl>")
                            .replace("<utils.glsl>","<flashlight:strobe_utils.glsl>").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
            }
            return ShaderComposer.write(result);
        }
    }
}
