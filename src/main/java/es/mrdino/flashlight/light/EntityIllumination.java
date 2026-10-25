package es.mrdino.flashlight.light;

import es.mrdino.flashlight.FlashlightSettings;
import es.mrdino.flashlight.math.Vec3;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Transports the optical ray to the pack. Never edits entity/world/client light. */
final class EntityIllumination {
    private static final int MAX_LIGHTS=32;
    private static final class Control {
        final ItemDisplay entity;
        ItemDisplay local;
        boolean left;
        int word=-1;
        final int slot;
        Vec3 origin,direction,appliedOrigin;
        Transformation transformation;
        double visibleRange,power,range,angle,beamVisibility;
        Control(ItemDisplay entity,int slot) { this.entity=entity; this.slot=slot; }
    }
    private final NamespacedKey key;
    private final Plugin plugin;
    private final PlayerShadows shadows;
    private final boolean enabled;
    private final Map<UUID,Control> controls=new HashMap<>();
    EntityIllumination(Plugin plugin) {
        this.plugin=plugin;
        key=new NamespacedKey(plugin,"screen_control");
        shadows=new PlayerShadows(plugin);
        enabled=plugin.getConfig().getBoolean("light.entities.enabled",true);
        for(var world:Bukkit.getWorlds()) for(var entity:world.getEntities())
            if(entity instanceof ItemDisplay && entity.getPersistentDataContainer().has(key)) entity.remove();
    }
    boolean enabled() { return enabled; }
    static int word(int slot,double power,double range,double angle) {
        int strength=(int)Math.round(Math.clamp(power,0,1)*255);
        return slot | strength<<5 | ((int)Math.round(Math.clamp(range,4,32))-4)<<13
                | ((int)Math.round(Math.clamp(angle,4,65))-4)<<18;
    }
    void update(org.bukkit.entity.Player player,Vec3 origin,Vec3 direction,double angle,double range,double power,double visibleRange,double beamVisibility,
                FlashlightSettings settings,boolean left) {
        if(!enabled) return;
        if(power<=0 || visibleRange<.08) { remove(player.getUniqueId()); return; }
        World world=player.getWorld(); Control control=controls.get(player.getUniqueId());
        if(control!=null && (!control.entity.isValid() || !control.entity.getWorld().equals(world))) { remove(player.getUniqueId()); control=null; }
        if(control==null) {
            int occupied=0;
            for(Control other:controls.values()) if(other.entity.getWorld().equals(world)) occupied|=1<<other.slot;
            int slot=0;
            while(slot<MAX_LIGHTS && (occupied & 1<<slot)!=0) slot++;
            if(slot>=MAX_LIGHTS) return;
            ItemDisplay entity=spawn(world,origin,"entity_control",settings.tracking().interpolationTicks());
            if(!settings.tracking().shared()) entity.setVisibleByDefault(false);
            control=new Control(entity,slot); controls.put(player.getUniqueId(),control);
            if(player.isOnline()) {
                if(settings.tracking().localCamera()) {
                    control.local=spawn(world,origin,left ? "entity_control_left" : "entity_control_right",1);
                    control.left=left;
                    control.local.setVisibleByDefault(false);
                    player.showEntity(plugin,control.local);
                    player.hideEntity(plugin,entity);
                } else if(!settings.tracking().shared()) player.showEntity(plugin,entity);
            }
        }
        if(control.local!=null && control.left!=left) {
            model(control.local,left ? "entity_control_left" : "entity_control_right"); control.left=left;
        }
        control.origin=origin; control.direction=direction; control.angle=angle;
        control.range=range; control.power=power; control.visibleRange=visibleRange;
        control.beamVisibility=beamVisibility;
        apply(control);
    }
    private ItemDisplay spawn(World world,Vec3 origin,String model,int interpolation) {
        return world.spawn(new Location(world,origin.x(),origin.y(),origin.z()),ItemDisplay.class,display->{
            display.setPersistent(false); display.setGravity(false); display.setInvulnerable(true); display.setSilent(true);
            display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            display.setDisplayWidth(0); display.setDisplayHeight(0); display.setShadowRadius(0); display.setShadowStrength(0);
            display.setViewRange(1); display.setTeleportDuration(interpolation); display.setInterpolationDuration(interpolation);
            display.setGlowing(true); model(display,model);
            display.getPersistentDataContainer().set(key,PersistentDataType.BYTE,(byte)1);
        });
    }
    private static void model(ItemDisplay display,String model) {
        var item=new ItemStack(Material.IRON_HORSE_ARMOR);
        var meta=Bukkit.getItemFactory().getItemMeta(item.getType());
        meta.setItemModel(new NamespacedKey("flashlight","beam/"+model)); item.setItemMeta(meta); display.setItemStack(item);
    }
    private void apply(Control control) {
        int packed=word(control.slot,control.power,control.range,control.angle);
        if(control.word!=packed) {
            Color color=Color.fromRGB(packed&255,(packed>>>8)&255,(packed>>>16)&255);
            control.entity.setGlowColorOverride(color);
            if(control.local!=null) control.local.setGlowColorOverride(color);
            control.word=packed;
        }
        var wanted=new Transformation(new Vector3f(),new Quaternionf().rotationTo(0,0,1,(float)control.direction.x(),(float)control.direction.y(),(float)control.direction.z()),
                new Vector3f((float)(1+control.beamVisibility),(float)Math.max(.01,control.visibleRange),1),new Quaternionf().rotationY((float)Math.PI));
        if(!wanted.equals(control.transformation)) {
            control.entity.setInterpolationDelay(-1); control.entity.setTransformation(wanted); control.transformation=wanted;
            if(control.local!=null) { control.local.setInterpolationDelay(-1); control.local.setTransformation(wanted); }
        }
        if(control.appliedOrigin==null || control.appliedOrigin.add(control.origin.scale(-1)).lengthSquared()>1e-8) {
            control.entity.teleport(new Location(control.entity.getWorld(),control.origin.x(),control.origin.y(),control.origin.z()));
            if(control.local!=null) control.local.teleport(new Location(control.local.getWorld(),control.origin.x(),control.origin.y(),control.origin.z()));
            control.appliedOrigin=control.origin;
        }
    }
    void remove(UUID id) {
        Control control=controls.remove(id);
        if(control!=null) { control.entity.remove(); if(control.local!=null) control.local.remove(); }
    }
    void updateShadows(java.util.Collection<? extends org.bukkit.entity.Player> players,FlashlightSettings settings,long tick) {
        if(!enabled || !settings.shadows().enabled()) { shadows.clear(); return; }
        shadows.update(players,controls.entrySet().stream().map(entry->new PlayerShadows.Emitter(entry.getKey(),entry.getValue().entity.getWorld(),
                entry.getValue().origin,entry.getValue().direction,entry.getValue().range,entry.getValue().angle)).toList(),settings,tick);
    }
    void clear() { for(UUID id:java.util.List.copyOf(controls.keySet())) remove(id); shadows.clear(); }
    int count(UUID id) { return controls.containsKey(id) ? 1 : 0; }
}
