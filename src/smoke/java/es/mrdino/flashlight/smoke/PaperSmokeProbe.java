package es.mrdino.flashlight.smoke;

import es.mrdino.flashlight.FlashlightPlugin;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.zip.ZipFile;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.MainHand;
import org.bukkit.plugin.java.JavaPlugin;

/** Executed only in the isolated localhost Paper smoke server, never shipped. */
public final class PaperSmokeProbe extends JavaPlugin {
    @Override public void onEnable() {
        Bukkit.getScheduler().runTaskLater(this, this::check, 5);
    }

    private void check() {
        try {
            FlashlightPlugin plugin = (FlashlightPlugin) Bukkit.getPluginManager().getPlugin("FlashLight");
            require(plugin != null && plugin.isEnabled(), "Plugin enabled on real Paper");
            var flashlight=plugin.items().flashlight();
            require(plugin.items().isFlashlight(flashlight) && plugin.items().charge(flashlight)==1,"Native item components and persistent flashlight data");
            plugin.items().update(flashlight,true,.45,"on",.6,plugin.settings());
            require(plugin.items().enabled(flashlight) && flashlight.getItemMeta().getCustomModelDataComponent().getFloats().size()==7,"Native model, state and float components");
            require(plugin.items().isBattery(plugin.items().battery(4)),"Native battery item");
            var world = Bukkit.getWorlds().getFirst();
            // Newer Minecraft versions no longer keep the old spawn-chunk area
            // loaded. The fixture must load its rays' chunks explicitly; the
            // renderer intentionally stops at an unloaded chunk boundary.
            for(int x=-2;x<=2;x++) for(int z=-2;z<=2;z++) world.getChunkAt(x,z).addPluginChunkTicket(this);
            // Reset only the fixture in this isolated smoke world, including prior floor/cube checks.
            for(int x=-6;x<=6;x++) for(int y=60;y<72;y++) for(int z=2;z<=12;z++)
                world.getBlockAt(x,y,z).setType(Material.AIR,false);
            for (int x=-6;x<=6;x++) for(int y=60;y<72;y++) world.getBlockAt(x,y,8).setType(Material.STONE,false);
            UUID id=UUID.randomUUID();
            Location[] camera={new Location(world,0,65.62,0,0,0)};
            Player viewpoint=(Player)Proxy.newProxyInstance(Player.class.getClassLoader(),new Class<?>[]{Player.class},(proxy,method,args)-> switch(method.getName()) {
                case "getUniqueId" -> id;
                case "getWorld" -> world;
                case "getMainHand" -> MainHand.RIGHT;
                case "getGameMode" -> org.bukkit.GameMode.SURVIVAL;
                case "isDead", "isInvisible" -> false;
                case "canSee" -> true;
                case "getBoundingBox" -> new org.bukkit.util.BoundingBox(-.3,64,-.3,.3,65.8,.3);
                case "isOnline" -> true;
                case "showEntity", "hideEntity" -> null; // No fake network connection; visibility is checked separately.
                case "getEyeLocation" -> camera[0].clone();
                case "hashCode" -> id.hashCode();
                case "equals" -> proxy==args[0];
                case "toString" -> "FlashLight isolated test viewpoint";
                default -> throw new UnsupportedOperationException(method.getName());
            });
            plugin.getConfig().set("light.entities.enabled",false);
            var beams=new es.mrdino.flashlight.light.BeamRenderer(plugin);
            for(int i=0;i<100;i++) beams.update(viewpoint,EquipmentSlot.HAND,1,plugin.settings(),i);
            long begin=System.nanoTime();
            for(int i=0;i<5000;i++) beams.update(viewpoint,EquipmentSlot.HAND,1,plugin.settings(),i+100);
            Files.writeString(Path.of("cpu-"+plugin.getPluginMeta().getVersion()+".txt"),
                    "5000 stationary wall updates ms="+(System.nanoTime()-begin)/1_000_000.0+"\n");
            begin=System.nanoTime();
            for(int i=0;i<2000;i++) {
                camera[0].setYaw((float)(Math.sin(i*.03)*12)); camera[0].setX(Math.sin(i*.015)*.6);
                beams.update(viewpoint,EquipmentSlot.HAND,1,plugin.settings(),i+5100);
            }
            Files.writeString(Path.of("cpu-"+plugin.getPluginMeta().getVersion()+".txt"),
                    "2000 moving wall updates ms="+(System.nanoTime()-begin)/1_000_000.0+"\n",java.nio.file.StandardOpenOption.APPEND);
            camera[0].setX(0); camera[0].setYaw(0);
            beams.remove(id);
            beams.update(viewpoint,EquipmentSlot.HAND,1,plugin.settings(),100);
            var displays=world.getEntitiesByClass(ItemDisplay.class);
            require(displays.size()>=2 && displays.size()<=13,"Bounded real ItemDisplays, no particles");
            var impact=displays.stream().filter(entity-> !entity.getItemStack().getType().isAir())
                    .filter(entity->entity.getItemStack().getItemMeta().getItemModel().getKey().startsWith("beam/surface_")).findFirst().orElseThrow();
            require(Math.abs(impact.getLocation().getZ()-7.994)<.025,"Real block collision and footprint normal");
            require(impact.getTransformation().getScale().x>1,"Projected footprint is scaled");
            require(impact.getInterpolationDuration()==0 && impact.getTeleportDuration()==0,"Fixed wall faces cannot interpolate through other surfaces");
            // Invisible administrative hitboxes must neither clip the volume nor receive patches.
            long tick=101;
            for(Material invisible:new Material[]{Material.BARRIER,Material.LIGHT,Material.STRUCTURE_VOID}) {
                for(int x=-3;x<=3;x++) for(int y=62;y<=69;y++) world.getBlockAt(x,y,4).setType(invisible,false);
                beams.invalidate(new Location(world,0,65,4));
                beams.update(viewpoint,EquipmentSlot.HAND,1,plugin.settings(),tick++);
                displays=world.getEntitiesByClass(ItemDisplay.class);
                require(hasSurface(displays,8) && !hasSurface(displays,4),"Light passes through "+invisible+" to the visible wall");
            }
            for(int x=-3;x<=3;x++) for(int y=62;y<=69;y++) world.getBlockAt(x,y,4).setType(Material.AIR,false);
            beams.invalidate(new Location(world,0,65,4));
            beams.update(viewpoint,EquipmentSlot.HAND,1,plugin.settings(),104);
            var wallFaces=surfaceIdentities(world.getEntitiesByClass(ItemDisplay.class));
            for(int motion=0;motion<20;motion++) {
                camera[0].setYaw((float)(Math.sin(motion*.5)*2));
                beams.update(viewpoint,EquipmentSlot.HAND,1,plugin.settings(),105+motion);
                var nextFaces=surfaceIdentities(world.getEntitiesByClass(ItemDisplay.class));
                for(var face:nextFaces.entrySet()) if(wallFaces.containsKey(face.getKey()))
                    require(wallFaces.get(face.getKey()).equals(face.getValue()),"An unchanged wall face retains its display through movement");
                require(world.getEntitiesByClass(ItemDisplay.class).size()<=1+plugin.settings().maxSurfacePatches(),"Geometry changes reuse a bounded display pool");
                wallFaces=nextFaces;
            }
            camera[0].setYaw(0);
            beams.update(viewpoint,EquipmentSlot.HAND,1,plugin.settings(),125);
            displays=world.getEntitiesByClass(ItemDisplay.class);
            double full=intensity(displays);
            beams.update(viewpoint,EquipmentSlot.HAND,.15,plugin.settings(),126);
            double dim=intensity(displays);
            require(Math.abs(dim-full*.15)<.006,"Partial intensity reaches real entity metadata immediately");
            beams.update(viewpoint,EquipmentSlot.HAND,0,plugin.settings(),127);
            require(displays.stream().allMatch(entity->entity.getItemStack().getType().isAir()),"Blackout hides beam and impact immediately");
            world.getBlockAt(-1,65,4).setType(Material.STONE,false);
            beams.invalidate(new Location(world,-1,65,4));
            beams.update(viewpoint,EquipmentSlot.HAND,1,plugin.settings(),128);
            displays=world.getEntitiesByClass(ItemDisplay.class);
            require(hasSurface(displays,4),"Foreground face is lit");
            require(hasSurface(displays,8),"Background wall is lit at the same time");
            for(int x=-6;x<=6;x++) for(int z=2;z<=12;z++) world.getBlockAt(x,64,z).setType(Material.STONE,false);
            beams.invalidate(new Location(world,0,64,5));
            beams.update(viewpoint,EquipmentSlot.HAND,1,plugin.settings(),129);
            displays=world.getEntitiesByClass(ItemDisplay.class);
            require(displays.stream().anyMatch(entity->!entity.getItemStack().getType().isAir()
                    && entity.getItemStack().getItemMeta().getItemModel().getKey().startsWith("beam/surface_h_")
                    && Math.abs(entity.getLocation().getY()-65.006)<.01),"Cone edge also lights the grazing floor");
            require(displays.size()<=1+plugin.settings().maxSurfacePatches(),"Display pool respects its hard cap");
            camera[0].setYaw(90);
            beams.update(viewpoint,EquipmentSlot.HAND,1,plugin.settings(),130);
            var outer=displays.stream().filter(entity->!entity.getItemStack().getType().isAir())
                    .filter(entity->entity.getItemStack().getItemMeta().getItemModel().getKey().startsWith("beam/volume_0")).findFirst().orElseThrow();
            var forward=outer.getTransformation().getLeftRotation().transform(new org.joml.Vector3f(0,0,1));
            require(forward.x<-.98 && Math.abs(forward.z)<.08,"Turn follows the player on the very next tick");
            require(outer.getInterpolationDuration()==1 && outer.getTeleportDuration()==1,"One tick interpolation, no stacked smoothing");
            beams.remove(id);
            require(world.getEntitiesByClass(ItemDisplay.class).isEmpty(),"Off removes every display");
            require(plugin.lights().activeCount()==0,"No temporary world light sources");
            // Precise mode supplies one private optical controller, never light cells
            // or metadata changes on the mob/player being illuminated.
            plugin.getConfig().set("light.entities.enabled",true);
            var precise=new es.mrdino.flashlight.light.BeamRenderer(plugin);
            camera[0].setYaw(0);
            var zombie=world.spawn(new Location(world,0,65,5),org.bukkit.entity.Zombie.class);
            zombie.setAI(false); zombie.setGravity(false);
            int beforeLight=world.getBlockAt(0,66,5).getLightFromBlocks();
            precise.update(viewpoint,EquipmentSlot.HAND,1,plugin.settings(),131);
            require(precise.screenLightCount(id)==1,"Optical controller exists independently of mob types");
            var control=world.getEntitiesByClass(ItemDisplay.class).stream()
                    .filter(entity->entity.getItemStack().getItemMeta().getItemModel().getKey().equals("beam/entity_control")).findFirst().orElseThrow();
            require(control.isGlowing() && control.getGlowColorOverride()!=null,"Vanilla outline pass transports only private optical data");
            require(!zombie.isGlowing() && world.getBlockAt(0,66,5).getLightFromBlocks()==beforeLight,"Mob and nearby block illumination stay authoritative and unchanged");
            require(world.getEntitiesByClass(ItemDisplay.class).size()==2,"Shared and private camera controllers keep geometry depth intact");
            for(double visibility:new double[]{0,3}) {
                plugin.getConfig().set("light.beam-visibility",visibility);
                precise.update(viewpoint,EquipmentSlot.HAND,1,es.mrdino.flashlight.FlashlightSettings.load(plugin.getConfig()),132);
                require(Math.abs(control.getTransformation().getScale().x()-(1+visibility))<.00001,
                        "Air visibility reaches native display metadata without turning off direct light");
            }
            var shadowPlayers=new java.util.ArrayList<Player>(); shadowPlayers.add(viewpoint);
            for(int caster=0;caster<12;caster++) {
                UUID casterId=UUID.randomUUID(); double z=3+caster*.1;
                shadowPlayers.add((Player)Proxy.newProxyInstance(Player.class.getClassLoader(),new Class<?>[]{Player.class},(proxy,method,args)->switch(method.getName()) {
                    case "getUniqueId" -> casterId;
                    case "getWorld" -> world;
                    case "getGameMode" -> org.bukkit.GameMode.SURVIVAL;
                    case "isDead", "isInvisible" -> false;
                    case "canSee" -> true;
                    case "showEntity", "hideEntity" -> null;
                    case "getLocation" -> new Location(world,0,64,z);
                    case "getBoundingBox" -> new org.bukkit.util.BoundingBox(-.3,64,z-.3,.3,65.8,z+.3);
                    default -> throw new UnsupportedOperationException(method.getName());
                }));
            }
            precise.updateShadows(shadowPlayers,plugin.settings(),133);
            require(world.getEntitiesByClass(ItemDisplay.class).stream().filter(display->display.getItemStack().getItemMeta().getItemModel().getKey().equals("beam/shadow_control")).count()==8,
                    "Native shared body-shadow controls enforce the per-world cap");
            camera[0].setYaw(180); precise.update(viewpoint,EquipmentSlot.HAND,1,plugin.settings(),134);
            var axis=control.getTransformation().getLeftRotation().transform(new org.joml.Vector3f(0,0,1));
            require(axis.z<-.98,"Optical data follows the actual beam direction");
            precise.update(viewpoint,EquipmentSlot.HAND,0,plugin.settings(),135);
            precise.updateShadows(shadowPlayers,plugin.settings(),135);
            require(precise.screenLightCount(id)==0 && !control.isValid(),"Blackout removes the optical data immediately");
            require(!zombie.isGlowing() && world.getBlockAt(0,66,5).getLightFromBlocks()==beforeLight,"No local emitter remains after off");
            zombie.remove(); precise.remove(id);
            camera[0].setX(0); camera[0].setYaw(0);
            for(int i=0;i<100;i++) precise.update(viewpoint,EquipmentSlot.HAND,1,plugin.settings(),i+10000);
            begin=System.nanoTime();
            for(int i=0;i<5000;i++) precise.update(viewpoint,EquipmentSlot.HAND,1,plugin.settings(),i+10100);
            double stationary=(System.nanoTime()-begin)/1_000_000.0;
            begin=System.nanoTime();
            for(int i=0;i<2000;i++) {
                camera[0].setYaw((float)(Math.sin(i*.03)*12)); camera[0].setX(Math.sin(i*.015)*.6);
                precise.update(viewpoint,EquipmentSlot.HAND,1,plugin.settings(),i+15100);
            }
            double moving=(System.nanoTime()-begin)/1_000_000.0;
            Files.writeString(Path.of("cpu-"+plugin.getPluginMeta().getVersion()+".txt"),
                    "5000 stationary precise updates ms="+stationary+"; average ms/update="+stationary/5000+"\n"
                    +"2000 moving precise updates ms="+moving+"; average ms/update="+moving/2000+"\n",java.nio.file.StandardOpenOption.APPEND);
            precise.remove(id);
            require(Bukkit.dispatchCommand(Bukkit.getConsoleSender(),"flashlight help"),"Native Paper command registration");
            try(ZipFile zip=new ZipFile(plugin.getDataFolder().toPath().resolve("FlashLight-ResourcePack.zip").toFile())) {
                require(zip.getEntry("assets/minecraft/shaders/core/entity.vsh")!=null,"Core shaders embedded in exported resource pack");
                require(zip.getEntry("assets/flashlight/shaders/include/beam_fragment.glsl")!=null,"Analytic volume inside the same ZIP");
                require(zip.getEntry("assets/flashlight/shaders/post/control_copy.fsh")!=null,"Compact metadata pass inside the same ZIP");
                require(zip.getEntry("assets/flashlight/shaders/post/outline.fsh")!=null,"Merged outline mask inside the same ZIP");
            }
            plugin.reloadFlashlight();
            require(plugin.isEnabled(),"Reload keeps plugin enabled");
            Files.writeString(Path.of("result.txt"),"PASS: real Paper "+Bukkit.getMinecraftVersion()+"; native items; commands; reload; stable wall reuse; invisible blocks ignored; foreground/background; grazing floor; bounded displays; next-tick turning; dimmer; blackout; cleanup; shared/private optical controls; eight shared body shadows; adjustable air visibility; unchanged entity metadata and block light; one resource pack.\n");
            getLogger().info("FlashLight PAPER SMOKE PASS");
        } catch(Throwable failure) {
            getLogger().log(java.util.logging.Level.SEVERE,"FlashLight PAPER SMOKE FAIL",failure);
            try { Files.writeString(Path.of("result.txt"),"FAIL: "+failure+"\n"); } catch(Exception ignored) { }
        } finally { Bukkit.shutdown(); }
    }

