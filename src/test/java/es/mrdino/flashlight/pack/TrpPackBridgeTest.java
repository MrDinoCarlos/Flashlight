package es.mrdino.flashlight.pack;

import es.mrdino.flashlight.FlashlightPlugin;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.logging.Logger;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TrpPackBridgeTest {
    public interface TrpApi extends Plugin {
        byte[] combineResourcePackOverlay(Plugin owner,String name,byte[] bytes);
        boolean registerNexoResourcePackOverlay(Plugin owner,String name,byte[] bytes);
        String registerResourcePackOverlay(Plugin owner,String name,byte[] bytes);
        void unregisterResourcePackOverlay(Plugin owner);
        void resendResourcePack(Plugin owner,Player player);
    }
    @TempDir Path directory;

    @Test void sharesTrpsNexoOrEmbeddedProviderWithoutSendingACompetingPack() throws Exception {
        for(boolean nexoMode:new boolean[]{false,true}) {
            FlashlightPlugin plugin=mock(FlashlightPlugin.class); TrpApi trp=mock(TrpApi.class);
            Server server=mock(Server.class); PluginManager manager=mock(PluginManager.class);
            var config=new org.bukkit.configuration.file.YamlConfiguration();
            when(plugin.getConfig()).thenReturn(config); when(plugin.getServer()).thenReturn(server);
            when(plugin.settings()).thenReturn(es.mrdino.flashlight.FlashlightSettings.load(config));
            when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger()); when(plugin.getDataFolder()).thenReturn(directory.resolve("Flashlight").toFile());
            byte[] raw;
            try(var stream=getClass().getResourceAsStream("/pack/FlashLight-ResourcePack.zip")) { assertNotNull(stream); raw=stream.readAllBytes(); }
            when(plugin.getResource(anyString())).thenAnswer(ignored->new java.io.ByteArrayInputStream(raw));
            when(server.getPluginManager()).thenReturn(manager); when(manager.getPlugin("TRPServerEdition")).thenReturn(trp);
            when(trp.isEnabled()).thenReturn(true);
            Plugin nexo=mock(Plugin.class); when(nexo.isEnabled()).thenReturn(true); when(nexo.getDataFolder()).thenReturn(directory.resolve("Nexo").toFile());
            when(manager.getPlugin("Nexo")).thenReturn(nexoMode ? nexo : null);
            String item="assets/minecraft/shaders/core/rendertype_item_entity_translucent_cull.";
            byte[] base=ShaderComposer.write(Map.of(item+"vsh",Files.readAllBytes(Path.of("src/test/resources/compat/trp/item.vsh")),
                    item+"fsh",Files.readAllBytes(Path.of("src/test/resources/compat/trp/item.fsh"))));
            when(trp.combineResourcePackOverlay(eq(plugin),anyString(),any(byte[].class))).thenReturn(base);
            when(trp.registerNexoResourcePackOverlay(eq(plugin),anyString(),any(byte[].class))).thenReturn(nexoMode);
            when(trp.registerResourcePackOverlay(eq(plugin),anyString(),any(byte[].class))).thenReturn("http://localhost/trp/combined.zip");
            try(var packs=new PackService(plugin)) {
                var captured=ArgumentCaptor.forClass(byte[].class);
                if(nexoMode) verify(trp).registerNexoResourcePackOverlay(eq(plugin),anyString(),captured.capture());
                else verify(trp).registerResourcePackOverlay(eq(plugin),anyString(),captured.capture());
                var overlay=ShaderComposer.read(captured.getValue());
                assertTrue(new String(overlay.get(item+"vsh"),StandardCharsets.UTF_8).contains("trpStrobeMarker"));
                assertTrue(overlay.containsKey("assets/flashlight/items/flashlight_on.json"));
                assertTrue(overlay.containsKey("assets/minecraft/post_effect/entity_outline.json"));
                assertTrue(overlay.containsKey("assets/flashlight/items/beam/entity_control.json"));
                assertFalse(Files.exists(directory.resolve("Nexo/pack/template_packs/"+NexoPackInstaller.PACK_NAME)));
                Player player=mock(Player.class); packs.request(player,true); verifyNoInteractions(player);
                packs.request(player,false); verify(trp).resendResourcePack(plugin,player); verifyNoInteractions(player);
            }
            verify(trp).unregisterResourcePackOverlay(plugin);
        }
    }
}
