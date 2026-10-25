package es.mrdino.flashlight.light;

import es.mrdino.flashlight.FlashlightSettings;
import es.mrdino.flashlight.math.Vec3;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFactory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.BoundingBox;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MultiplayerControlsTest {
    @Test void privateCameraCopiesAreOnlyShownToTheirOwnerAndFollowHandChanges() {
        try(var fixture=new Fixture()) {
            var lights=new EntityIllumination(fixture.plugin);
            var owner=fixture.player("Owner",0);
            lights.update(owner,new Vec3(0,65,0),new Vec3(0,0,1),28,20,.8,20,1,fixture.settings(),false);
            assertEquals(2,fixture.displays.size());
            ItemDisplay shared=fixture.displays.get(0),local=fixture.displays.get(1);
            verify(owner).hideEntity(fixture.plugin,shared); verify(owner).showEntity(fixture.plugin,local);
            verify(local).setVisibleByDefault(false); verify(shared,never()).setVisibleByDefault(false);
            assertEquals(shared.getGlowColorOverride(),local.getGlowColorOverride());
            lights.update(owner,new Vec3(0,65,0),new Vec3(0,0,1),28,20,.8,20,1,fixture.settings(),true);
            verify(fixture.metas.getLast()).setItemModel(new NamespacedKey("flashlight","beam/entity_control_left"));
            lights.remove(owner.getUniqueId()); verify(shared).remove(); verify(local).remove();
        }
    }
    @Test void sharedPlayerShadowsReachObserversAndDisappearWhenTheCasterHidesOrTheLightStops() {
        try(var fixture=new Fixture()) {
            var lights=new EntityIllumination(fixture.plugin);
            var owner=fixture.player("Owner",0); var other=fixture.player("Caster",3);
            lights.update(owner,new Vec3(0,65,0),new Vec3(0,0,1),28,20,.8,20,1,fixture.settings(),false);
            lights.updateShadows(List.of(owner,other),fixture.settings(),1);
            assertEquals(3,fixture.displays.size()); ItemDisplay caster=fixture.displays.getLast();
            verify(caster).setVisibleByDefault(false); verify(owner).showEntity(fixture.plugin,caster); verify(other).showEntity(fixture.plugin,caster);
            when(other.isInvisible()).thenReturn(true); lights.updateShadows(List.of(owner,other),fixture.settings(),3);
            verify(caster).remove();
            when(other.isInvisible()).thenReturn(false); lights.updateShadows(List.of(owner,other),fixture.settings(),5);
            ItemDisplay replacement=fixture.displays.getLast(); assertNotSame(caster,replacement);
            lights.remove(owner.getUniqueId()); lights.updateShadows(List.of(owner,other),fixture.settings(),6); verify(replacement).remove();
        }
    }
    @Test void sharingAndCameraTrackingCanBeDisabledAndCasterCountsStayBounded() {
        try(var fixture=new Fixture()) {
            fixture.config.set("light.shared-light",false); fixture.config.set("light.local-camera-tracking",false);
            var lights=new EntityIllumination(fixture.plugin); var owner=fixture.player("Owner",0);
            lights.update(owner,new Vec3(0,65,0),new Vec3(0,0,1),28,20,.8,20,1,fixture.settings(),false);
            assertEquals(1,fixture.displays.size()); verify(fixture.displays.getFirst()).setVisibleByDefault(false);
            verify(owner).showEntity(fixture.plugin,fixture.displays.getFirst());
            var players=new ArrayList<Player>(); players.add(owner);
            for(int index=0;index<20;index++) players.add(fixture.player("Caster"+index,3+index*.1));
            lights.updateShadows(players,fixture.settings(),1);
            assertEquals(9,fixture.displays.size(),"One light plus at most eight shared casters per world");
            fixture.config.set("light.shadows.enabled",false); lights.updateShadows(players,fixture.settings(),3);
            fixture.displays.stream().skip(1).forEach(display->verify(display).remove()); lights.clear();
        }
    }
    @Test void oldConfigurationsEnableTheRequestedMultiplayerFeaturesByDefault() {
        var settings=FlashlightSettings.load(new YamlConfiguration());
        assertTrue(settings.tracking().shared()); assertTrue(settings.tracking().localCamera());
        assertTrue(settings.shadows().enabled()); assertEquals(8,settings.shadows().maxCasters());
        assertTrue(settings.glare().enabled()); assertEquals(.65,settings.glare().strength());
    }
    private static final class Fixture implements AutoCloseable {
        final org.mockbukkit.mockbukkit.ServerMock server=org.mockbukkit.mockbukkit.MockBukkit.mock();
        final org.mockito.MockedStatic<Bukkit> bukkit=mockStatic(Bukkit.class,CALLS_REAL_METHODS);
        final Plugin plugin=mock(Plugin.class);
        final World world=mock(World.class);
        final ItemFactory factory=mock(ItemFactory.class);
        final YamlConfiguration config=new YamlConfiguration();
        final List<ItemDisplay> displays=new ArrayList<>();
        final List<ItemMeta> metas=new ArrayList<>();
        @SuppressWarnings("unchecked") Fixture() {
            when(plugin.getName()).thenReturn("FlashLight"); when(plugin.getConfig()).thenReturn(config);
            when(world.getUID()).thenReturn(UUID.randomUUID()); when(world.getEntities()).thenReturn(List.of());
            bukkit.when(Bukkit::getWorlds).thenReturn(List.of(world)); bukkit.when(Bukkit::getItemFactory).thenReturn(factory);
            when(factory.getItemMeta(any(Material.class))).thenAnswer(call->{
                ItemMeta meta=mock(ItemMeta.class); when(meta.clone()).thenReturn(meta); when(meta.getPersistentDataContainer()).thenReturn(mock(PersistentDataContainer.class)); metas.add(meta); return meta;
            });
            when(factory.isApplicable(any(ItemMeta.class),any(Material.class))).thenReturn(true);
            when(factory.asMetaFor(any(ItemMeta.class),any(Material.class))).thenAnswer(call->call.getArgument(0));
            when(world.spawn(any(Location.class),eq(ItemDisplay.class),any(Consumer.class))).thenAnswer(call->{
                ItemDisplay display=mock(ItemDisplay.class); when(display.isValid()).thenReturn(true); when(display.getWorld()).thenReturn(world);
                when(display.getPersistentDataContainer()).thenReturn(mock(PersistentDataContainer.class));
                doAnswer(color->{ when(display.getGlowColorOverride()).thenReturn(color.getArgument(0)); return null; }).when(display).setGlowColorOverride(any());
                ((Consumer<ItemDisplay>)call.getArgument(2)).accept(display); displays.add(display); return display;
            });
        }
        Player player(String name,double z) {
            Player player=mock(Player.class); when(player.getName()).thenReturn(name); when(player.getUniqueId()).thenReturn(UUID.randomUUID());
            when(player.getWorld()).thenReturn(world); when(player.isOnline()).thenReturn(true); when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
            when(player.getBoundingBox()).thenReturn(new BoundingBox(-.3,64,z-.3,.3,65.8,z+.3));
            when(player.getLocation()).thenReturn(new Location(world,0,64,z)); when(player.canSee(any(Player.class))).thenReturn(true); return player;
        }
        FlashlightSettings settings() { return FlashlightSettings.load(config); }
        @Override public void close() { bukkit.close(); org.mockbukkit.mockbukkit.MockBukkit.unmock(); }
    }
}
