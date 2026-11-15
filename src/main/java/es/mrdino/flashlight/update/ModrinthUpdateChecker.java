package es.mrdino.flashlight.update;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import es.mrdino.flashlight.FlashlightPlugin;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.net.URI;
import java.net.URLEncoder;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.scheduler.BukkitTask;

/** Checks official stable releases for this native Paper version; never installs files. */
public final class ModrinthUpdateChecker implements Listener, AutoCloseable {
    public static final String PROJECT_ID="J6Ka5Ie8";
    public static final String PROJECT_URL="https://modrinth.com/plugin/the-flashlight-plugin";
    private static final int MAX_RESPONSE_BYTES=2*1024*1024;
    private final FlashlightPlugin plugin;
    private final VersionSource source;
    private final Clock clock;
    private static final long NOTICE_INTERVAL_MILLIS=TimeUnit.HOURS.toMillis(24);
    private final Map<UUID,Long> adminNotifications=new HashMap<>();
    private BukkitTask task;
    private volatile boolean closed;
    private volatile Release available;
    private long consoleNotifiedAt=Long.MIN_VALUE;
    private volatile long generation;

    public record Release(String version,String id) {
        public String url() { return PROJECT_URL+"/version/"+id; }
    }

    @FunctionalInterface interface VersionSource {
        JsonArray versions(String minecraft,String installed) throws Exception;
    }

    public ModrinthUpdateChecker(FlashlightPlugin plugin) {
        this(plugin,(minecraft,installed)->fetch(versionsUri(minecraft),installed));
    }

    ModrinthUpdateChecker(FlashlightPlugin plugin,VersionSource source) {
        this(plugin,source,Clock.systemUTC());
    }

    ModrinthUpdateChecker(FlashlightPlugin plugin,VersionSource source,Clock clock) {
        this.plugin=plugin; this.source=source; this.clock=clock;
    }

