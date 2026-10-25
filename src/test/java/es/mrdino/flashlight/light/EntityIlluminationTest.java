package es.mrdino.flashlight.light;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EntityIlluminationTest {
    @Test void transportsOnlyTheFlashlightParametersWithoutChangingEntityLight() {
        for(int slot=0;slot<32;slot++) for(double power:new double[]{0,.15,1}) {
            int packed=EntityIllumination.word(slot,power,20,28);
            assertEquals(slot,packed&31); assertEquals(Math.round(power*255),(packed>>>5)&255);
            assertEquals(20,4+((packed>>>13)&31)); assertEquals(28,4+((packed>>>18)&63));
            assertEquals(0,packed>>>24);
        }
    }
}
