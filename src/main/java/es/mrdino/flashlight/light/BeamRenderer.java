package es.mrdino.flashlight.light;

import es.mrdino.flashlight.FlashlightSettings;
import es.mrdino.flashlight.math.Vec3;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MainHand;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** One optical controller; a volume and receiving faces remain an optional fallback. */
public final class BeamRenderer {
    private static final Quaternionf ITEM_ROTATION=new Quaternionf().rotationY((float)Math.PI);
    private final NamespacedKey entityKey;
    private final Plugin plugin;
    private final SurfaceSampler sampler=new SurfaceSampler();
    private final HeldItemPose heldPose;
    private final EntityIllumination entityLight;
    private final Map<UUID,Visual> visuals=new HashMap<>();

    /** Cache our own state: reading/cloning Bukkit item metadata every tick is expensive. */
    private static final class Carrier {
        final ItemDisplay entity;
        String model;
        Color color;
        Display.Brightness brightness;
        Transformation transformation;
        Location location;
        SurfacePatch face;
        Carrier(ItemDisplay entity) { this.entity=entity; }
        void hide() {
            if(model!=null) { entity.setItemStack(new ItemStack(Material.AIR)); model=null; color=null; }
        }
        void remove() { entity.remove(); }
    }
    private static final class Visual {
        final Carrier outer;
        final World world;
        final List<Carrier> surfaces=new ArrayList<>();
        final Map<SurfacePatch.Plane,SurfaceProjection.State> planes=new HashMap<>();
        Vec3 direction,lastEye,scanOrigin,scanDirection;
        long lastPose=Long.MIN_VALUE,lastScan=Long.MIN_VALUE;
        boolean dirty=true;
        SurfaceSampler.Result geometry;
        BeamSampler.Context collisions;
        long cacheTick;
        FlashlightSettings settings;
        Visual(World world,Carrier outer) { this.world=world; this.outer=outer; }
        void hide() { if(outer!=null) outer.hide(); surfaces.forEach(Carrier::hide); }
        void remove() { if(outer!=null) outer.remove(); surfaces.forEach(Carrier::remove); }
        boolean valid() { return (outer==null || outer.entity.isValid()) && surfaces.stream().allMatch(c->c.entity.isValid()); }
    }

    public BeamRenderer(Plugin plugin) {
        this.plugin=plugin;
        entityKey=new NamespacedKey(plugin,"beam_display"); heldPose=HeldItemPose.load(plugin); entityLight=new EntityIllumination(plugin);
        for(var world:Bukkit.getWorlds()) for(var entity:world.getEntities())
            if(entity instanceof ItemDisplay && entity.getPersistentDataContainer().has(entityKey)) entity.remove();
    }