    public void start() {
        if (task!=null || closed || !plugin.getConfig().getBoolean("updates.enabled",true)) return;
        long hours=Math.clamp(plugin.getConfig().getLong("updates.check-interval-hours",24),1,168);
        plugin.getServer().getPluginManager().registerEvents(this,plugin);
        task=plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin,this::check,200L,hours*72_000L);
    }

    private void check() {
        if(closed) return;
        long expectedGeneration=generation;
        try {
            String minecraft=plugin.getServer().getMinecraftVersion();
            String current=plugin.getPluginMeta().getVersion();
            JsonArray releases=source.versions(minecraft,current);
            Optional<Release> update=findUpdate(releases,current,minecraft);
            if(closed || generation!=expectedGeneration) return;
            available=update.orElse(null);
            if(available!=null) {
                Release release=available;
                if(consoleNoticeDue(clock.millis()))
                    plugin.getLogger().info("FlashLight update available: "+release.version()+" (installed "+current+"). "+release.url());
                plugin.getServer().getScheduler().runTask(plugin,()->{
                    if(!closed && generation==expectedGeneration)
                        plugin.getServer().getOnlinePlayers().forEach(player->notifyAdmin(player,release));
                });
            }
        } catch(InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch(Exception failure) {
            if(!closed) plugin.getLogger().fine("Modrinth update check unavailable: "+failure.getClass().getSimpleName()+": "+failure.getMessage());
        }
    }

    private synchronized boolean consoleNoticeDue(long now) {
        if(consoleNotifiedAt!=Long.MIN_VALUE && now-consoleNotifiedAt<NOTICE_INTERVAL_MILLIS) return false;
        consoleNotifiedAt=now;
        return true;
    }

    /** Reload changes scheduling/options, keeping the server session's anti-spam state. */
    public void reload() {
        if(closed) return;
        generation++;
        if(task!=null) task.cancel();
        task=null; available=null;
        HandlerList.unregisterAll(this);
        start();
    }

    static URI versionsUri(String minecraft) {
        String game=URLEncoder.encode("[\""+minecraft+"\"]",StandardCharsets.UTF_8);
        return URI.create("https://api.modrinth.com/v2/project/"+PROJECT_ID
                +"/version?loaders=%5B%22paper%22%5D&game_versions="+game+"&include_changelog=false");
    }

    static JsonArray fetch(URI uri,String installed) throws IOException {
        var connection=(HttpURLConnection)uri.toURL().openConnection();
        connection.setConnectTimeout(5000); connection.setReadTimeout(5000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Accept","application/json");
        connection.setRequestProperty("User-Agent","MrDinoCarlos/Flashlight/"+installed+" (+https://github.com/MrDinoCarlos/Flashlight)");
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        try {
            if(connection.getResponseCode()!=200) throw new IOException("HTTP "+connection.getResponseCode());
            try(var input=connection.getInputStream();var bytes=new ByteArrayOutputStream()) {
                byte[] buffer=new byte[8192]; int read;
                while((read=input.read(buffer))!=-1) {
                    if(System.nanoTime()>deadline) throw new IOException("Version response timed out");
                    if(bytes.size()+read>MAX_RESPONSE_BYTES) throw new IOException("Version response exceeds size limit");
                    bytes.write(buffer,0,read);
                }
                return JsonParser.parseString(bytes.toString(StandardCharsets.UTF_8)).getAsJsonArray();
            }
        } finally { connection.disconnect(); }
    }

    static Optional<Release> findUpdate(JsonArray releases,String installed,String minecraft) {
        Optional<Version> current=Version.parse(installed);
        if(current.isEmpty()) return Optional.empty();
        Release best=null; Version bestVersion=current.get();
        for(var entry:releases) {
            if(!entry.isJsonObject()) continue;
            JsonObject release=entry.getAsJsonObject();
            if(!"release".equals(string(release,"version_type")) || !"listed".equals(string(release,"status"))
                    || !PROJECT_ID.equals(string(release,"project_id"))
                    || !contains(release,"loaders","paper") || !contains(release,"game_versions",minecraft)) continue;
            String number=string(release,"version_number"),id=string(release,"id");
            Optional<Version> candidate=Version.parse(number);
            if(candidate.isEmpty() || !candidate.get().prerelease().isEmpty() || !id.matches("[A-Za-z0-9]{1,64}")) continue;
            if(candidate.get().compareTo(bestVersion)>0) {
                bestVersion=candidate.get(); best=new Release(number,id);
            }
        }
        return Optional.ofNullable(best);
    }

    private static String string(JsonObject object,String name) {
        var value=object.get(name);
        return value!=null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString() : "";
    }

    private static boolean contains(JsonObject object,String name,String wanted) {
        var values=object.get(name);
        return values!=null && values.isJsonArray() && values.getAsJsonArray().asList().stream()
                .anyMatch(value->value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() && value.getAsString().equals(wanted));
    }

    @EventHandler public void join(PlayerJoinEvent event) {
        Release release=available;
        if(!closed && release!=null) notifyAdmin(event.getPlayer(),release);
    }

    private void notifyAdmin(Player player,Release release) {
        if(!player.hasPermission("flashlight.admin") || !plugin.getConfig().getBoolean("updates.notify-admins",true)) return;
        long now=clock.millis();
        Long previous=adminNotifications.get(player.getUniqueId());
        if(previous!=null && now-previous<NOTICE_INTERVAL_MILLIS) return;
        adminNotifications.put(player.getUniqueId(),now);
        player.sendMessage(Component.text("FlashLight update available: "+release.version()+". ",NamedTextColor.YELLOW)
                .append(Component.text("Download on Modrinth",NamedTextColor.AQUA).clickEvent(ClickEvent.openUrl(release.url()))));
    }

    @Override public void close() {
        if(closed) return;
        closed=true;
        generation++;
        if(task!=null) task.cancel();
        HandlerList.unregisterAll(this);
    }

    /** Semantic ordering, rather than publication date or lexical string ordering. */
    private record Version(List<BigInteger> numbers,List<String> prerelease) implements Comparable<Version> {
        private static final Pattern FORMAT=Pattern.compile("v?(\\d+)\\.(\\d+)\\.(\\d+)(?:-([0-9A-Za-z.-]+))?(?:\\+[0-9A-Za-z.-]+)?");
        static Optional<Version> parse(String value) {
            if(value==null || value.length()>128) return Optional.empty();
            var match=FORMAT.matcher(value);
            if(!match.matches()) return Optional.empty();
            return Optional.of(new Version(List.of(new BigInteger(match.group(1)),new BigInteger(match.group(2)),new BigInteger(match.group(3))),
                    match.group(4)==null ? List.of() : Arrays.asList(match.group(4).split("\\.",-1))));
        }
        @Override public int compareTo(Version other) {
            for(int i=0;i<3;i++) { int order=numbers.get(i).compareTo(other.numbers.get(i)); if(order!=0) return order; }
            if(prerelease.isEmpty() || other.prerelease.isEmpty())
                return prerelease.isEmpty() ? (other.prerelease.isEmpty() ? 0 : 1) : -1;
            for(int i=0;i<Math.min(prerelease.size(),other.prerelease.size());i++) {
                String a=prerelease.get(i),b=other.prerelease.get(i);
                boolean an=a.matches("\\d+"),bn=b.matches("\\d+");
                int order=an && bn ? new BigInteger(a).compareTo(new BigInteger(b)) : an!=bn ? (an ? -1 : 1) : a.compareTo(b);
                if(order!=0) return order;
            }
            return Integer.compare(prerelease.size(),other.prerelease.size());
        }
    }
}
