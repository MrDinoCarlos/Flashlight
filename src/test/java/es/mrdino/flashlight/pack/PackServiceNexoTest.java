package es.mrdino.flashlight.pack;

import es.mrdino.flashlight.FlashlightPlugin;
import es.mrdino.flashlight.FlashlightSettings;
import io.papermc.paper.plugin.configuration.PluginMeta;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import org.bukkit.Server;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PackServiceNexoTest {
    public interface NexoApi extends Plugin { Generator getPackGenerator(); }
    public static class Generator {
        final CompletableFuture<Void> future=new CompletableFuture<>();
        public CompletableFuture<Void> getPackGenFuture() { return future; }
    }
    @TempDir Path directory;
    private FlashlightPlugin plugin;
    private NexoApi nexo;
    private Server server;
    private PluginManager plugins;
    private ConsoleCommandSender console;
    private YamlConfiguration config;
    private final ArrayDeque<Runnable> tasks=new ArrayDeque<>();

    @BeforeEach void setup() throws Exception {
        plugin=mock(FlashlightPlugin.class); nexo=mock(NexoApi.class);
        server=mock(Server.class); plugins=mock(PluginManager.class);
        console=mock(ConsoleCommandSender.class); BukkitScheduler scheduler=mock(BukkitScheduler.class);
        config=new YamlConfiguration(); config.set("resource-pack.url","https://example.com/flashlight.zip");
        when(plugin.getConfig()).thenReturn(config); when(plugin.getServer()).thenReturn(server);
        when(plugin.getDataFolder()).thenReturn(directory.resolve("Flashlight").toFile());
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger()); when(plugin.isEnabled()).thenReturn(true);
        when(plugin.settings()).thenAnswer(ignored->FlashlightSettings.load(config));
        byte[] bytes;
        try(var stream=getClass().getResourceAsStream("/pack/FlashLight-ResourcePack.zip")) { assertNotNull(stream); bytes=stream.readAllBytes(); }
        when(plugin.getResource("pack/FlashLight-ResourcePack.zip")).thenAnswer(ignored->new ByteArrayInputStream(bytes));
        when(server.getPluginManager()).thenReturn(plugins); when(server.getScheduler()).thenReturn(scheduler);
        when(server.getConsoleSender()).thenReturn(console); when(server.dispatchCommand(any(),anyString())).thenReturn(true);
        when(plugins.getPlugin("Nexo")).thenReturn(nexo); when(nexo.isEnabled()).thenReturn(true); when(nexo.getName()).thenReturn("Nexo");
        when(nexo.getDataFolder()).thenReturn(directory.resolve("Nexo").toFile());
        PluginMeta meta=mock(PluginMeta.class); when(meta.getVersion()).thenReturn("1.28.0"); when(nexo.getPluginMeta()).thenReturn(meta);
        when(scheduler.runTaskLater(eq(plugin),any(Runnable.class),anyLong())).thenAnswer(call->enqueue(call.getArgument(1)));
        when(scheduler.runTask(eq(plugin),any(Runnable.class))).thenAnswer(call->enqueue(call.getArgument(1)));
        when(scheduler.runTaskAsynchronously(eq(plugin),any(Runnable.class))).thenAnswer(call->enqueue(call.getArgument(1)));
    }
    private BukkitTask enqueue(Runnable action) { tasks.add(action); return mock(BukkitTask.class); }
    private void drain() {
        int count=0;
        while(!tasks.isEmpty()) { assertTrue(++count<50,"No rebuild loop"); tasks.remove().run(); }
    }
    private Path template() { return directory.resolve("Nexo/pack/template_packs/"+NexoPackInstaller.PACK_NAME); }

    @Test void nexoOwnsDeliveryAndOnlyAChangedInputTriggersARebuild() throws Exception {
        try(var packs=new PackService(plugin)) {
            assertTrue(packs.ownsNexoDelivery());
            Player player=mock(Player.class);
            packs.request(player,true); verifyNoInteractions(player);
            drain(); assertTrue(Files.isRegularFile(template()));
            verify(server,times(1)).dispatchCommand(console,"nexo reload pack");
            when(player.getName()).thenReturn("Explorer"); packs.request(player,false);
            verify(server).dispatchCommand(console,"nexo pack Explorer");
        }
        try(var packs=new PackService(plugin)) {
            drain(); verify(server,times(1)).dispatchCommand(console,"nexo reload pack");
            assertTrue(packs.ownsNexoDelivery());
        }
    }

    @Test void waitsForTheStartupBuildBeforeInstallingOrRebuilding() throws Exception {
        Generator generator=new Generator(); when(nexo.getPackGenerator()).thenReturn(generator);
        try(var packs=new PackService(plugin)) {
            drain(); assertFalse(Files.exists(template()));
            verify(server,never()).dispatchCommand(console,"nexo reload pack");
            generator.future.complete(null); drain();
            assertTrue(Files.exists(template()));
            verify(server,times(1)).dispatchCommand(console,"nexo reload pack");
        }
    }

    @Test void closingWhileNexoIsGeneratingCancelsTheHandoff() throws Exception {
        Generator generator=new Generator(); when(nexo.getPackGenerator()).thenReturn(generator);
        var packs=new PackService(plugin); drain(); packs.close();
        generator.future.complete(null); drain();
        assertFalse(Files.exists(template())); verify(server,never()).dispatchCommand(any(),anyString());
    }

    @Test void lateNexoActivationTakesOverDelivery() throws Exception {
        when(plugins.getPlugin("Nexo")).thenReturn(null);
        try(var packs=new PackService(plugin)) {
            assertFalse(packs.ownsNexoDelivery());
            PluginEnableEvent event=mock(PluginEnableEvent.class); when(event.getPlugin()).thenReturn(nexo);
            packs.nexoEnabled(event); drain();
            assertTrue(packs.ownsNexoDelivery()); assertTrue(Files.exists(template()));
            Player player=mock(Player.class); packs.request(player,true); verifyNoInteractions(player);
        }
    }

    @Test void withoutNexoOrWhenDisabledTheExistingStandaloneDeliveryStillWorks() throws Exception {
        for(boolean installed:new boolean[]{false,true}) {
            when(plugins.getPlugin("Nexo")).thenReturn(installed ? nexo : null);
            config.set("resource-pack.nexo.enabled",!installed);
            try(var packs=new PackService(plugin)) {
                assertFalse(packs.ownsNexoDelivery()); drain();
                Player player=mock(Player.class); when(player.getUniqueId()).thenReturn(UUID.randomUUID());
                packs.request(player,true);
                verify(player).addResourcePack(any(UUID.class),eq("https://example.com/flashlight.zip"),isNull(),anyString(),anyBoolean());
            }
        }
        assertFalse(Files.exists(directory.resolve("Nexo")));
    }

    @Test void autoRebuildCanBeDisabledWithoutDisablingInstallation() throws Exception {
        config.set("resource-pack.nexo.auto-rebuild",false);
        try(var packs=new PackService(plugin)) {
            drain(); assertTrue(packs.ownsNexoDelivery()); assertTrue(Files.exists(template()));
            verify(server,never()).dispatchCommand(any(),anyString());
        }
    }
}
