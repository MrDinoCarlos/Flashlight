package es.mrdino.flashlight.light;

import org.bukkit.Color;
import org.bukkit.entity.Display;
import org.joml.Vector3f;

/** 1.21.4 tint + brightness transport, decoded inside the normal resource pack. */
public record BeamMetadata(Color color, Display.Brightness brightness) {
    public static int ratioCode(double length, double diameter) {
        double ratio = Math.clamp(length / Math.max(.000001, diameter), .0625, 16384);
        return (int) Math.round(Math.log(ratio / .0625) / Math.log(262144) * 4095);
    }

    public static double ratio(int code) { return .0625 * Math.pow(262144, Math.clamp(code, 0, 4095) / 4095.0); }

    public static double carrierDiameter(double length, double diameter) {
        return length / ratio(ratioCode(length, diameter));
    }

    public static BeamMetadata encode(double length, double diameter, double intensity, Vector3f localPlaneNormal) {
        Vector3f n = new Vector3f(localPlaneNormal).normalize();
        n.div(Math.abs(n.x) + Math.abs(n.y) + Math.abs(n.z));
        float x = n.x, y = n.y;
        if (n.z < 0) {
            x = (1 - Math.abs(n.y)) * (n.x >= 0 ? 1 : -1);
            y = (1 - Math.abs(n.x)) * (n.y >= 0 ? 1 : -1);
        }
        long octX = Math.round(Math.clamp(x * .5 + .5, 0, 1) * 63);
        long octY = Math.round(Math.clamp(y * .5 + .5, 0, 1) * 63);
        long word = ratioCode(length, diameter) | (Math.round(Math.clamp(intensity, 0, 1) * 255) << 12)
                | (octX << 20) | (octY << 26);
        return new BeamMetadata(Color.fromRGB((int) (word & 255), (int) (word >>> 8 & 255), (int) (word >>> 16 & 255)),
                new Display.Brightness((int) (word >>> 24 & 15), (int) (word >>> 28 & 15)));
    }
}
