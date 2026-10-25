package es.mrdino.flashlight;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CommandDurationTest {
    @Test void secondsTicksAndFractionsAreBoundedAndRoundedUp() {
        assertEquals(60, CommandDuration.ticks("3s"));
        assertEquals(10, CommandDuration.ticks("0.5"));
        assertEquals(40, CommandDuration.ticks("40t"));
        assertEquals(2, CommandDuration.ticks("0.051S"));
        assertEquals(72000, CommandDuration.ticks("3600s"));
        for (String value : new String[]{"-1s", "0", "0.01s", "NaN", "Infinity", "1e10", "1m", "3601s", "999999999999999999t"}) {
            assertThrows(IllegalArgumentException.class, () -> CommandDuration.ticks(value), value);
        }
    }
}
