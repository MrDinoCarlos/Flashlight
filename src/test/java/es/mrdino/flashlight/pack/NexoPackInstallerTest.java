package es.mrdino.flashlight.pack;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class NexoPackInstallerTest {
    @TempDir Path directory;
    private byte[] pack() throws IOException {
        try(var stream=getClass().getResourceAsStream("/pack/FlashLight-ResourcePack.zip")) {
            assertNotNull(stream); return stream.readAllBytes();
        }
    }

    @Test void installsTheExactBundledZipAndRegistersADefaultRequiredTemplateOnModernNexo() throws Exception {
        byte[] bytes=pack();
        var result=NexoPackInstaller.install(directory,"1.28.0","AUTO",bytes);
        assertTrue(result.changed()); assertTrue(result.multipack());
        assertEquals(directory.resolve("pack/template_packs/"+NexoPackInstaller.PACK_NAME),result.file());
        assertArrayEquals(bytes,Files.readAllBytes(result.file()));
        var yaml=YamlConfiguration.loadConfiguration(directory.resolve("multipack.yml").toFile());
        String entry="templates."+NexoPackInstaller.TEMPLATE;
        assertEquals(NexoPackInstaller.PACK_NAME,yaml.getString(entry+".path"));
        assertTrue(yaml.getBoolean(entry+".required")); assertTrue(yaml.getBoolean(entry+".default"));
        var time=Files.getLastModifiedTime(result.file());
        var yamlTime=Files.getLastModifiedTime(directory.resolve("multipack.yml"));
        assertFalse(NexoPackInstaller.install(directory,"1.28.0","AUTO",bytes).changed());
        assertEquals(time,Files.getLastModifiedTime(result.file()));
        assertEquals(yamlTime,Files.getLastModifiedTime(directory.resolve("multipack.yml")));
    }

    @Test void preservesOtherTemplatesCommentsAdministratorChoicesAndAnExactOriginalBackup() throws Exception {
        Path file=directory.resolve("multipack.yml");
        String old="# My Nexo packs\ntemplates:\n  another_plugin:\n    path: Another.zip\n    required: false # Keep optional\n  flashlight:\n    path: FlashLight-ResourcePack.zip\n    default: false\ncustom: keep\n";
        Files.writeString(file,old);
        NexoPackInstaller.install(directory,"1.27.1","AUTO",pack());
        var yaml=YamlConfiguration.loadConfiguration(file.toFile());
        assertFalse(yaml.getBoolean("templates.flashlight.default"));
        assertTrue(yaml.getBoolean("templates.flashlight.required"));
        assertFalse(yaml.getBoolean("templates.another_plugin.required"));
        assertEquals("Another.zip",yaml.getString("templates.another_plugin.path"));
        assertEquals("keep",yaml.getString("custom"));
        assertTrue(Files.readString(file).contains("My Nexo packs"));
        assertTrue(Files.readString(file).contains("Keep optional"));
        assertEquals(old,Files.readString(directory.resolve("multipack.yml.before-flashlight.bak")));
        NexoPackInstaller.install(directory,"1.28.0","AUTO",pack());
        assertEquals(old,Files.readString(directory.resolve("multipack.yml.before-flashlight.bak")));
    }

    @Test void legacyNexoReceivesAMergedExternalPackWithoutAMultipackConfiguration() throws Exception {
        var result=NexoPackInstaller.install(directory,"1.26.2","AUTO",pack());
        assertFalse(result.multipack()); assertTrue(result.changed());
        assertEquals(directory.resolve("pack/external_packs/"+NexoPackInstaller.PACK_NAME),result.file());
        assertFalse(Files.exists(directory.resolve("multipack.yml")));
        assertArrayEquals(pack(),Files.readAllBytes(result.file()));
    }

    @Test void switchingLayoutsRemovesOnlyOurFormerInputAndRegistration() throws Exception {
        Files.writeString(directory.resolve("multipack.yml"),"templates:\n  another_plugin:\n    path: Another.zip\n");
        var multi=NexoPackInstaller.install(directory,"1.28.0","AUTO",pack());
        Path unrelated=directory.resolve("pack/template_packs/Another.zip"); Files.writeString(unrelated,"untouched");
        var merged=NexoPackInstaller.install(directory,"1.28.0","MERGED",pack());
        assertFalse(Files.exists(multi.file())); assertTrue(Files.exists(merged.file()));
        var yaml=YamlConfiguration.loadConfiguration(directory.resolve("multipack.yml").toFile());
        assertFalse(yaml.contains("templates.flashlight"));
        assertEquals("Another.zip",yaml.getString("templates.another_plugin.path"));
        assertEquals("untouched",Files.readString(unrelated));
        assertTrue(NexoPackInstaller.install(directory,"1.28.0","AUTO",pack()).changed());
        assertFalse(Files.exists(merged.file())); assertTrue(Files.exists(multi.file()));
        assertEquals("untouched",Files.readString(unrelated));
    }

    @Test void invalidInputOrYamlNeverReplacesAWorkingPack() throws Exception {
        var existing=NexoPackInstaller.install(directory,"1.28.0","AUTO",pack());
        byte[] working=Files.readAllBytes(existing.file());
        assertThrows(IOException.class,()->NexoPackInstaller.install(directory,"1.28.0","AUTO",new byte[]{1,2,3}));
        assertArrayEquals(working,Files.readAllBytes(existing.file()));
        Path yaml=directory.resolve("multipack.yml"); Files.writeString(yaml,"templates: [broken\n");
        assertThrows(IOException.class,()->NexoPackInstaller.install(directory,"1.28.0","AUTO",pack()));
        assertEquals("templates: [broken\n",Files.readString(yaml));
        assertArrayEquals(working,Files.readAllBytes(existing.file()));
    }

    @Test void conflictingTemplatePathsAndScalarSectionsAreNeverOverwritten() throws Exception {
        Path file=directory.resolve("multipack.yml");
        for(String original:new String[]{"templates: custom-scalar\n","templates:\n  flashlight: custom-scalar\n",
                "templates:\n  flashlight:\n    path: MyOwnPack.zip\n"}) {
            Files.writeString(file,original);
            assertThrows(IOException.class,()->NexoPackInstaller.install(directory,"1.28.0","AUTO",pack()));
            assertEquals(original,Files.readString(file));
            assertFalse(Files.exists(directory.resolve("pack/template_packs/"+NexoPackInstaller.PACK_NAME)));
        }
    }

    @Test void modeSelectionSupportsCurrentAndLegacyVersionStrings() {
        assertTrue(NexoPackInstaller.multipack("1.27.0-SNAPSHOT","AUTO"));
        assertTrue(NexoPackInstaller.multipack("2.0.0","AUTO"));
        assertFalse(NexoPackInstaller.multipack("1.9.4","AUTO"));
        assertFalse(NexoPackInstaller.multipack("1.28.0","MERGED"));
        assertThrows(IllegalArgumentException.class,()->NexoPackInstaller.multipack("1.26.0","MULTIPACK"));
        assertThrows(IllegalArgumentException.class,()->NexoPackInstaller.multipack("1.28.0","INVALID"));
    }
}
