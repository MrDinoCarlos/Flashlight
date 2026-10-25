package es.mrdino.flashlight.math;

/** Exact voxel traversal. The caller can stop BEFORE touching an unloaded chunk. */
public final class VoxelRay {
    private VoxelRay() { }

    @FunctionalInterface
    public interface Visitor {
        boolean visit(int x, int y, int z, double enteredAt, double leavesAt);
    }

    public static void walk(Vec3 origin, Vec3 direction, double range, Visitor visitor) {
        if (!Double.isFinite(range) || range < 0 || direction.lengthSquared() < 1.0e-12) {
            return;
        }
        Vec3 ray = direction.normalized();
        int x = (int) Math.floor(origin.x());
        int y = (int) Math.floor(origin.y());
        int z = (int) Math.floor(origin.z());
        int sx = (int) Math.signum(ray.x());
        int sy = (int) Math.signum(ray.y());
        int sz = (int) Math.signum(ray.z());
        double dx = delta(ray.x()), dy = delta(ray.y()), dz = delta(ray.z());
        double tx = boundary(origin.x(), x, ray.x());
        double ty = boundary(origin.y(), y, ray.y());
        double tz = boundary(origin.z(), z, ray.z());
        double entered = 0;
        while (entered <= range) {
            double next = Math.min(tx, Math.min(ty, tz));
            if (!visitor.visit(x, y, z, entered, Math.min(next, range)) || next > range) {
                return;
            }
            // Advance all tied axes. Rays through grid corners cannot loop forever.
            if (tx <= next + 1.0e-10) { x += sx; tx += dx; }
            if (ty <= next + 1.0e-10) { y += sy; ty += dy; }
            if (tz <= next + 1.0e-10) { z += sz; tz += dz; }
            entered = next;
        }
    }

    private static double delta(double axis) {
        return axis == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / axis);
    }

    private static double boundary(double position, int cell, double axis) {
        if (axis > 0) return (cell + 1 - position) / axis;
        if (axis < 0) return (position - cell) / -axis;
        return Double.POSITIVE_INFINITY;
    }
}
