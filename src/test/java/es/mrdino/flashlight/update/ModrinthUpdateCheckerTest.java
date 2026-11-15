package es.mrdino.flashlight.update;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import es.mrdino.flashlight.FlashlightPlugin;
import io.papermc.paper.plugin.configuration.PluginMeta;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import org.bukkit.Server;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.PluginManager;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ModrinthUpdateCheckerTest {
    private JsonObject release(String version,String minecraft) {
        var value=new JsonObject();
        value.addProperty("id","Version123"); value.addProperty("project_id",ModrinthUpdateChecker.PROJECT_ID);
        value.addProperty("version_number",version); value.addProperty("version_type","release"); value.addProperty("status","listed");
        value.add("loaders",JsonParser.parseString("[\"paper\"]"));
        var games=new JsonArray(); games.add(minecraft); value.add("game_versions",games);
        return value;
    }

    @Test void selectsANewerStablePaperReleaseForTheExactMinecraftVersion() {
        var releases=new JsonArray();
        var beta=release("99.0.0","26.3"); beta.addProperty("version_type","beta"); releases.add(beta);
        releases.add(release("1.99.0","26.2"));
        var fabric=release("98.0.0","26.3"); fabric.add("loaders",JsonParser.parseString("[\"fabric\"]")); releases.add(fabric);
        var other=release("97.0.0","26.3"); other.addProperty("project_id","OtherProject"); releases.add(other);
        var archived=release("96.0.0","26.3"); archived.addProperty("status","archived"); releases.add(archived);
        releases.add(release("2.0.0-rc.1","26.3")); releases.add(release("broken","26.3"));
        releases.add(release("1.9.9","26.3")); releases.add(release("1.10.0","26.3"));
        var result=ModrinthUpdateChecker.findUpdate(releases,"1.8.3","26.3").orElseThrow();
        assertEquals("1.10.0",result.version());
        assertEquals("https://modrinth.com/plugin/the-flashlight-plugin/version/Version123",result.url());
        assertTrue(ModrinthUpdateChecker.findUpdate(releases,"2.0.0","26.3").isEmpty());
    }

    @Test void equalOlderOrUnparseableVersionsDoNotProduceFalseUpdateWarnings() {
        var releases=new JsonArray(); releases.add(release("1.8.2","1.21.4")); releases.add(release("v1.8.3+build.2","1.21.4"));
        assertTrue(ModrinthUpdateChecker.findUpdate(releases,"1.8.3","1.21.4").isEmpty());
        assertTrue(ModrinthUpdateChecker.findUpdate(releases,"local-build","1.21.4").isEmpty());
        assertTrue(ModrinthUpdateChecker.findUpdate(releases,"1.8.3-SNAPSHOT","1.21.4").isPresent());
        assertTrue(ModrinthUpdateChecker.findUpdate(new JsonArray(),"1.8.3","1.21.4").isEmpty());
    }

    @Test void queryTargetsTheVerifiedProjectAndOmitsLargeChangelogs() {
        URI uri=ModrinthUpdateChecker.versionsUri("26.3");
        assertEquals("api.modrinth.com",uri.getHost());
        assertTrue(uri.getPath().contains("/J6Ka5Ie8/version"));
        String query=java.net.URLDecoder.decode(uri.getRawQuery(),StandardCharsets.UTF_8);
        assertTrue(query.contains("game_versions=[\"26.3\"]")); assertTrue(query.contains("loaders=[\"paper\"]"));
        assertTrue(query.contains("include_changelog=false"));
    }

    @Test void httpUsesAnIdentifyingUserAgentAndHandlesRateLimitsAndInvalidJson() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/ok",exchange->{
            assertTrue(exchange.getRequestHeaders().getFirst("User-Agent").contains("MrDinoCarlos/Flashlight/1.8.3"));
            byte[] bytes="[]".getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(200,bytes.length);
            try(var output=exchange.getResponseBody()) { output.write(bytes); }
        });
        server.createContext("/limited",exchange->{ exchange.sendResponseHeaders(429,-1); exchange.close(); });
        server.createContext("/invalid",exchange->{
            byte[] bytes="not-json".getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(200,bytes.length);
            try(var output=exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        try {
            String base="http://127.0.0.1:"+server.getAddress().getPort();
            assertEquals(0,ModrinthUpdateChecker.fetch(URI.create(base+"/ok"),"1.8.3").size());
            assertThrows(java.io.IOException.class,()->ModrinthUpdateChecker.fetch(URI.create(base+"/limited"),"1.8.3"));
            assertThrows(RuntimeException.class,()->ModrinthUpdateChecker.fetch(URI.create(base+"/invalid"),"1.8.3"));
        } finally { server.stop(0); }
    }

    @Test void checksRunAsynchronouslyNotifyOnlyAdminsAndStopAfterClose() {
        var plugin=mock(FlashlightPlugin.class); var server=mock(Server.class); var manager=mock(PluginManager.class);
        var scheduler=mock(BukkitScheduler.class); var task=mock(BukkitTask.class); var logger=mock(Logger.class);
        var config=new YamlConfiguration(); var meta=mock(PluginMeta.class);
        when(plugin.getConfig()).thenReturn(config); when(plugin.getServer()).thenReturn(server);
        when(plugin.getPluginMeta()).thenReturn(meta); when(meta.getVersion()).thenReturn("1.8.3");
        when(plugin.getLogger()).thenReturn(logger); when(server.getMinecraftVersion()).thenReturn("26.3");
        when(server.getPluginManager()).thenReturn(manager); when(server.getScheduler()).thenReturn(scheduler);
        var admin=mock(Player.class); var player=mock(Player.class);
        when(admin.hasPermission("flashlight.admin")).thenReturn(true);
        doReturn(List.of(admin,player)).when(server).getOnlinePlayers();
        List<Runnable> checks=new ArrayList<>(); List<Runnable> notifications=new ArrayList<>();
        when(scheduler.runTaskTimerAsynchronously(eq(plugin),any(Runnable.class),eq(200L),eq(1_728_000L))).thenAnswer(call->{ checks.add(call.getArgument(1)); return task; });
        when(scheduler.runTask(eq(plugin),any(Runnable.class))).thenAnswer(call->{ notifications.add(call.getArgument(1)); return mock(BukkitTask.class); });
        AtomicInteger requests=new AtomicInteger();
        try(var checker=new ModrinthUpdateChecker(plugin,(minecraft,installed)->{
            assertEquals("26.3",minecraft); assertEquals("1.8.3",installed); requests.incrementAndGet();
            var releases=new JsonArray(); releases.add(release("1.9.0","26.3")); return releases;
        })) {
            checker.start(); checker.start(); assertEquals(1,checks.size());
            checks.getFirst().run(); assertEquals(1,notifications.size()); notifications.removeFirst().run();
            verify(admin).sendMessage(any(net.kyori.adventure.text.Component.class));
            verify(player,never()).sendMessage(any(net.kyori.adventure.text.Component.class));
            checks.getFirst().run(); notifications.removeFirst().run();
            verify(admin,times(1)).sendMessage(any(net.kyori.adventure.text.Component.class));
            checker.join(new PlayerJoinEvent(admin,(net.kyori.adventure.text.Component)null));
            verify(admin,times(1)).sendMessage(any(net.kyori.adventure.text.Component.class));
            checker.close(); checks.getFirst().run(); assertEquals(2,requests.get());
            verify(task).cancel();
        }
    }

    @Test void noticesRepeatOnlyAfter24HoursAndReloadKeepsTheCooldown() {
        var plugin=mock(FlashlightPlugin.class); var server=mock(Server.class); var scheduler=mock(BukkitScheduler.class);
        var config=new YamlConfiguration(); var meta=mock(PluginMeta.class); var logger=mock(Logger.class);
        when(plugin.getConfig()).thenReturn(config); when(plugin.getServer()).thenReturn(server);
        when(plugin.getPluginMeta()).thenReturn(meta); when(meta.getVersion()).thenReturn("1.8.3");
        when(plugin.getLogger()).thenReturn(logger); when(server.getMinecraftVersion()).thenReturn("26.3");
        when(server.getScheduler()).thenReturn(scheduler); when(server.getPluginManager()).thenReturn(mock(PluginManager.class));
        var admin=mock(Player.class); when(admin.getUniqueId()).thenReturn(java.util.UUID.randomUUID());
        when(admin.hasPermission("flashlight.admin")).thenReturn(true); doReturn(List.of(admin)).when(server).getOnlinePlayers();
        List<Runnable> checks=new ArrayList<>();
        when(scheduler.runTaskTimerAsynchronously(eq(plugin),any(Runnable.class),anyLong(),anyLong())).thenAnswer(call->{ checks.add(call.getArgument(1)); return mock(BukkitTask.class); });
        when(scheduler.runTask(eq(plugin),any(Runnable.class))).thenAnswer(call->{ ((Runnable)call.getArgument(1)).run(); return mock(BukkitTask.class); });
        var millis=new java.util.concurrent.atomic.AtomicLong();
        java.time.Clock clock=new java.time.Clock() {
            public java.time.ZoneId getZone() { return java.time.ZoneOffset.UTC; }
            public java.time.Clock withZone(java.time.ZoneId zone) { return this; }
            public java.time.Instant instant() { return java.time.Instant.ofEpochMilli(millis.get()); }
        };
        try(var checker=new ModrinthUpdateChecker(plugin,(minecraft,installed)->{
            var releases=new JsonArray(); releases.add(release("1.9.0","26.3")); return releases;
        },clock)) {
            checker.start(); checks.getLast().run();
            checker.join(new PlayerJoinEvent(admin,(net.kyori.adventure.text.Component)null));
            millis.set(java.util.concurrent.TimeUnit.HOURS.toMillis(23)); checks.getLast().run();
            checker.reload(); checks.getLast().run(); checker.join(new PlayerJoinEvent(admin,(net.kyori.adventure.text.Component)null));
            verify(logger,times(1)).info(contains("update available"));
            verify(admin,times(1)).sendMessage(any(net.kyori.adventure.text.Component.class));
            millis.set(java.util.concurrent.TimeUnit.HOURS.toMillis(24)); checks.getLast().run();
            verify(logger,times(2)).info(contains("update available"));
            verify(admin,times(2)).sendMessage(any(net.kyori.adventure.text.Component.class));
            checker.join(new PlayerJoinEvent(admin,(net.kyori.adventure.text.Component)null));
            checks.getLast().run();
            verify(admin,times(2)).sendMessage(any(net.kyori.adventure.text.Component.class));
            millis.set(java.util.concurrent.TimeUnit.HOURS.toMillis(48)); checks.getLast().run();
            verify(logger,times(3)).info(contains("update available"));
            verify(admin,times(3)).sendMessage(any(net.kyori.adventure.text.Component.class));
        }
    }

    @Test void disabledChecksNeverScheduleNetworkWorkAndFailuresStayNonFatal() {
        var plugin=mock(FlashlightPlugin.class); var server=mock(Server.class); var scheduler=mock(BukkitScheduler.class);
        var config=new YamlConfiguration(); config.set("updates.enabled",false);
        when(plugin.getConfig()).thenReturn(config); when(plugin.getServer()).thenReturn(server);
        try(var checker=new ModrinthUpdateChecker(plugin,(minecraft,installed)->{ fail("No network when disabled"); return null; })) {
            checker.start(); verifyNoInteractions(server);
        }
        config.set("updates.enabled",true);
        when(server.getPluginManager()).thenReturn(mock(PluginManager.class)); when(server.getScheduler()).thenReturn(scheduler);
        when(server.getMinecraftVersion()).thenReturn("26.3");
        var meta=mock(PluginMeta.class); when(meta.getVersion()).thenReturn("1.8.3"); when(plugin.getPluginMeta()).thenReturn(meta);
        when(plugin.getLogger()).thenReturn(mock(Logger.class));
        List<Runnable> checks=new ArrayList<>();
        when(scheduler.runTaskTimerAsynchronously(eq(plugin),any(Runnable.class),anyLong(),anyLong())).thenAnswer(call->{ checks.add(call.getArgument(1)); return mock(BukkitTask.class); });
        try(var checker=new ModrinthUpdateChecker(plugin,(minecraft,installed)->{ throw new java.io.IOException("network unavailable"); })) {
            checker.start(); assertDoesNotThrow(()->checks.getFirst().run());
            verify(scheduler,never()).runTask(eq(plugin),any(Runnable.class));
        }
    }
}
