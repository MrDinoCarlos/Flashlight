package es.mrdino.flashlight.light;

import es.mrdino.flashlight.math.Vec3;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SurfaceAssignmentsTest {
    private SurfacePatch face(double plane,double left,double right) {
        return new SurfacePatch(new Vec3(0,0,1),plane,left,0,right,1);
    }

    @Test void reorderedFacesKeepTheirEntities() {
        var wall=face(-8,0,2); var cube=face(-4,0,1);
        assertArrayEquals(new int[]{1,0},SurfaceAssignments.match(List.of(wall,cube),List.of(cube,wall)));
    }

    @Test void exactMatchesAreReservedBeforeChangedFacesReuseOverlappingSlots() {
        var left=face(-8,0,1); var right=face(-8,1,2);
        assertArrayEquals(new int[]{1,0},SurfaceAssignments.match(List.of(left,right),List.of(face(-8,0,2),left)));
    }

    @Test void expandingSplittingAndSwitchingPlanesReuseTheBoundedPool() {
        var old=Arrays.asList(face(-8,0,2),null,face(-4,0,1));
        var split=List.of(face(-8,0,1),face(-8,1,2),face(-6,0,1));
        int[] slots=SurfaceAssignments.match(old,split);
        assertEquals(0,slots[0]);
        assertEquals(3,Arrays.stream(slots).distinct().count());
        assertTrue(Arrays.stream(slots).allMatch(slot->slot>=0 && slot<3));
        assertArrayEquals(new int[]{0,1,2},SurfaceAssignments.match(List.of(),split));
    }
}
