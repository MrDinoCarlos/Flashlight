package es.mrdino.flashlight.pack;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import es.mrdino.flashlight.FlashlightItems;
import es.mrdino.flashlight.FlashlightPlugin;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;

/** One shared item pack. Native arms are rendered by Minecraft, never baked into a model. */
public final class PackService implements AutoCloseable, org.bukkit.event.Listener {
    private static final UUID PACK_ID = UUID.fromString("b946fb5e-f484-37d6-8fd5-cfe7139c566d");
    private final FlashlightPlugin plugin;
    private final Path file;
    private final String hash;
    private final String route;
    private final Map<UUID, Long> requests = new HashMap<>();
    private final ExecutorService workers = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "Flashlight-pack-http");
        thread.setDaemon(true);
        return thread;
    });
    private HttpServer server;
    private String publicUrl = "";
    private boolean closed;
    private boolean nexoOwned;
    private NexoPackBridge nexoBridge;
    private TrpPackBridge trpBridge;
    private boolean trpOwned;
    private boolean strobeStandalone;

    public PackService(FlashlightPlugin plugin) throws IOException {
        this.plugin = plugin;
        Files.createDirectories(plugin.getDataFolder().toPath());
        file = plugin.getDataFolder().toPath().resolve("FlashLight-ResourcePack.zip");
        try (InputStream base = plugin.getResource("pack/FlashLight-ResourcePack.zip")) {
            if (base == null) throw new IOException("The bundled resource pack is missing.");
            String minecraft = plugin.getServer().getMinecraftVersion();
            if (minecraft == null) minecraft = "1.21.4";
            byte[] bytes=PackTuning.apply(PackCompatibility.forMinecraft(base.readAllBytes(), minecraft),plugin.settings().glare());
            var trp=plugin.getServer().getPluginManager().getPlugin("TRPServerEdition");
            var strobe=plugin.getServer().getPluginManager().getPlugin("StrobeLights");
            if((trp==null || !trp.isEnabled()) && strobe!=null && strobe.isEnabled()) {
                try {
                    bytes=StrobePackSupport.compose(strobe,bytes,minecraft); strobeStandalone=true;
                    plugin.getLogger().info("StrobeLights " + minecraft + " shader preserved in FlashLight's pack.");
                } catch(IOException failure) {
                    plugin.getLogger().log(Level.WARNING,"StrobeLights pack integration is unavailable for "+minecraft
                            +"; FlashLight's native pack was exported. Install a matching StrobeLights pack before combining their renderers.",failure);
                }
            }
            Files.write(file,bytes);
        }
        hash = sha1(Files.readAllBytes(file));
        route = "/packs/" + hash + ".zip";
        plugin.getServer().getPluginManager().registerEvents(this,plugin);
        var trp=plugin.getServer().getPluginManager().getPlugin("TRPServerEdition");
        var nexo=plugin.getServer().getPluginManager().getPlugin("Nexo");
        if(trp!=null && trp.isEnabled()) useTrp(trp,nexo);
        else if(nexo!=null && nexo.isEnabled() && plugin.getConfig().getBoolean("resource-pack.nexo.enabled",true)) useNexo(nexo);
        else startHost();
    }

    private void useTrp(org.bukkit.plugin.Plugin trp,org.bukkit.plugin.Plugin nexo) {
        trpOwned=true;
        if(server!=null) { server.stop(0); server=null; }
        if(nexoBridge!=null) { nexoBridge.close(); nexoBridge=null; }
        nexoOwned=false;
        if(trpBridge!=null) trpBridge.close();
        trpBridge=new TrpPackBridge(plugin,trp);
        try {
            boolean available=nexo!=null && nexo.isEnabled() && plugin.getConfig().getBoolean("resource-pack.nexo.enabled",true);
            if(available) NexoPackInstaller.uninstall(nexo.getDataFolder().toPath());
            if(!trpBridge.start(Files.readAllBytes(file),available))
                plugin.getLogger().severe("TRP did not accept the FlashLight overlay; its current pack was preserved. Enable TRP's embedded or Nexo pack provider and reload Flashlight.");
        } catch(ReflectiveOperationException | IOException failure) {
            plugin.getLogger().log(Level.SEVERE,"TRP pack integration failed; its renderer and pack delivery were preserved.",failure);
        }
    }

    private void useNexo(org.bukkit.plugin.Plugin nexo) {
        nexoOwned=true;
        if(server!=null) { server.stop(0); server=null; }
        if(nexoBridge!=null) nexoBridge.close();
        nexoBridge=new NexoPackBridge(plugin,nexo,file); nexoBridge.start();
    }
    @org.bukkit.event.EventHandler
    public void nexoEnabled(org.bukkit.event.server.PluginEnableEvent event) {
        if(closed) return;
        if(event.getPlugin().getName().equals("TRPServerEdition")) {
            useTrp(event.getPlugin(),plugin.getServer().getPluginManager().getPlugin("Nexo"));
        } else if(event.getPlugin().getName().equals("Nexo") && plugin.getConfig().getBoolean("resource-pack.nexo.enabled",true)) {
            var trp=plugin.getServer().getPluginManager().getPlugin("TRPServerEdition");
            if(trp!=null && trp.isEnabled()) useTrp(trp,event.getPlugin()); else useNexo(event.getPlugin());
        }
    }
    boolean ownsNexoDelivery() { return nexoOwned; }

    @org.bukkit.event.EventHandler(priority=org.bukkit.event.EventPriority.MONITOR)
    public void sharedPackLoaded(org.bukkit.event.player.PlayerResourcePackStatusEvent event) {
        if(closed || !strobeStandalone || nexoOwned || trpOwned || isOurPack(event.getID())
                || event.getStatus()!=org.bukkit.event.player.PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED) return;
        if(!plugin.settings().packOnJoin() && !requests.containsKey(event.getPlayer().getUniqueId())) return;
        plugin.getServer().getScheduler().runTask(plugin,()->{
            if(!closed && event.getPlayer().isOnline()) {
                requests.remove(event.getPlayer().getUniqueId()); request(event.getPlayer(),true);
            }
        });
    }

    private void startHost() {
        if (!plugin.getConfig().getBoolean("resource-pack.server.enabled", false)) return;
        try {
            String configured = plugin.getConfig().getString("resource-pack.server.public-url", "").trim();
            URI uri = URI.create(configured);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getQuery() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException("Configure resource-pack.server.public-url with an HTTP(S) URL.");
            }
            int port = plugin.getConfig().getInt("resource-pack.server.port", 8192);
            if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid resource pack port.");
            server = HttpServer.create(new InetSocketAddress(
                    plugin.getConfig().getString("resource-pack.server.bind", "0.0.0.0"), port), 32);
            server.createContext("/packs/", this::serve);
            server.setExecutor(workers);
            server.start();
            publicUrl = configured.replaceAll("/+$", "");
        } catch (IOException | IllegalArgumentException exception) {
            if (server != null) server.stop(0);
            server = null;
            plugin.getLogger().log(Level.WARNING, "Could not host the resource pack. Manual export is available.", exception);
        }
    }

    public void request(Player player, boolean automatic) {
        if (closed) return;
        if(trpOwned) { if(!automatic && trpBridge!=null) trpBridge.request(player); return; }
        if(nexoOwned) { if(!automatic && nexoBridge!=null) nexoBridge.request(player); return; }
        long now = System.currentTimeMillis();
        Long previous = requests.get(player.getUniqueId());
        if (previous != null && now - previous < 5000) return;
        String url = plugin.settings().packUrl();
        String sha = plugin.settings().packSha1();
        if (url.isEmpty() && server != null) { url = publicUrl + route; sha = hash; }
        if (url.isEmpty()) {
            if (!automatic) export(player);
            return;
        }
        requests.put(player.getUniqueId(), now);
        player.addResourcePack(PACK_ID, url, sha.isEmpty() ? null : HexFormat.of().parseHex(sha),
                plugin.settings().packPrompt(), plugin.settings().packRequired());
    }

    public void export(CommandSender sender) {
        if (!closed) sender.sendMessage(FlashlightItems.text(
                "Resource pack: plugins/FlashLight/FlashLight-ResourcePack.zip", NamedTextColor.GREEN));
    }

    private void serve(HttpExchange exchange) throws IOException {
        try (exchange) {
            String method = exchange.getRequestMethod();
            if (!method.equals("GET") && !method.equals("HEAD")) {
                exchange.getResponseHeaders().set("Allow", "GET, HEAD");
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            if (!route.equals(exchange.getRequestURI().getPath()) || !Files.isRegularFile(file)) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            exchange.getResponseHeaders().set("Content-Type", "application/zip");
            exchange.getResponseHeaders().set("Cache-Control", "public, max-age=31536000, immutable");
            exchange.getResponseHeaders().set("ETag", '"' + hash + '"');
            long size = Files.size(file);
            exchange.getResponseHeaders().set("Content-Length", Long.toString(size));
            exchange.sendResponseHeaders(200, method.equals("HEAD") ? -1 : size);
            if (method.equals("GET")) Files.copy(file, exchange.getResponseBody());
        }
    }

    public static String sha1(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes)); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
    public static boolean isOurPack(UUID id) { return PACK_ID.equals(id); }
    public void forget(Player player) { requests.remove(player.getUniqueId()); }
    @Override public void close() {
        closed = true;
        org.bukkit.event.HandlerList.unregisterAll(this);
        if(trpBridge!=null) trpBridge.close();
        if(nexoBridge!=null) nexoBridge.close();
        if (server != null) server.stop(0);
        workers.shutdownNow();
        requests.clear();
    }
}
