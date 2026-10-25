package es.mrdino.flashlight.pack;

import com.google.gson.JsonParser;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.zip.ZipInputStream;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/** Installs only FlashLight's ZIP/template; Nexo remains responsible for hosting and delivery. */
final class NexoPackInstaller {
    static final String PACK_NAME="FlashLight-ResourcePack.zip",TEMPLATE="flashlight";
    private static final Pattern VERSION=Pattern.compile(".*?(\\d+)\\.(\\d+).*");
    record Result(Path file,boolean multipack,boolean changed) { }

    static boolean multipack(String version,String configured) {
        String mode=configured.trim().toUpperCase(Locale.ROOT);
        if(mode.equals("MERGED")) return false;
        if(!mode.equals("AUTO") && !mode.equals("MULTIPACK"))
            throw new IllegalArgumentException("resource-pack.nexo.integration must be AUTO, MULTIPACK or MERGED");
        var match=VERSION.matcher(version);
        boolean supported=match.matches() && (Integer.parseInt(match.group(1))>1
                || Integer.parseInt(match.group(1))==1 && Integer.parseInt(match.group(2))>=27);
        if(mode.equals("MULTIPACK") && !supported)
            throw new IllegalArgumentException("Nexo MultiPack requires Nexo 1.27 or newer; select MERGED for older versions");
        return supported;
    }

    static Result install(Path data,String version,String mode,byte[] pack) throws IOException {
        validate(pack);
        boolean multi=multipack(version,mode);
        Path root=data.toAbsolutePath().normalize();
        Path configuration=root.resolve("multipack.yml");
        YamlConfiguration yaml=new YamlConfiguration(); yaml.options().parseComments(true);
        String original=Files.exists(configuration) ? Files.readString(configuration) : null;
        boolean yamlChanged=false;
        if(multi || original!=null) {
            try { if(original!=null) yaml.loadFromString(original); }
            catch(InvalidConfigurationException failure) { throw new IOException("Nexo multipack.yml is invalid; existing pack/config preserved",failure); }
        }
        String entry="templates."+TEMPLATE;
        if(multi) {
            if(yaml.contains("templates") && !yaml.isConfigurationSection("templates")
                    || yaml.contains(entry) && !yaml.isConfigurationSection(entry))
                throw new IOException("Nexo template configuration is not a section; it was not overwritten");
            String existing=yaml.getString(entry+".path");
            if(existing!=null && !PACK_NAME.equals(existing))
                throw new IOException("Nexo template "+TEMPLATE+" already points to another pack; it was not overwritten");
            if(!yaml.contains(entry+".path")) { yaml.set(entry+".path",PACK_NAME); yamlChanged=true; }
            // Existing administrator choices remain authoritative on later reloads.
            if(!yaml.contains(entry+".required")) { yaml.set(entry+".required",true); yamlChanged=true; }
            if(!yaml.contains(entry+".default")) { yaml.set(entry+".default",true); yamlChanged=true; }
        } else if(PACK_NAME.equals(yaml.getString(entry+".path"))) {
            yaml.set(entry,null); yamlChanged=true;
        }
        Path target=root.resolve(multi ? "pack/template_packs/"+PACK_NAME : "pack/external_packs/"+PACK_NAME);
        byte[] previous=Files.isRegularFile(target) ? Files.readAllBytes(target) : null;
        boolean changed=!java.util.Arrays.equals(pack,previous);
        if(yamlChanged) verifyUnchanged(configuration,original);
        if(changed) {
            backup(target,target.resolveSibling(PACK_NAME+".before-flashlight.bak"));
            atomicWrite(target,pack);
        }
        if(yamlChanged) {
            try {
                verifyUnchanged(configuration,original);
                backup(configuration,root.resolve("multipack.yml.before-flashlight.bak"));
                atomicWrite(configuration,yaml.saveToString().getBytes(StandardCharsets.UTF_8));
            } catch(IOException failure) {
                // Keep the previous input usable when its template registration cannot be committed.
                if(changed && java.util.Arrays.equals(pack,Files.readAllBytes(target))) {
                    if(previous==null) Files.delete(target); else atomicWrite(target,previous);
                }
                throw failure;
            }
        }
        // A layout switch must not leave two FlashLight packs in Nexo's inputs.
        Path stale=root.resolve(multi ? "pack/external_packs/"+PACK_NAME : "pack/template_packs/"+PACK_NAME);
        if(Files.isRegularFile(stale)) {
            // Remove only a file positively identified as this plugin's resource pack.
            boolean owned=true;
            try { validate(Files.readAllBytes(stale)); }
            catch(IOException ignored) { owned=false; /* An unrelated administrator file is retained. */ }
            if(owned) { Files.delete(stale); changed=true; }
        }
        return new Result(target,multi,changed || yamlChanged);
    }

