package es.mrdino.flashlight.math;

import org.bukkit.util.Vector;

public record Vec3(double x, double y, double z) {
    public static Vec3 of(Vector vector) {
        return new Vec3(vector.getX(), vector.getY(), vector.getZ());
    }

    public Vec3 add(Vec3 other) {
        return new Vec3(x + other.x, y + other.y, z + other.z);
    }

    public Vec3 scale(double amount) {
        return new Vec3(x * amount, y * amount, z * amount);
    }

    public Vec3 cross(Vec3 other) {
        return new Vec3(y * other.z - z * other.y, z * other.x - x * other.z, x * other.y - y * other.x);
    }

    public double lengthSquared() {
        return x * x + y * y + z * z;
    }

    public Vec3 normalized() {
        double length = Math.sqrt(lengthSquared());
        return length < 1.0e-9 ? new Vec3(0, 0, 1) : scale(1.0 / length);
    }

    public Vec3 follow(Vec3 target, double response) {
        Vec3 blended = scale(1 - response).add(target.scale(response));
        // A 180-degree turn must never produce a zero/NaN direction.
        return blended.lengthSquared() < 1.0e-8 ? target.normalized() : blended.normalized();
    }

    public Vec3 right() {
        Vec3 right = cross(new Vec3(0, 1, 0));
        return right.lengthSquared() < 1.0e-8 ? new Vec3(1, 0, 0) : right.normalized();
    }

    public Vector toBukkit() {
        return new Vector(x, y, z);
    }
}
