package es.mrdino.flashlight.light;

import es.mrdino.flashlight.FlashlightSettings;
import es.mrdino.flashlight.math.Vec3;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Shared, bounded player-body occluders; no dependence on an observer's depth image. */
final class PlayerShadows {
    record Emitter(UUID owner,World world,Vec3 origin,Vec3 direction,double range,double angle) { }
    private static final class Caster {
        final ItemDisplay display;
        final int slot;
        final Set<UUID> viewers=new HashSet<>();
        Vec3 center;
        Transformation transform;
        Caster(ItemDisplay display,int slot) { this.display=display; this.slot=slot; }
    }
    private final Plugin plugin;
    private final NamespacedKey key;
    private final Map<UUID,Caster> casters=new HashMap<>();
    private long lastUpdate=Long.MIN_VALUE;
    PlayerShadows(Plugin plugin) {
        this.plugin=plugin; key=new NamespacedKey(plugin,"shadow_control");
        for(var world:Bukkit.getWorlds()) for(var entity:world.getEntities())
            if(entity instanceof ItemDisplay && entity.getPersistentDataContainer().has(key)) entity.remove();
    }
    static boolean intersects(Emitter light,UUID player,World world,BoundingBox box) {
        if(light.owner.equals(player) || !light.world.equals(world)) return false;
        Vec3 offset=Vec3.of(box.getCenter()).add(light.origin.scale(-1));
        double radius=box.getHeight()*.5+Math.max(box.getWidthX(),box.getWidthZ())*.5;
        double axial=SurfacePatch.dot(offset,light.direction);
        if(axial < -radius || axial>light.range+radius) return false;
        return offset.add(light.direction.scale(-axial)).lengthSquared()
                <=Math.pow(Math.max(0,axial)*Math.tan(Math.toRadians(light.angle*.5))+radius,2);
    }
    void update(Collection<? extends Player> players,List<Emitter> emitters,FlashlightSettings settings,long tick) {
        if(emitters.isEmpty()) { clear(); return; }
        if(lastUpdate!=Long.MIN_VALUE && tick-lastUpdate<settings.shadows().updateInterval()) return;
        lastUpdate=tick;
        Set<UUID> retained=new HashSet<>();
        Map<UUID,Integer> perWorld=new HashMap<>();
        // Stable existing casters keep their slots; new ones are selected in UUID order.
        var ordered=players.stream().filter(player->!player.isDead() && !player.isInvisible() && player.getGameMode()!=GameMode.SPECTATOR)
                .sorted(Comparator.<Player>comparingInt(player->casters.containsKey(player.getUniqueId()) ? 0 : 1)
                        .thenComparing(Player::getUniqueId)).toList();
        for(Player player:ordered) {
            BoundingBox box=player.getBoundingBox();
            if(emitters.stream().noneMatch(light->intersects(light,player.getUniqueId(),player.getWorld(),box))) continue;
            UUID worldId=player.getWorld().getUID();
            int count=perWorld.getOrDefault(worldId,0);
            if(count>=settings.shadows().maxCasters()) continue;
            perWorld.put(worldId,count+1); retained.add(player.getUniqueId());
            Caster caster=casters.get(player.getUniqueId());
            if(caster!=null && (!caster.display.isValid() || !caster.display.getWorld().equals(player.getWorld()))) {
                caster.display.remove(); casters.remove(player.getUniqueId()); caster=null;
            }
            if(caster==null) {
                int occupied=0;
                for(Caster other:casters.values()) if(other.display.getWorld().equals(player.getWorld())) occupied|=1<<other.slot;
                int slot=0; while(slot<8 && (occupied & 1<<slot)!=0) slot++;
                if(slot==8) continue;
                caster=new Caster(spawn(player,settings.tracking().interpolationTicks()),slot);
                caster.display.setGlowColorOverride(Color.fromRGB(slot,0,0)); casters.put(player.getUniqueId(),caster);
            }
            Vec3 center=Vec3.of(box.getCenter());
            if(caster.center==null || caster.center.add(center.scale(-1)).lengthSquared()>1e-8) {
                caster.display.teleport(new Location(player.getWorld(),center.x(),center.y(),center.z())); caster.center=center;
            }
            float radius=(float)(Math.max(box.getWidthX(),box.getWidthZ())*.5);
            var transform=new Transformation(new Vector3f(),new Quaternionf(),
                    new Vector3f(Math.max(.05f,radius*2),(float)Math.max(.1,box.getHeight()),1),new Quaternionf().rotationY((float)Math.PI));
            if(!transform.equals(caster.transform)) {
                caster.display.setInterpolationDelay(-1); caster.display.setTransformation(transform); caster.transform=transform;
            }
            // A hidden/vanished player must not leak through a technical caster.
            Set<UUID> visible=new HashSet<>();
            for(Player viewer:players) if(viewer.getWorld().equals(player.getWorld()) && viewer.canSee(player)) {
                visible.add(viewer.getUniqueId());
                if(caster.viewers.add(viewer.getUniqueId())) viewer.showEntity(plugin,caster.display);
            }
            for(UUID old:Set.copyOf(caster.viewers)) if(!visible.contains(old)) {
                Player viewer=Bukkit.getPlayer(old); if(viewer!=null) viewer.hideEntity(plugin,caster.display);
                caster.viewers.remove(old);
            }
        }
        for(UUID id:Set.copyOf(casters.keySet())) if(!retained.contains(id)) casters.remove(id).display.remove();
    }
    private ItemDisplay spawn(Player player,int interpolation) {
        return player.getWorld().spawn(player.getLocation(),ItemDisplay.class,display->{
            display.setPersistent(false); display.setGravity(false); display.setInvulnerable(true); display.setSilent(true);
            display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            display.setDisplayWidth(0); display.setDisplayHeight(0); display.setShadowRadius(0); display.setShadowStrength(0);
            display.setViewRange(1); display.setTeleportDuration(interpolation); display.setInterpolationDuration(interpolation);
            display.setVisibleByDefault(false); display.setGlowing(true);
            var item=new ItemStack(Material.IRON_HORSE_ARMOR); var meta=Bukkit.getItemFactory().getItemMeta(item.getType());
            meta.setItemModel(new NamespacedKey("flashlight","beam/shadow_control")); item.setItemMeta(meta); display.setItemStack(item);
            display.getPersistentDataContainer().set(key,PersistentDataType.BYTE,(byte)1);
        });
    }
    void clear() { casters.values().forEach(caster->caster.display.remove()); casters.clear(); lastUpdate=Long.MIN_VALUE; }
}
