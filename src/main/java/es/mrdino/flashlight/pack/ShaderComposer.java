package es.mrdino.flashlight.pack;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Adds a separate flashlight branch while keeping the host's TRP/Strobe renderer intact. */
public final class ShaderComposer {
    private static final String CORE="assets/minecraft/shaders/core/";
    private static final String HOOK="// FlashLight combined shader v1";
    private static final Pattern MAIN=Pattern.compile("\\bvoid\\s+main\\s*\\(\\s*(?:void\\s*)?\\)\\s*\\{");
    private ShaderComposer() { }

    static byte[] assetsOnly(byte[] pack) throws IOException {
        Map<String,byte[]> entries=read(pack);
        entries.keySet().removeIf(path->path.startsWith(CORE));
        return write(entries);
    }

    static byte[] overlay(byte[] flashlight,byte[] host) throws IOException {
        Map<String,byte[]> entries=read(flashlight),base=read(host);
        for(var entry:entries.entrySet()) {
            String path=entry.getKey();
            if(path.startsWith(CORE) && base.containsKey(path) && (path.endsWith(".vsh") || path.endsWith(".fsh")))
                entry.setValue(compose(new String(base.get(path),StandardCharsets.UTF_8),
                        new String(entry.getValue(),StandardCharsets.UTF_8),path.endsWith(".vsh")).getBytes(StandardCharsets.UTF_8));
        }
        return write(entries);
    }

    public static String compose(String host,boolean vertex) throws IOException {
        if(host.contains(HOOK)) return host;
        var main=MAIN.matcher(host);
        if(!main.find()) throw new IOException("The host shader has no main() entry point");
        String function=vertex ? "flashlightHostVertex" : "flashlightHostFragment";
        String declarations=HOOK+"\n";
        boolean trp=host.contains("trpMarkerFlag") && host.contains("trpBeamColor");
        if(trp) declarations+="#define FLASHLIGHT_TRP_HOST\n";
        // TRP's audited legacy sources use the pre-1.21.3 fog overload.
        // Keep that call and its transform semantics on the 1.21.4 include API.
        if(vertex && host.contains("fog_distance(ModelViewMat,") && !host.contains("float fog_distance(mat4"))
            declarations+="float fog_distance(mat4 transform, vec3 position, int shape) { return fog_distance((transform * vec4(position, 1.0)).xyz, shape); }\n";
        for(String[] uniform:new String[][]{{"sampler2D","Sampler0"},{"mat4","ProjMat"}})
            if(!Pattern.compile("\\buniform\\s+"+uniform[0]+"\\s+"+uniform[1]+"\\b").matcher(host).find())
                declarations+="uniform "+uniform[0]+" "+uniform[1]+";\n";
        declarations+="#moj_import <flashlight:beam_"+(vertex ? "vertex" : "fragment")+".glsl>\n";
        String combined=host.substring(0,main.start())+declarations+"void "+function+"() {"+host.substring(main.end());
        return combined+(vertex ? "\nvoid main() {\n    flashlightVertex();\n    if (nfMarkerFlag > 0.5) {\n        gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);\n        vertexDistance = fog_distance(Position, FogShape);\n        texCoord0 = UV0;\n        return;\n    }\n    flashlightHostVertex();\n}\n"
                : "\nvoid main() {\n    gl_FragDepth = gl_FragCoord.z;\n    if (flashlightFragment(fragColor)) return;\n    flashlightHostFragment();\n}\n");
    }

    private static String compose(String host,String template,boolean vertex) throws IOException {
        String hook="// FlashLight versioned renderer hook v2";
        if(!template.contains(hook)) return compose(host,vertex);
        if(host.contains(hook)) return host;
        boolean modern=template.contains("flashlightVertexDistance");
        if(modern && !host.contains("dynamictransforms.glsl"))
            throw new IOException("The shared pack provider's shader targets an older Minecraft renderer; install its matching server version");
        var main=MAIN.matcher(host);
        if(!main.find()) throw new IOException("The host shader has no main() entry point");
        String function=vertex ? "flashlightVanillaVertex" : "flashlightVanillaFragment";
        int start=template.indexOf(hook),end=template.indexOf("void "+function+"()",start);
        int wrapper=template.lastIndexOf("\nvoid main()");
        if(end<0 || wrapper<0) throw new IOException("Invalid versioned FlashLight shader template");
        String declarations=template.substring(start,end);
        if(host.contains("trpMarkerFlag") && host.contains("trpBeamColor")) declarations="#define FLASHLIGHT_TRP_HOST\n"+declarations;
        for(String[] uniform:new String[][]{{"sampler2D","Sampler0"},{"mat4","ProjMat"}})
            if(Pattern.compile("\\buniform\\s+"+uniform[0]+"\\s+"+uniform[1]+"\\b").matcher(host).find())
                declarations=declarations.replace("uniform "+uniform[0]+" "+uniform[1]+";\n","");
        if(vertex && host.contains("fog_distance(ModelViewMat,") && !host.contains("float fog_distance(mat4"))
            declarations+="float fog_distance(mat4 transform, vec3 position, int shape) { return fog_distance((transform * vec4(position, 1.0)).xyz, shape); }\n";
        if(host.contains("projection.glsl")) declarations=declarations.replaceAll("(?m)^#(?:moj_import|include) <minecraft:projection\\.glsl>\\R","");
        return host.substring(0,main.start())+declarations+"void "+function+"() {"+host.substring(main.end())+template.substring(wrapper);
    }

    static Map<String,byte[]> read(byte[] bytes) throws IOException {
        Map<String,byte[]> entries=new LinkedHashMap<>(); long total=0;
        try(var zip=new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while((entry=zip.getNextEntry())!=null) {
                String path=entry.getName();
                if(path.startsWith("/") || path.contains("\\") || path.contains("../") || entries.containsKey(path)) throw new IOException("Invalid pack entry: "+path);
                if(!entry.isDirectory()) {
                    byte[] content=zip.readNBytes(32*1024*1024+1); total+=content.length;
                    if(content.length>32*1024*1024 || total>256*1024*1024) throw new IOException("Resource pack size exceeded");
                    entries.put(path,content);
                }
                zip.closeEntry();
            }
        }
        return entries;
    }

    static byte[] write(Map<String,byte[]> entries) throws IOException {
        var bytes=new ByteArrayOutputStream();
        try(var zip=new ZipOutputStream(bytes)) {
            for(var entry:new java.util.TreeMap<>(entries).entrySet()) {
                ZipEntry file=new ZipEntry(entry.getKey()); file.setTime(0L);
                zip.putNextEntry(file); zip.write(entry.getValue()); zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
}