    private static boolean hasSurface(java.util.Collection<ItemDisplay> displays,double z) {
        return displays.stream().anyMatch(entity->!entity.getItemStack().getType().isAir()
                && entity.getItemStack().getItemMeta().getItemModel().getKey().startsWith("beam/surface_")
                && Math.abs(entity.getLocation().getZ()-(z-.006))<.01);
    }

    private static java.util.Map<String,UUID> surfaceIdentities(java.util.Collection<ItemDisplay> displays) {
        var result=new java.util.HashMap<String,UUID>();
        for(var display:displays) {
            if(display.getItemStack().getType().isAir() || !display.getItemStack().getItemMeta().getItemModel().getKey().startsWith("beam/surface_")) continue;
            var position=display.getLocation(); var transform=display.getTransformation();
            String key=position.getX()+","+position.getY()+","+position.getZ()+":"+transform.getScale()+":"+transform.getLeftRotation();
            require(result.put(key,display.getUniqueId())==null,"No two active displays overlap the same receiving face");
        }
        return result;
    }

    private static double intensity(java.util.Collection<ItemDisplay> displays) {
        var entity=displays.stream().filter(display-> !display.getItemStack().getType().isAir())
                .filter(display->display.getItemStack().getItemMeta().getItemModel().getKey().startsWith("beam/volume_0")).findFirst().orElseThrow();
        var color=entity.getItemStack().getItemMeta().getCustomModelDataComponent().getColors().getFirst();
        long word=color.getRed()|(long)color.getGreen()<<8|(long)color.getBlue()<<16
                |(long)entity.getBrightness().getBlockLight()<<24|(long)entity.getBrightness().getSkyLight()<<28;
        return (word>>>12&255)/255.0;
    }
    private static void require(boolean value,String message) { if(!value) throw new AssertionError(message); }
}
