package es.mrdino.flashlight.pack;

import java.io.IOException;
import java.util.Locale;

/** Native fast shaders stay the default; the compact pair is selected independently. */
public final class RendererProfiles {
    public enum Mode { AUTO, FAST, COMPATIBLE }
    private static final String POST="assets/flashlight/shaders/post/";
    private RendererProfiles() { }

    public static Mode mode(String value) {
        try { return Mode.valueOf(value.trim().toUpperCase(Locale.ROOT)); }
        catch(IllegalArgumentException invalid) {
            throw new IllegalArgumentException("resource-pack.renderer must be AUTO, FAST or COMPATIBLE",invalid);
        }
    }

    public static void validate(org.bukkit.configuration.file.FileConfiguration config) {
        mode(config.getString("resource-pack.renderer","AUTO"));
        String url=config.getString("resource-pack.compatibility-url","").trim();
        if(url.isEmpty()) return;
        var uri=java.net.URI.create(url);
        if(uri.getHost()==null || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())))
            throw new IllegalArgumentException("resource-pack.compatibility-url must be a direct HTTP(S) ZIP URL");
    }

    public static byte[] compatible(byte[] pack) throws IOException {
        var entries=ShaderComposer.read(pack);
        for(String stage:new String[]{"vsh","fsh"}) {
            if(!entries.containsKey(POST+"spotlight_compat."+stage))
                throw new IOException("The compact renderer is missing: "+stage);
        }
        for(String path:java.util.List.copyOf(entries.keySet())) {
            for(String stage:new String[]{"vsh","fsh"}) {
                String suffix=POST+"spotlight_compat."+stage;
                if(path.equals(suffix) || path.endsWith("/"+suffix))
                    entries.put(path.substring(0,path.length()-suffix.length())+POST+"spotlight."+stage,entries.get(path));
            }
        }
        return ShaderComposer.write(entries);
    }
}
