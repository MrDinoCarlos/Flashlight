package es.mrdino.flashlight.pack;

import es.mrdino.flashlight.FlashlightPlugin;
import es.mrdino.flashlight.FlashlightSettings;
import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.Server;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.plugin.PluginManager;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StandalonePackDeliveryTest {
    @TempDir Path directory;
    private FlashlightPlugin plugin;
    private Server server;
    private Player player;
    private YamlConfiguration config;
    private int port;
    private final ArrayDeque<Runnable> tasks=new ArrayDeque<>();

    @BeforeEach void setup() throws Exception {
        plugin=mock(FlashlightPlugin.class); server=mock(Server.class); player=mock(Player.class);
        var plugins=mock(PluginManager.class);
        var scheduler=mock(BukkitScheduler.class);
        config=new YamlConfiguration();
        try(var input=getClass().getResourceAsStream("/config.yml")) {
            config.load(new java.io.InputStreamReader(java.util.Objects.requireNonNull(input),java.nio.charset.StandardCharsets.UTF_8));
        }
        try(var socket=new ServerSocket(0)) { port=socket.getLocalPort(); }
        config.set("resource-pack.server.bind","127.0.0.1");
        config.set("resource-pack.server.port",port);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.settings()).thenAnswer(ignored->FlashlightSettings.load(config));
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        when(server.getPluginManager()).thenReturn(plugins);
        when(server.getScheduler()).thenReturn(scheduler);
        when(server.getMinecraftVersion()).thenReturn("1.21.4");
        when(scheduler.runTaskLater(eq(plugin),any(Runnable.class),eq(30L))).thenAnswer(call->{
            tasks.add(call.getArgument(1)); return mock(BukkitTask.class);
        });
        when(scheduler.runTask(eq(plugin),any(Runnable.class))).thenAnswer(call->{
            tasks.add(call.getArgument(1)); return mock(BukkitTask.class);
        });
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.isOnline()).thenReturn(true);
        when(player.getVirtualHost()).thenReturn(InetSocketAddress.createUnresolved("127.0.0.1",25565));
        usePack("1.21.4");
    }

    private void usePack(String minecraft) throws Exception {
        String version=Files.readString(Path.of("build.gradle.kts")).lines()
                .filter(line->line.startsWith("version = ")).findFirst().orElseThrow().split("\"")[1];
        String branch=minecraft.startsWith("1.") ? "1.21.X" : minecraft.substring(0,4)+".X";
        byte[] bytes=Files.readAllBytes(Path.of("build/distributions/FlashLight-ResourcePack-"+version+"-"+branch+".zip"));
        when(plugin.getResource("pack/FlashLight-ResourcePack.zip")).thenAnswer(ignored->new ByteArrayInputStream(bytes));
        when(server.getMinecraftVersion()).thenReturn(minecraft);
    }

    private void join(PackService packs) {
        packs.join(new PlayerJoinEvent(player,(net.kyori.adventure.text.Component)null));
        assertFalse(tasks.isEmpty(),"Joining schedules automatic delivery with the shipped defaults");
        while(!tasks.isEmpty()) tasks.remove().run();
    }

    private URI sentUrl() {
        var url=ArgumentCaptor.forClass(String.class);
        var sha=ArgumentCaptor.forClass(byte[].class);
        verify(player).addResourcePack(any(UUID.class),url.capture(),sha.capture(),anyString(),eq(false));
        assertEquals(40,java.util.HexFormat.of().formatHex(sha.getValue()).length());
        return URI.create(url.getValue());
    }

    @ParameterizedTest
    @ValueSource(strings={"1.21.4","1.21.5","1.21.6","1.21.7","1.21.8","1.21.9","1.21.10","1.21.11","26.1","26.1.1","26.1.2","26.2","26.3"})
    void joinsWithoutNexoDownloadTheExactNativePackOverHttp(String minecraft) throws Exception {
        usePack(minecraft);
        try(var packs=new PackService(plugin); var client=HttpClient.newHttpClient()) {
            join(packs);
            URI url=sentUrl();
            assertEquals(port,url.getPort());
            var response=client.send(HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(5)).build(),HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200,response.statusCode());
            assertEquals("application/zip",response.headers().firstValue("Content-Type").orElseThrow());
            assertArrayEquals(Files.readAllBytes(directory.resolve("FlashLight-ResourcePack.zip")),response.body());
            assertEquals("/packs/"+PackService.sha1(response.body())+".zip",url.getPath());
            var assets=ShaderComposer.read(response.body());
            var metadata=com.google.gson.JsonParser.parseString(new String(assets.get("pack.mcmeta"),java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            assertEquals(PackCompatibility.format(minecraft),PackCompatibility.major(metadata.getAsJsonObject("pack").get("min_format")));
            assertFalse(metadata.has("overlays"),"The HTTP server delivers the resolved native pack");
        }
    }

    @Test void explicitPublicUrlOverridesTheLoginHostname() throws Exception {
        config.set("resource-pack.server.public-url","https://packs.example.com/flashlight/");
        try(var packs=new PackService(plugin)) {
            join(packs);
            assertTrue(sentUrl().toString().startsWith("https://packs.example.com/flashlight/packs/"));
        }
    }

    @ParameterizedTest @ValueSource(strings={"play.example.com","192.0.2.5","[2001:db8::1]"})
    void advertisedUrlsSupportDomainsIpv4AndIpv6(String host) throws Exception {
        when(player.getVirtualHost()).thenReturn(InetSocketAddress.createUnresolved(host,25577));
        try(var packs=new PackService(plugin)) {
            join(packs);
            URI url=sentUrl();
            assertEquals(host,url.getHost()); assertEquals(port,url.getPort());
        }
    }

    @Test void reloadResendsToOnlinePlayersAndClosedServicesDoNotSend() throws Exception {
        doReturn(List.of(player)).when(server).getOnlinePlayers();
        var old=new PackService(plugin);
        old.close();
        try(var replacement=new PackService(plugin)) {
            assertEquals(2,tasks.size());
            tasks.remove().run();
            verify(player,never()).addResourcePack(any(UUID.class),anyString(),any(),anyString(),anyBoolean());
            tasks.remove().run();
            sentUrl();
        }
    }

    @Test void administratorsCanDisableAutomaticDeliveryAfterMigration() throws Exception {
        config.set("resource-pack.send-on-join",false);
        config.set("resource-pack.server.enabled",false);
        try(var packs=new PackService(plugin)) {
            packs.join(new PlayerJoinEvent(player,(net.kyori.adventure.text.Component)null));
            assertTrue(tasks.isEmpty());
            verify(player,never()).addResourcePack(any(UUID.class),anyString(),any(),anyString(),anyBoolean());
        }
    }

    @Test void closingTheHostReleasesItsPort() throws Exception {
        var packs=new PackService(plugin); packs.close();
        try(var socket=new ServerSocket()) { socket.bind(new InetSocketAddress("127.0.0.1",port)); }
    }

    private PlayerResourcePackStatusEvent status(UUID id,String value) {
        var event=mock(PlayerResourcePackStatusEvent.class);
        when(event.getPlayer()).thenReturn(player); when(event.getID()).thenReturn(id);
        when(event.getStatus()).thenReturn(PlayerResourcePackStatusEvent.Status.valueOf(value));
        return event;
    }

    @Test void reloadFailureRetriesOnlyThatPlayerOnceAndServesTheCompatibleBytes() throws Exception {
        try(var packs=new PackService(plugin); var client=HttpClient.newHttpClient()) {
            join(packs);
            var ids=ArgumentCaptor.forClass(UUID.class);
            verify(player).addResourcePack(ids.capture(),anyString(),any(),anyString(),anyBoolean());
            UUID fast=ids.getValue();
            var failure=status(fast,"FAILED_RELOAD");
            packs.failedReload(failure); packs.failedReload(failure);
            assertEquals(1,tasks.size()); tasks.remove().run();
            var urls=ArgumentCaptor.forClass(String.class); var hashes=ArgumentCaptor.forClass(byte[].class);
            verify(player,times(2)).addResourcePack(ids.capture(),urls.capture(),hashes.capture(),anyString(),anyBoolean());
            UUID compatible=ids.getValue(); assertNotEquals(fast,compatible);
            verify(player).removeResourcePack(fast);
            byte[] expected=Files.readAllBytes(directory.resolve("FlashLight-ResourcePack-Compatible.zip"));
            var response=client.send(HttpRequest.newBuilder(URI.create(urls.getValue())).build(),HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200,response.statusCode()); assertArrayEquals(expected,response.body());
            assertEquals(PackService.sha1(expected),java.util.HexFormat.of().formatHex(hashes.getValue()));
            packs.failedReload(failure); packs.failedReload(status(compatible,"FAILED_RELOAD"));
            assertTrue(tasks.isEmpty(),"Neither repeated failures nor compatible failures cause a retry loop");
            Player another=mock(Player.class);
            when(another.getUniqueId()).thenReturn(UUID.randomUUID());
            when(another.getVirtualHost()).thenReturn(InetSocketAddress.createUnresolved("127.0.0.1",25565));
            packs.request(another,true);
            verify(another).addResourcePack(eq(fast),eq(urls.getAllValues().getFirst()),any(),anyString(),anyBoolean());
            packs.renderer(player,false);
            verify(player).removeResourcePack(compatible);
            verify(player,times(2)).addResourcePack(eq(fast),anyString(),any(),anyString(),anyBoolean());
        }
    }

    @ParameterizedTest @ValueSource(strings={"DECLINED","FAILED_DOWNLOAD","ACCEPTED","SUCCESSFULLY_LOADED"})
    void unrelatedStatusesDoNotSelectACompatibleRenderer(String value) throws Exception {
        try(var packs=new PackService(plugin)) {
            join(packs);
            var ids=ArgumentCaptor.forClass(UUID.class);
            verify(player).addResourcePack(ids.capture(),anyString(),any(),anyString(),anyBoolean());
            packs.failedReload(status(ids.getValue(),value));
            packs.failedReload(status(UUID.randomUUID(),"FAILED_RELOAD"));
            assertTrue(tasks.isEmpty()); verify(player,never()).removeResourcePack(any(UUID.class));
        }
    }

    @Test void fastModeAndClosedServicesNeverScheduleFallbacks() throws Exception {
        config.set("resource-pack.renderer","FAST");
        var packs=new PackService(plugin);
        join(packs);
        var ids=ArgumentCaptor.forClass(UUID.class);
        verify(player).addResourcePack(ids.capture(),anyString(),any(),anyString(),anyBoolean());
        packs.failedReload(status(ids.getValue(),"FAILED_RELOAD"));
        assertTrue(tasks.isEmpty()); packs.close();
        packs.failedReload(status(ids.getValue(),"FAILED_RELOAD")); assertTrue(tasks.isEmpty());
    }

    @Test void explicitCompatibleModeExportsAndDeliversTheCompactRenderer() throws Exception {
        config.set("resource-pack.renderer","COMPATIBLE");
        try(var packs=new PackService(plugin)) {
            join(packs);
            assertArrayEquals(Files.readAllBytes(directory.resolve("FlashLight-ResourcePack.zip")),
                    Files.readAllBytes(directory.resolve("FlashLight-ResourcePack-Compatible.zip")));
            sentUrl();
            packs.renderer(player,false);
            verify(player,never()).removeResourcePack(any(UUID.class));
        }
    }

    @Test void externallyHostedPacksNeedAnExplicitCompatibleUrlAndKeepTheirListenerDisabled() throws Exception {
        config.set("resource-pack.url","https://packs.example/fast.zip");
        try(var packs=new PackService(plugin)) {
            packs.renderer(player,true);
            verify(player,never()).addResourcePack(any(UUID.class),anyString(),any(),anyString(),anyBoolean());
        }
        config.set("resource-pack.compatibility-url","https://packs.example/compatible.zip");
        try(var packs=new PackService(plugin)) {
            packs.renderer(player,true);
            verify(player).addResourcePack(any(UUID.class),eq("https://packs.example/compatible.zip"),any(),anyString(),anyBoolean());
            try(var socket=new ServerSocket()) { socket.bind(new InetSocketAddress("127.0.0.1",port)); }
        }
    }

    @Test void relativeCompatibleUrlIsRejectedWithAUsefulError() {
        config.set("resource-pack.compatibility-url","//example.com/pack.zip");
        var error=assertThrows(IllegalArgumentException.class,()->new PackService(plugin));
        assertTrue(error.getMessage().contains("HTTP(S)"));
    }
}
