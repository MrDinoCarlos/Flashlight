package es.mrdino.flashlight.pack;

import es.mrdino.flashlight.FlashlightPlugin;
import java.io.IOException;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/** Uses TRP's overlay API so its post-processing and StrobeLights remain in the same final pack. */
final class TrpPackBridge implements AutoCloseable {
    private final FlashlightPlugin plugin;
    private final Plugin trp;
    private boolean registered;
    TrpPackBridge(FlashlightPlugin plugin,Plugin trp) { this.plugin=plugin; this.trp=trp; }

    boolean start(byte[] pack,boolean nexo) throws ReflectiveOperationException,IOException {
        byte[] assets=ShaderComposer.assetsOnly(pack);
        Object host=trp.getClass().getMethod("combineResourcePackOverlay",Plugin.class,String.class,byte[].class)
                .invoke(trp,plugin,NexoPackInstaller.PACK_NAME,assets);
        if(!(host instanceof byte[] base) || base.length==0) return false;
        byte[] overlay=ShaderComposer.overlay(pack,base);
        if(nexo) {
            Object accepted=trp.getClass().getMethod("registerNexoResourcePackOverlay",Plugin.class,String.class,byte[].class)
                    .invoke(trp,plugin,NexoPackInstaller.PACK_NAME,overlay);
            registered=Boolean.TRUE.equals(accepted);
        }
        if(!registered) {
            Object url=trp.getClass().getMethod("registerResourcePackOverlay",Plugin.class,String.class,byte[].class)
                    .invoke(trp,plugin,NexoPackInstaller.PACK_NAME,overlay);
            registered=url instanceof String address && !address.isBlank();
        }
        if(registered) plugin.getLogger().info("FlashLight registered in TRP's shared pack; TRP/StrobeLights shaders and delivery preserved.");
        return registered;
    }

    void request(Player player) {
        if(!registered || !trp.isEnabled()) return;
        try { trp.getClass().getMethod("resendResourcePack",Plugin.class,Player.class).invoke(trp,plugin,player); }
        catch(ReflectiveOperationException failure) { plugin.getLogger().warning("TRP could not resend the shared resource pack: "+failure.getMessage()); }
    }
    @Override public void close() {
        if(!registered || !trp.isEnabled()) return;
        try { trp.getClass().getMethod("unregisterResourcePackOverlay",Plugin.class).invoke(trp,plugin); }
        catch(ReflectiveOperationException failure) { plugin.getLogger().warning("TRP overlay cleanup failed: "+failure.getMessage()); }
        registered=false;
    }
}
