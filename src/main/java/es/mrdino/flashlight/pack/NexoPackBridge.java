package es.mrdino.flashlight.pack;

import es.mrdino.flashlight.FlashlightPlugin;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/** Reflection-only optional bridge, following TRP's Nexo external-pack/MultiPack ownership. */
final class NexoPackBridge implements AutoCloseable {
    private final FlashlightPlugin plugin;
    private final Plugin nexo;
    private final Path pack;
    private final String mode;
    private final boolean autoRebuild;
    private final Object installation=new Object();
    private volatile boolean closed;
    private BukkitTask scheduled;

    NexoPackBridge(FlashlightPlugin plugin,Plugin nexo,Path pack) {
        this.plugin=plugin; this.nexo=nexo; this.pack=pack;
        mode=plugin.getConfig().getString("resource-pack.nexo.integration","AUTO");
        autoRebuild=plugin.getConfig().getBoolean("resource-pack.nexo.auto-rebuild",true);
    }

    void start() { scheduled=plugin.getServer().getScheduler().runTaskLater(plugin,this::waitForStartup,20); }
    private boolean alive() { return !closed && plugin.isEnabled() && nexo.isEnabled(); }
    private void main(Runnable action) {
        if(alive()) scheduled=plugin.getServer().getScheduler().runTask(plugin,()->{ if(alive()) action.run(); });
    }

    private void waitForStartup() {
        if(!alive()) return;
        CompletableFuture<?> future=generationFuture();
        if(future!=null && !future.isDone()) {
            future.whenComplete((ignored,failure)->main(this::install));
        } else install();
    }

    private void install() {
        if(!alive()) return;
        scheduled=plugin.getServer().getScheduler().runTaskAsynchronously(plugin,()->{
            if(!alive()) return;
            try {
                NexoPackInstaller.Result result;
                // A plugin reload must finish/cancel the old handoff before exporting the next ZIP.
                synchronized(installation) {
                    if(!alive()) return;
                    result=NexoPackInstaller.install(nexo.getDataFolder().toPath(),nexo.getPluginMeta().getVersion(),mode,Files.readAllBytes(pack));
                }
                plugin.getLogger().info("Nexo: FlashLight pack installed at "+result.file());
                if(result.changed() && autoRebuild) main(()->rebuild(result.multipack()));
            } catch(Exception failure) {
                plugin.getLogger().log(Level.SEVERE,"Nexo pack integration failed; FlashLight remains enabled and will not send a competing pack.",failure);
            }
        });
    }

    private Object generator() throws ReflectiveOperationException {
        for(String name:new String[]{"packGenerator","getPackGenerator"}) {
            try {
                Object value=nexo.getClass().getMethod(name).invoke(nexo);
                if(value!=null) return value;
            }
            catch(NoSuchMethodException ignored) { }
        }
        throw new NoSuchMethodException("Nexo PackGenerator accessor");
    }
    private CompletableFuture<?> generationFuture() {
        try {
            Object generator=generator();
            Object result=generator.getClass().getMethod("getPackGenFuture").invoke(generator);
            return result instanceof CompletableFuture<?> future ? future : null;
        } catch(ReflectiveOperationException | LinkageError ignored) { return null; }
    }
    private void rebuild(boolean multipack) {
        if(!alive()) return;
        CompletableFuture<?> future=generationFuture();
        if(future!=null && !future.isDone()) { future.whenComplete((ignored,failure)->main(()->rebuild(multipack))); return; }
        if(!multipack) {
            try { Object generator=generator(); generator.getClass().getMethod("regeneratePack").invoke(generator); return; }
            catch(ReflectiveOperationException | LinkageError ignored) { /* Supported console fallback. */ }
        }
        try {
            if(!plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(),"nexo reload pack"))
                plugin.getLogger().warning("Nexo did not accept 'nexo reload pack'; the installed FlashLight input was preserved.");
        } catch(RuntimeException failure) { plugin.getLogger().log(Level.WARNING,"Nexo pack reload failed; installed input preserved.",failure); }
    }
    void request(Player player) {
        if(alive()) plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(),"nexo pack "+player.getName());
    }
    @Override public void close() {
        synchronized(installation) { closed=true; }
        if(scheduled!=null) scheduled.cancel();
    }
}
