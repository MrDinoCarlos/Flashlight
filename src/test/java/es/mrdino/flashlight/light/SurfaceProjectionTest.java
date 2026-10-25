package es.mrdino.flashlight.light;

import es.mrdino.flashlight.math.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SurfaceProjectionTest {
    private static int light(SurfaceProjection projection) {
        return projection.brightness().getBlockLight()+16*projection.brightness().getSkyLight();
    }

    @Test void splittingTheSameWallDoesNotChangeItsBrightness() {
        Vec3 normal=new Vec3(0,0,1),source=new Vec3(.37,-.4,-1.33),direction=new Vec3(-.035,.038,-.99866).normalized();
        var full=new SurfacePatch(normal,-8,-4,-4,4,4);
        var left=new SurfacePatch(normal,-8,-4,-4,0,4);
        var right=new SurfacePatch(normal,-8,0,-4,4,4);
        int expected=light(SurfaceProjection.of(full,source,direction,.087,28,20,1));
        assertEquals(expected,light(SurfaceProjection.of(left,source,direction,.087,28,20,1)),
                "Repartitioning a wall while turning must not modulate its light");
        assertEquals(expected,light(SurfaceProjection.of(right,source,direction,.087,28,20,1)));
    }

    private Vec3 aim(double degrees) {
        double azimuth=Math.toRadians(degrees),inclination=Math.toRadians(30);
        return new Vec3(Math.cos(azimuth)*Math.sin(inclination),Math.sin(azimuth)*Math.sin(inclination),-Math.cos(inclination));
    }

    @Test void grazingFacesDoNotCreateASecondFullBrightnessHotSpot() {
        Vec3 source=new Vec3(0,0,0),direction=new Vec3(0,0,-1);
        var wall=new SurfacePatch(new Vec3(0,0,1),-6,-4,-4,4,4);
        var side=new SurfacePatch(new Vec3(1,0,0),-1,-8,-2,-4,2);
        var headOn=SurfaceProjection.of(wall,source,direction,.087,28,20,1);
        var grazing=SurfaceProjection.of(side,source,direction,.087,28,20,1);
        assertNotNull(grazing); assertTrue(light(grazing)<light(headOn)*.65);
        assertTrue(light(grazing)>0,"Grazing surfaces remain lit, with lower irradiance");
    }

    @Test void tinyMotionAcrossAnEncodingBoundaryDoesNotMakeTheProjectionOscillate() {
        var face=new SurfacePatch(new Vec3(0,0,1),-8,-8,-8,8,8);
        Vec3 source=new Vec3(0,0,0); var state=new SurfaceProjection.State();
        var first=SurfaceProjection.of(face,source,aim(2.7),.087,28,20,1,state);
        assertNotEquals(first.model(),SurfaceProjection.of(face,source,aim(2.9),.087,28,20,1).model(),"This movement straddles a real marker boundary");
        for(int tick=0;tick<20;tick++) assertEquals(first.model(),
                SurfaceProjection.of(face,source,aim(tick%2==0 ? 2.9 : 2.7),.087,28,20,1,state).model());
        assertNotEquals(first.model(),SurfaceProjection.of(face,source,aim(8),.087,28,20,1,state).model(),"Deliberate aiming is not delayed");
    }

    @Test void smoothingGeometricFadeDoesNotDelayCommandedFlickerAndResetsAfterAnAbsence() {
        var face=new SurfacePatch(new Vec3(0,0,1),-8,-4,-4,4,4);
        Vec3 source=new Vec3(0,0,0),direction=new Vec3(0,0,-1); var state=new SurfaceProjection.State();
        state.prepareFade(1,100); state.prepareFade(.4,101);
        int full=light(SurfaceProjection.of(face,source,direction,.087,28,20,1,state));
        assertEquals(179,full,1,"One tick smooths geometric attenuation");
        assertEquals(Math.round(full*.15),light(SurfaceProjection.of(face,source,direction,.087,28,20,.15,state)),1);
        assertEquals(0,light(SurfaceProjection.of(face,source,direction,.087,28,20,0,state)));
        state.prepareFade(.2,120);
        assertEquals(51,light(SurfaceProjection.of(face,source,direction,.087,28,20,1,state)));
    }
}
