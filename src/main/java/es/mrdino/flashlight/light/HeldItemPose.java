package es.mrdino.flashlight.light;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import es.mrdino.flashlight.math.Vec3;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipInputStream;
import org.bukkit.plugin.Plugin;

/** The lens transform generated from the same model shipped to the player. */
public record HeldItemPose(Ray right, Ray left, double lensRadius) {
    public record Ray(Vec3 origin, Vec3 direction) { }

    public static HeldItemPose load(Plugin plugin) {
        try (var source = plugin.getResource("pack/FlashLight-ResourcePack.zip");
             var zip = new ZipInputStream(java.util.Objects.requireNonNull(source))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!entry.getName().equals("assets/flashlight/flashlight_pose.json")) continue;
                var root = JsonParser.parseReader(new InputStreamReader(zip, StandardCharsets.UTF_8)).getAsJsonObject();
                var hands = root.getAsJsonObject("hands");
                return new HeldItemPose(read(hands.getAsJsonObject("right")), read(hands.getAsJsonObject("left")), root.get("lens_radius").getAsDouble());
            }
            throw new IllegalStateException("The resource pack has no flashlight lens transform.");
        } catch (java.io.IOException exception) { throw new java.io.UncheckedIOException(exception); }
    }

    private static Ray read(JsonObject side) { return new Ray(vector(side, "origin"), vector(side, "direction").normalized()); }
    private static Vec3 vector(JsonObject side, String key) {
        var values = side.getAsJsonArray(key);
        return new Vec3(values.get(0).getAsDouble(), values.get(1).getAsDouble(), values.get(2).getAsDouble());
    }

    public Ray world(Vec3 eye, Vec3 forward, double yawDegrees, boolean leftHand) {
        Vec3 rightAxis = forward.cross(new Vec3(0, 1, 0));
        if (rightAxis.lengthSquared() < 1e-8) {
            double yaw = Math.toRadians(yawDegrees);
            rightAxis = new Vec3(-Math.cos(yaw), 0, -Math.sin(yaw));
        }
        rightAxis = rightAxis.normalized();
        Vec3 up = rightAxis.cross(forward).normalized();
        Ray pose = leftHand ? left : right;
        return new Ray(eye.add(transform(pose.origin, rightAxis, up, forward)),
                transform(pose.direction, rightAxis, up, forward).normalized());
    }

    private static Vec3 transform(Vec3 value, Vec3 right, Vec3 up, Vec3 forward) {
        return right.scale(value.x()).add(up.scale(value.y())).add(forward.scale(-value.z()));
    }
}
