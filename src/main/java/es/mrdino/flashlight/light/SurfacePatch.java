package es.mrdino.flashlight.light;

import es.mrdino.flashlight.math.Vec3;

/** An actual, bounded solid face. Adjacent faces may merge without spanning holes. */
public record SurfacePatch(Vec3 normal, double plane, double minU, double minV, double maxU, double maxV) {
    public record Plane(Vec3 normal,long coordinate) { }
    public Plane planeKey() { return new Plane(normal,Math.round(plane*4096)); }
    boolean sameFootprint(SurfacePatch other) {
        return other!=null && planeKey().equals(other.planeKey()) && same(minU,other.minU) && same(minV,other.minV)
                && same(maxU,other.maxU) && same(maxV,other.maxV);
    }
    double overlap(SurfacePatch other) {
        if(other==null || !planeKey().equals(other.planeKey())) return 0;
        return Math.max(0,Math.min(maxU,other.maxU)-Math.max(minU,other.minU))
                *Math.max(0,Math.min(maxV,other.maxV)-Math.max(minV,other.minV));
    }
    public Vec3 u() {
        if (Math.abs(normal.x()) > .5) return new Vec3(0, 0, -normal.x());
        if (Math.abs(normal.y()) > .5) return new Vec3(1, 0, 0);
        return new Vec3(normal.z(), 0, 0);
    }
    public Vec3 v() { return normal.cross(u()); }
    public Vec3 point(double u, double v) { return normal.scale(plane).add(u().scale(u)).add(v().scale(v)); }
    public Vec3 center() { return point((minU+maxU)*.5, (minV+maxV)*.5); }
    public double width() { return maxU-minU; }
    public double height() { return maxV-minV; }
    static double dot(Vec3 a, Vec3 b) { return a.x()*b.x()+a.y()*b.y()+a.z()*b.z(); }
    public SurfacePatch merge(SurfacePatch b) {
        if (!normal.equals(b.normal) || Math.abs(plane-b.plane)>1e-6) return null;
        if (same(minV,b.minV) && same(maxV,b.maxV) && (same(maxU,b.minU) || same(minU,b.maxU)))
            return new SurfacePatch(normal,plane,Math.min(minU,b.minU),minV,Math.max(maxU,b.maxU),maxV);
        if (same(minU,b.minU) && same(maxU,b.maxU) && (same(maxV,b.minV) || same(minV,b.maxV)))
            return new SurfacePatch(normal,plane,minU,Math.min(minV,b.minV),maxU,Math.max(maxV,b.maxV));
        return null;
    }
    private static boolean same(double a,double b) { return Math.abs(a-b)<1e-6; }
}
