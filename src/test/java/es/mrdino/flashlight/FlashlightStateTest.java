package es.mrdino.flashlight;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class FlashlightStateTest {
    @Test
    void explicitFaultUsesExactDepthLengthAndPulseEvenWithRandomFaultsDisabled() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("flicker.enabled", false);
        FlashlightSettings settings = FlashlightSettings.load(config);
        FlashlightState state = new FlashlightState(1, new Random(1));
        state.start(0, settings);
        state.forceFlicker(1, 20, .4, 4);
        for (int tick = 1; tick <= 20; tick++) {
            assertTrue(state.tick(tick, settings));
            assertEquals(((tick - 1) / 4) % 2 == 0 ? .6 : 1, state.intensity(), 1e-9);
        }
        state.tick(21, settings);
        assertEquals(1, state.intensity());
        assertFalse(state.flickering(21));
    }

    @Test
    void aZeroStrengthFaultDoesNotDimAndAReplacementFaultUsesTheNewSettings() {
        FlashlightSettings settings = FlashlightSettings.load(new YamlConfiguration());
        FlashlightState state = new FlashlightState(1, new Random(1));
        state.start(0, settings);
        state.forceFlicker(1, 20, 0, 3);
        state.tick(1, settings);
        assertEquals(1, state.intensity());
        state.forceFlicker(2, 5, 1, 2);
        state.tick(2, settings);
        assertEquals(0, state.intensity());
        state.tick(7, settings);
        assertEquals(1, state.intensity());
    }
    @Test
    void batteryDrainsOnlyForTicksTheFlashlightIsRunningAndThenStops() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("battery.duration-seconds", 10);
        config.set("flicker.enabled", false);
        FlashlightSettings settings = FlashlightSettings.load(config);
        FlashlightState state = new FlashlightState(1, new Random(4));
        state.start(5000, settings);
        for (int tick = 1; tick <= 100; tick++) assertTrue(state.tick(5000 + tick, settings));
        assertEquals(.5, state.battery(), 1e-8);
        for (int tick = 101; tick < 200; tick++) assertTrue(state.tick(5000 + tick, settings));
        assertFalse(state.tick(5200, settings));
        assertEquals(0, state.battery());
    }

    @Test
    void aForcedFaultHasVisibleDarkPulsesAndAlwaysRecovers() {
        FlashlightSettings settings = FlashlightSettings.load(new YamlConfiguration());
        FlashlightState state = new FlashlightState(1, new Random(7));
        state.start(0, settings);
        state.forceFlicker(1, settings);
        boolean dark = false, bright = false;
        int minimumDarkRun = Integer.MAX_VALUE, run = 0;
        for (int tick = 1; tick <= 50; tick++) {
            assertTrue(state.tick(tick, settings));
            if (state.intensity() < .3) { dark = true; run++; }
            else {
                bright = true;
                if (run > 0) minimumDarkRun = Math.min(minimumDarkRun, run);
                run = 0;
            }
        }
        assertTrue(dark);
        assertTrue(bright);
        assertTrue(minimumDarkRun >= 3, "A pulse must survive a two-tick lighting update.");
        assertFalse(state.flickering(50));
        assertEquals(1, state.intensity());
    }

    @Test
    void faultsHappenAutomaticallyAndDoNotBecomePermanent() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("flicker.min-interval-seconds", 2);
        config.set("flicker.max-interval-seconds", 2);
        config.set("battery.enabled", false);
        FlashlightSettings settings = FlashlightSettings.load(config);
        FlashlightState state = new FlashlightState(1, new Random(3));
        state.start(0, settings);
        int darkTicks = 0;
        for (int tick = 1; tick < 500; tick++) {
            assertTrue(state.tick(tick, settings));
            if (state.intensity() < .3) darkTicks++;
        }
        assertTrue(darkTicks > 10 && darkTicks < 300);
        assertEquals(1, state.battery());
    }

    @Test
    void disabledBatteryWorksEvenWhenStoredChargeIsEmpty() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("battery.enabled", false);
        FlashlightSettings settings = FlashlightSettings.load(config);
        FlashlightState state = new FlashlightState(0, new Random(7));
        state.start(0, settings);
        assertTrue(state.tick(1, settings));
        state.recharge();
        assertEquals(1, state.battery());
    }

    @Test
    void badConfigurationIsBoundedAndInvalidPackHashesAreRejected() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("light.range", Double.NaN);
        config.set("light.update-interval-ticks", 0);
        config.set("flicker.max-interval-seconds", -8);
        FlashlightSettings settings = FlashlightSettings.load(config);
        assertEquals(20, settings.range());
        assertEquals(1, settings.updateInterval());
        assertTrue(settings.flickerMaxInterval() >= settings.flickerMinInterval());
        config.set("resource-pack.sha1", "wrong");
        assertThrows(IllegalArgumentException.class, () -> FlashlightSettings.load(config));
    }
}