    public void update(Player player,EquipmentSlot hand,double intensity,FlashlightSettings settings,long tick) {
        Visual visual=visuals.get(player.getUniqueId());
        World world=player.getWorld();
        if(visual!=null && (!visual.valid() || !visual.world.equals(world))) {
            remove(player.getUniqueId()); visual=null;
        }
        double power=Math.clamp(intensity*settings.brightness()/15.0,0,1);
        if(power<=0) { if(visual!=null) visual.hide(); entityLight.remove(player.getUniqueId()); return; }
        Location eyeLocation=player.getEyeLocation();
        if(visual==null) {
            visual=new Visual(world,entityLight.enabled() ? null : spawn(player,eyeLocation,1,settings)); visuals.put(player.getUniqueId(),visual);
        }
        Vec3 eye=Vec3.of(eyeLocation.toVector()),wanted=Vec3.of(eyeLocation.getDirection()).normalized();
        double walking=visual.lastEye==null ? 0 : Math.min(1,Math.sqrt(eye.add(visual.lastEye.scale(-1)).lengthSquared())*7);
        wanted=wanted.add(wanted.right().scale(Math.sin(tick*.31)*settings.walkingSway()*walking))
                .add(new Vec3(0,Math.sin(tick*.073)*settings.breathing(),0)).normalized();
        visual.direction=visual.direction==null || settings.directFollow() ? wanted
                : visual.direction.follow(wanted,1-Math.pow(1-settings.smoothing(),Math.max(1,tick-visual.lastPose)));
        boolean left=player.getMainHand()==MainHand.LEFT;
        if(hand==EquipmentSlot.OFF_HAND) left=!left;
        HeldItemPose.Ray ray=heldPose.world(eye,visual.direction,eyeLocation.getYaw(),left);
        Vec3 origin=ray.origin(),direction=ray.direction();
        double turn=visual.scanDirection==null ? -1 : SurfacePatch.dot(direction,visual.scanDirection);
        double travel=visual.scanOrigin==null ? Double.POSITIVE_INFINITY : origin.add(visual.scanOrigin.scale(-1)).lengthSquared();
        long elapsed=tick-visual.lastScan;
        boolean moved=travel>.0016 || turn<.99995;
        if(visual.geometry==null || visual.dirty || visual.settings!=settings || turn<.985 || travel>.25
                || elapsed>=10 || moved && elapsed>=settings.updateInterval()) {
            if(visual.collisions==null || visual.dirty || visual.settings!=settings || tick-visual.cacheTick>=10
                    || visual.collisions.cells.size()>4096) {
                visual.collisions=new BeamSampler.Context(world,settings.waterproof()); visual.cacheTick=tick;
            }
            visual.geometry=sampler.sample(visual.collisions,origin,direction,heldPose.lensRadius(),settings,
                    visual.geometry==null ? List.of() : visual.geometry.patches(),!entityLight.enabled());
            visual.scanOrigin=origin; visual.scanDirection=direction; visual.lastScan=tick;
            visual.dirty=false; visual.settings=settings;
        }
        double length=Math.max(.01,visual.geometry.visibleRange());
        entityLight.update(player,origin,direction,settings.coneAngle(),settings.range(),power,length,settings.beamVisibility(),settings,left);
        if(entityLight.enabled()) {
            visual.lastEye=eye; visual.lastPose=tick; return;
        }
        if(length<.08) { visual.hide(); return; }
        double diameter=BeamMetadata.carrierDiameter(length,2*heldPose.lensRadius()
                +2*Math.tan(Math.toRadians(settings.coneAngle()*.5))*length);
        Quaternionf rotation=orientation(direction);
        Location location=location(world,origin);
        transform(visual.outer,location,rotation,new Vector3f((float)diameter,(float)diameter,(float)length));
        // Each receiving face clips its own projection. A foreground hit must not truncate the whole cone.
        double airPower=Math.clamp(power*settings.beamVisibility(),0,1);
        BeamMetadata data=BeamMetadata.encode(length,diameter,airPower,new Vector3f(0,0,-1));
        int reach=(int)Math.round(settings.range());
        if(airPower>0) item(visual.outer,"volume_0_both_"+reach,data.color(),data.brightness()); else visual.outer.hide();
        List<SurfacePatch> faces=visual.geometry.patches();
        if(faces.isEmpty()) {
            visual.surfaces.forEach(Carrier::hide); visual.planes.clear();
            visual.lastEye=eye; visual.lastPose=tick; return;
        }
        // Area-weighted photometry is invariant when a wall splits/merges.
        // Smooth only this geometric attenuation, never command/fault intensity.
        Map<SurfacePatch.Plane,Vec3> centres=new HashMap<>();
        Map<SurfacePatch.Plane,Double> areas=new HashMap<>();
        for(SurfacePatch face:faces) {
            double area=face.width()*face.height();
            centres.merge(face.planeKey(),face.center().scale(area),Vec3::add);
            areas.merge(face.planeKey(),area,Double::sum);
        }
        for(SurfacePatch face:faces) {
            Vec3 weighted=centres.remove(face.planeKey());
            if(weighted==null) continue;
            var state=visual.planes.computeIfAbsent(face.planeKey(),ignored->new SurfaceProjection.State());
            double fade=SurfaceProjection.attenuation(face,weighted.scale(1/areas.get(face.planeKey())),origin,direction,settings.range());
            state.prepareFade(fade,tick);
        }
        visual.planes.values().removeIf(state->tick-state.lastSeen()>40);
        int[] slots=SurfaceAssignments.match(visual.surfaces.stream().map(carrier->carrier.face).toList(),faces);
        boolean[] used=new boolean[Math.max(visual.surfaces.size(),faces.size())];
        for(int index=0;index<faces.size();index++) {
            SurfacePatch face=faces.get(index);
            var projection=SurfaceProjection.of(face,origin,direction,heldPose.lensRadius(),settings.coneAngle(),settings.range(),power,
                    visual.planes.get(face.planeKey()));
            if(projection==null) continue;
            int slot=slots[index];
            while(slot>=visual.surfaces.size()) visual.surfaces.add(spawn(player,location,0,settings));
            Carrier carrier=visual.surfaces.get(slot); carrier.face=face; used[slot]=true;
            Vec3 u=face.u(),v=face.v(),n=face.normal();
            Quaternionf q=new Quaternionf().setFromNormalized(new Matrix3f(
                    (float)u.x(),(float)u.y(),(float)u.z(),(float)v.x(),(float)v.y(),(float)v.z(),(float)n.x(),(float)n.y(),(float)n.z()));
            transform(carrier,location(world,face.center().add(n.scale(.006))),q,
                    new Vector3f((float)face.width(),(float)face.height(),1));
            item(carrier,projection.model(),projection.color(),projection.brightness());
        }
        for(int slot=0;slot<visual.surfaces.size();slot++) if(!used[slot]) visual.surfaces.get(slot).hide();
        visual.lastEye=eye; visual.lastPose=tick;
    }

