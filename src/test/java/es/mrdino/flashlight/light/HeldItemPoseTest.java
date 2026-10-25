package es.mrdino.flashlight.light;

import es.mrdino.flashlight.math.Vec3;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HeldItemPoseTest {
    @ParameterizedTest
    @ValueSource(strings={"flashlight_on","flashlight_off","flashlight_low",
            "flashlight_plain_on","flashlight_plain_off","flashlight_plain_low"})
    void thirdPersonLensPointsForwardWithSwitchAboveTheGripInBothVanillaHands(String name) throws Exception {
        try(var source=getClass().getResourceAsStream("/pack/FlashLight-ResourcePack.zip"); var zip=new java.util.zip.ZipInputStream(source)) {
            java.util.zip.ZipEntry entry;
            while((entry=zip.getNextEntry())!=null) {
                if(!entry.getName().equals("assets/flashlight/models/item/"+name+".json")) continue;
                var model=com.google.gson.JsonParser.parseString(new String(zip.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
                var switchElement=java.util.stream.StreamSupport.stream(model.getAsJsonArray("elements").spliterator(),false)
                        .map(com.google.gson.JsonElement::getAsJsonObject)
                        .filter(element->element.get("name").getAsString().equals("user_model_26")).findFirst().orElseThrow();
                for(boolean left:new boolean[]{false,true}) {
                    var display=model.getAsJsonObject("display").getAsJsonObject("thirdperson_"+(left ? "left" : "right")+"hand");
                    var rotation=display.getAsJsonArray("rotation"); var translation=display.getAsJsonArray("translation"); var scale=display.getAsJsonArray("scale");
                    // Native ItemInHandLayer and ItemTransform: model Y is down,
                    // forward is -Z; left-handed rendering mirrors Y/Z rotations.
                    var transform=new org.joml.Matrix4f().rotateX((float)-Math.PI/2).rotateY((float)Math.PI)
                            .translate(left ? -1f/16 : 1f/16,2f/16,-10f/16)
                            .translate((left ? -1 : 1)*translation.get(0).getAsFloat()/16,translation.get(1).getAsFloat()/16,translation.get(2).getAsFloat()/16)
                            .rotateXYZ((float)Math.toRadians(rotation.get(0).getAsDouble()),
                                    (left ? -1 : 1)*(float)Math.toRadians(rotation.get(1).getAsDouble()),
                                    (left ? -1 : 1)*(float)Math.toRadians(rotation.get(2).getAsDouble()))
                            .scale(scale.get(0).getAsFloat(),scale.get(1).getAsFloat(),scale.get(2).getAsFloat());
                    var grip=transform.transformPosition(new org.joml.Vector3f(8.0361f/16-.5f,7.9028f/16-.5f,13.5f/16-.5f));
                    // Native arm box: Y=-2..10, not 0..12. Its shoulder
                    // translation applies to the arm and item equally.
                    assertTrue(grip.y>7f/16 && grip.y<10f/16,"The grip must sit inside the actual vanilla fist (model Y points down)");
                    assertTrue(Math.abs(grip.z)<.125,"The grip must lie inside the fist's depth, rather than float ahead of it");
                    assertTrue(Math.abs(grip.x)<.125,"Both hands must hold the body across its width");
                    var lens=transform.transformPosition(new org.joml.Vector3f(8.0361f/16-.5f,7.9028f/16-.5f,-5.675975f/16-.5f));
                    assertTrue(lens.z<grip.z-.3f,"The lens must face forward (-Z), not shine back toward the player's cape");
                    assertEquals(grip.y,lens.y,.02,"The lens should point level relative to the hand");
                    var start=switchElement.getAsJsonArray("from"); var end=switchElement.getAsJsonArray("to");
                    var button=transform.transformPosition(new org.joml.Vector3f(
                            (start.get(0).getAsFloat()+end.get(0).getAsFloat())/32-.5f,
                            (start.get(1).getAsFloat()+end.get(1).getAsFloat())/32-.5f,
                            (start.get(2).getAsFloat()+end.get(2).getAsFloat())/32-.5f));
                    assertTrue(button.y<grip.y-.025f,"The switch belongs above the body, not underneath the hand");
                }
                return;
            }
            fail("Missing third-person model");
        }
    }
    private HeldItemPose pose() {
        Plugin plugin=mock(Plugin.class);
        when(plugin.getResource("pack/FlashLight-ResourcePack.zip"))
                .thenAnswer(invocation -> getClass().getResourceAsStream("/pack/FlashLight-ResourcePack.zip"));
        return HeldItemPose.load(plugin);
    }

    @Test void emitsFromTheActualLensRatherThanBesideTheCamera() {
        HeldItemPose pose=pose();
        var right=pose.world(new Vec3(0,65,0),new Vec3(0,0,1),0,false);
        assertEquals(-.37343,right.origin().x(),.001);
        assertEquals(64.59184,right.origin().y(),.001);
        assertEquals(1.33413,right.origin().z(),.001);
        // Its centre line reaches the crosshair at approximately twelve blocks.
        double distance=(12-right.origin().z())/right.direction().z();
        Vec3 focus=right.origin().add(right.direction().scale(distance));
        assertEquals(0,focus.x(),.025); assertEquals(65,focus.y(),.025);
        var left=pose.world(new Vec3(0,65,0),new Vec3(0,0,1),0,true);
        assertTrue(left.origin().x()>0);
        assertEquals(right.origin().z(),left.origin().z(),.001);
    }

    @Test void followsPitchYawAndRemainsFiniteWhenLookingStraightUp() {
        var east=pose().world(new Vec3(0,65,0),new Vec3(1,0,0),-90,false);
        assertEquals(1.33413,east.origin().x(),.001);
        assertTrue(east.direction().x()>.99);
        var up=pose().world(new Vec3(0,65,0),new Vec3(0,1,0),90,false);
        assertTrue(Double.isFinite(up.origin().x()+up.origin().y()+up.origin().z()));
        assertTrue(up.direction().y()>.99);
    }

    @Test void intensityFallsWithDistanceAndReachesZeroSmoothlyAtTheConfiguredRange() {
        double previous=1;
        for(double distance=0;distance<=20;distance+=.1) {
            double intensity=BeamOptics.attenuation(distance,20);
            assertTrue(intensity>=0 && intensity<=previous+1e-10);
            previous=intensity;
        }
        assertEquals(.5,BeamOptics.attenuation(6,20),1e-9);
        assertTrue(BeamOptics.attenuation(19.9,20)<.0001);
        assertEquals(0,BeamOptics.attenuation(20,20));
        assertEquals(0,BeamOptics.attenuation(30,20));
    }
}