    private static void verifyUnchanged(Path file,String original) throws IOException {
        String current=Files.exists(file) ? Files.readString(file) : null;
        if(!java.util.Objects.equals(original,current))
            throw new IOException("Nexo multipack.yml changed during installation; existing configuration preserved");
    }

    static void uninstall(Path data) throws IOException {
        Path root=data.toAbsolutePath().normalize(),file=root.resolve("multipack.yml");
        if(Files.isRegularFile(file)) {
            String original=Files.readString(file); var yaml=new YamlConfiguration(); yaml.options().parseComments(true);
            try { yaml.loadFromString(original); }
            catch(InvalidConfigurationException failure) { throw new IOException("Nexo multipack.yml is invalid; cleanup cancelled",failure); }
            if(PACK_NAME.equals(yaml.getString("templates."+TEMPLATE+".path"))) {
                yaml.set("templates."+TEMPLATE,null); verifyUnchanged(file,original);
                backup(file,root.resolve("multipack.yml.before-flashlight.bak"));
                atomicWrite(file,yaml.saveToString().getBytes(StandardCharsets.UTF_8));
            }
        }
        for(String directory:new String[]{"external_packs","template_packs"}) {
            Path pack=root.resolve("pack/"+directory+"/"+PACK_NAME);
            if(!Files.isRegularFile(pack)) continue;
            try { validate(Files.readAllBytes(pack)); }
            catch(IOException unrelated) { continue; }
            Files.delete(pack);
        }
    }

    private static void backup(Path file,Path backup) throws IOException {
        if(Files.isRegularFile(file) && !Files.exists(backup)) Files.copy(file,backup);
    }

    static void atomicWrite(Path target,byte[] bytes) throws IOException {
        Files.createDirectories(target.getParent());
        Path temporary=Files.createTempFile(target.getParent(),".flashlight-",".tmp");
        try {
            Files.write(temporary,bytes);
            Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }

    private static void validate(byte[] pack) throws IOException {
        var entries=new HashSet<String>(); long size=0;
        try(var zip=new ZipInputStream(new ByteArrayInputStream(pack))) {
            java.util.zip.ZipEntry entry;
            while((entry=zip.getNextEntry())!=null) {
                String name=entry.getName();
                if(name.startsWith("/") || name.contains("\\") || name.contains("../") || !entries.add(name))
                    throw new IOException("Invalid FlashLight ZIP entry: "+name);
                byte[] bytes=zip.readNBytes(8*1024*1024+1); size+=bytes.length;
                if(bytes.length>8*1024*1024 || size>128*1024*1024) throw new IOException("Invalid FlashLight ZIP size");
                if(name.equals("pack.mcmeta")) {
                    try {
                        var metadata=JsonParser.parseString(new String(bytes,StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("pack");
                        int format=PackCompatibility.major(metadata.has("min_format") ? metadata.get("min_format") : metadata.get("pack_format"));
                        if(!java.util.Set.of(46,55,63,64,69,75,84,88,97).contains(format))
                            throw new IOException("Unsupported FlashLight resource pack format: " + format);
                    } catch(RuntimeException failure) { throw new IOException("Invalid FlashLight pack.mcmeta",failure); }
                }
                zip.closeEntry();
            }
        }
        for(String required:new String[]{"pack.mcmeta","assets/flashlight/items/flashlight_on.json",
                "assets/flashlight/shaders/include/beam_fragment.glsl","assets/minecraft/shaders/core/entity.vsh"})
            if(!entries.contains(required)) throw new IOException("FlashLight ZIP is missing "+required);
    }
}
