package es.mrdino.flashlight.light;

/** Mirrored in the resource-pack volume shader, in physical blocks/metres. */
public final class BeamOptics {
    private BeamOptics() { }
    public static double attenuation(double distance, double range) {
        double d = Math.max(0, distance);
        double t = Math.clamp((d - range * .65) / (range * .35), 0, 1);
        double fade = 1 - t * t * (3 - 2 * t);
        return fade / (1 + d * d / 36);
    }
}