    private Carrier spawn(Player owner,Location location,int interpolation,FlashlightSettings settings) {
        Location neutral=location.clone(); neutral.setYaw(0); neutral.setPitch(0);
        ItemDisplay display=location.getWorld().spawn(neutral,ItemDisplay.class,entity->{
            entity.setPersistent(false); entity.setGravity(false); entity.setInvulnerable(true); entity.setSilent(true);
            entity.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            entity.setShadowRadius(0); entity.setShadowStrength(0); entity.setViewRange(1);
            entity.setDisplayWidth(0); entity.setDisplayHeight(0);
            if(!settings.tracking().shared()) entity.setVisibleByDefault(false);
            // Receiving faces belong to fixed world geometry. Interpolating
            // their bounds while tint changes immediately creates gaps, overlap
            // and wrong projection coordinates during every rescan.
            entity.setTeleportDuration(interpolation); entity.setInterpolationDuration(interpolation);
            entity.getPersistentDataContainer().set(entityKey,PersistentDataType.BYTE,(byte)1);
        });
        if(!settings.tracking().shared() && owner.isOnline()) owner.showEntity(plugin,display);
        return new Carrier(display);
    }
    private static void item(Carrier carrier,String model,Color color,Display.Brightness brightness) {
        if(!model.equals(carrier.model) || !color.equals(carrier.color)) {
            ItemStack stack=new ItemStack(Material.IRON_HORSE_ARMOR);
            var meta=Objects.requireNonNull(Bukkit.getItemFactory().getItemMeta(stack.getType()));
            meta.setItemModel(new NamespacedKey("flashlight","beam/"+model));
            var component=meta.getCustomModelDataComponent(); component.setColors(List.of(color));
            meta.setCustomModelDataComponent(component); stack.setItemMeta(meta); carrier.entity.setItemStack(stack);
            carrier.model=model; carrier.color=color;
        }
        if(!brightness.equals(carrier.brightness)) { carrier.entity.setBrightness(brightness); carrier.brightness=brightness; }
    }
    private static void transform(Carrier carrier,Location location,Quaternionf rotation,Vector3f scale) {
        if(carrier.location==null || carrier.location.distanceSquared(location)>1e-7) {
            carrier.entity.teleport(location); carrier.location=location.clone();
        }
        Transformation wanted=new Transformation(new Vector3f(),new Quaternionf(rotation),scale,new Quaternionf(ITEM_ROTATION));
        if(!wanted.equals(carrier.transformation)) {
            carrier.entity.setInterpolationDelay(-1); carrier.entity.setTransformation(wanted); carrier.transformation=wanted;
        }
    }
    private static Quaternionf orientation(Vec3 direction) { return new Quaternionf().rotationTo(0,0,1,(float)direction.x(),(float)direction.y(),(float)direction.z()); }
    private static Location location(World world,Vec3 point) { return new Location(world,point.x(),point.y(),point.z()); }

    /** Called after nearby block edits. Other world changes are picked up by the ten-tick cache TTL. */
    public void invalidate(Location location) {
        Vec3 point=Vec3.of(location.toVector());
        for(Visual visual:visuals.values()) if(visual.scanOrigin!=null && visual.world.equals(location.getWorld())
                && point.add(visual.scanOrigin.scale(-1)).lengthSquared()<Math.pow(visual.settings.range()+12,2)) visual.dirty=true;
    }
    public void remove(UUID player) { Visual visual=visuals.remove(player); if(visual!=null) visual.remove(); entityLight.remove(player); }
    public void clear() { visuals.values().forEach(Visual::remove); visuals.clear(); entityLight.clear(); }
    public int activeCount() { return visuals.size(); }
    public int screenLightCount(UUID player) { return entityLight.count(player); }
    public void updateShadows(java.util.Collection<? extends Player> players,FlashlightSettings settings,long tick) {
        entityLight.updateShadows(players,settings,tick);
    }
}
