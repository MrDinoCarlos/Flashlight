package es.mrdino.flashlight;

import java.util.random.RandomGenerator;

/** Tick-based: lag never drains more battery than the light actually runs. */
public final class FlashlightState {
    private final RandomGenerator random;
    private long nextFlicker = Long.MAX_VALUE;
    private long flickerUntil;
    private long nextPulse;
    private long lastPulseTick = Long.MIN_VALUE;
    private double flickerStrength = 1;
    private int pulseTicks;
    private boolean dimPulse;
    private double intensity = 1;
    private double battery;

    public FlashlightState(double battery, RandomGenerator random) {
        this.battery = Math.clamp(battery, 0, 1);
        this.random = random;
    }

    public void start(long tick, FlashlightSettings settings) {
        intensity = 1;
        flickerUntil = 0;
        schedule(tick, settings);
    }

    public void forceFlicker(long tick, FlashlightSettings settings) {
        forceFlicker(tick, random.nextInt(settings.flickerMinDuration(), settings.flickerMaxDuration() + 1),
                settings.flickerStrength(), 0);
    }

    public void forceFlicker(long tick, int duration, double strength, int pulseTicks) {
        if (duration < 1 || !Double.isFinite(strength) || strength < 0 || strength > 1 || pulseTicks < 0) {
            throw new IllegalArgumentException("Invalid flicker duration, strength or pulse interval.");
        }
        flickerUntil = tick + duration;
        flickerStrength = strength;
        this.pulseTicks = pulseTicks;
        dimPulse = true;
        intensity = 1 - flickerStrength;
        nextPulse = tick + (pulseTicks == 0 ? random.nextInt(3, 6) : pulseTicks);
        lastPulseTick = tick;
    }

    public boolean tick(long tick, FlashlightSettings settings) {
        if (settings.batteryEnabled()) {
            battery = Math.max(0, battery - 1.0 / settings.batteryDurationTicks());
        }
        if (battery <= 1.0e-9 && settings.batteryEnabled()) {
            battery = 0;
            intensity = 0;
            return false;
        }
        if (settings.flickerEnabled() && tick >= nextFlicker && tick >= flickerUntil) {
            forceFlicker(tick, settings);
        }
        if (tick < flickerUntil) {
            if (tick >= nextPulse) {
                dimPulse = !dimPulse;
                intensity = dimPulse ? 1 - flickerStrength : 1;
                nextPulse = tick + (pulseTicks == 0 ? random.nextInt(3, 6) : pulseTicks);
                lastPulseTick = tick;
            }
        } else {
            if (intensity != 1) {
                intensity = 1;
                schedule(tick, settings);
            }
            // A final bright pulse may already be 1. Always schedule after a completed fault.
            if (flickerUntil != 0) {
                flickerUntil = 0;
                schedule(tick, settings);
            }
        }
        return true;
    }

    private void schedule(long tick, FlashlightSettings settings) {
        double multiplier = settings.batteryEnabled() && battery <= settings.lowBatteryThreshold() ? settings.lowBatteryMultiplier() : 1;
        int interval = random.nextInt(settings.flickerMinInterval(), settings.flickerMaxInterval() + 1);
        nextFlicker = tick + Math.max(20, (long) (interval / multiplier));
    }

    public void recharge() { battery = 1; }
    public double battery() { return battery; }
    public double intensity() { return intensity; }
    public boolean flickering(long tick) { return tick < flickerUntil; }
    public boolean pulsedAt(long tick) { return lastPulseTick == tick; }
}
