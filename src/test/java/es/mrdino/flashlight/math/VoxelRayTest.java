package es.mrdino.flashlight.math;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class VoxelRayTest {
    @Test
    void visitsEveryVoxelAndStopsAtTheFirstBlockedCell() {
        List<Integer> cells = new ArrayList<>();
        VoxelRay.walk(new Vec3(.5, 64.5, .5), new Vec3(1, 0, 0), 20, (x, y, z, entered, leaves) -> {
            cells.add(x);
            assertEquals(64, y);
            assertEquals(0, z);
            if (x == 3) assertEquals(2.5, entered, 1e-9);
            return x < 3;
        });
        assertEquals(List.of(0, 1, 2, 3), cells);
    }

    @Test
    void handlesNegativeCoordinatesAndStartingExactlyOnABoundary() {
        List<Integer> cells = new ArrayList<>();
        VoxelRay.walk(new Vec3(-16, 5, 0), new Vec3(-1, 0, 0), 2.5, (x, y, z, entered, leaves) -> {
            cells.add(x);
            return true;
        });
        assertEquals(List.of(-16, -17, -18, -19), cells);
    }

    @Test
    void diagonalAndVerticalRaysAlwaysTerminate() {
        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
            List<String> diagonal = new ArrayList<>();
            VoxelRay.walk(new Vec3(.5, .5, .5), new Vec3(1, 1, 1), 3, (x, y, z, entered, leaves) -> {
                diagonal.add(x + ":" + y + ":" + z);
                return true;
            });
            assertEquals(List.of("0:0:0", "1:1:1", "2:2:2"), diagonal);
            List<Integer> vertical = new ArrayList<>();
            VoxelRay.walk(new Vec3(.5, .5, .5), new Vec3(0, 1, 0), 2, (x, y, z, entered, leaves) -> {
                vertical.add(y);
                return true;
            });
            assertEquals(List.of(0, 1, 2), vertical);
        });
    }

    @Test
    void aFastTurnAndVerticalLookProduceValidUnitDirections() {
        Vec3 turned = new Vec3(1, 0, 0).follow(new Vec3(-1, 0, 0), .5);
        assertEquals(1, turned.lengthSquared(), 1e-9);
        assertEquals(new Vec3(-1, 0, 0), turned);
        assertEquals(1, new Vec3(0, 1, 0).right().lengthSquared(), 1e-9);
    }
}
