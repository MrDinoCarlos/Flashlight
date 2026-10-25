package es.mrdino.flashlight.light;

import es.mrdino.flashlight.FlashlightSettings;
import es.mrdino.flashlight.math.Vec3;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.World;
import org.bukkit.util.BoundingBox;
import static es.mrdino.flashlight.light.SurfacePatch.dot;

/** Samples the cone, then merges visible collision faces instead of spawning an entity per block. */
final class SurfaceSampler {
    record Result(List<SurfacePatch> patches,double visibleRange) { }
    private record Plane(Vec3 normal,long coordinate) { }
    private static final class Group {
        final SurfacePatch plane;
        final ArrayDeque<BeamSampler.CellKey> queue=new ArrayDeque<>();
        final HashSet<BeamSampler.CellKey> visited=new HashSet<>();
        Group(Vec3 n,double k) { plane=new SurfacePatch(n,k,0,0,1,1); }
    }

    Result sample(World world,Vec3 origin,Vec3 direction,double lensRadius,FlashlightSettings settings) {
        return sample(new BeamSampler.Context(world,settings.waterproof()),origin,direction,lensRadius,settings);
    }

    Result sample(BeamSampler.Context context,Vec3 origin,Vec3 direction,double lensRadius,FlashlightSettings settings) {
        return sample(context,origin,direction,lensRadius,settings,List.of());
    }

    Result sample(BeamSampler.Context context,Vec3 origin,Vec3 direction,double lensRadius,FlashlightSettings settings,List<SurfacePatch> previous) {
        return sample(context,origin,direction,lensRadius,settings,previous,true);
    }
    Result sample(BeamSampler.Context context,Vec3 origin,Vec3 direction,double lensRadius,FlashlightSettings settings,List<SurfacePatch> previous,boolean receivingFaces) {
        // Recheck chunk availability on every scan, even when reusing collision shapes.
        context.chunks.clear();
        Vec3 right=direction.right(),up=right.cross(direction).normalized();
        double spread=Math.tan(Math.toRadians(settings.coneAngle()*.5));
        Map<Plane,Group> groups=new LinkedHashMap<>();
        double visibleRange=0;
        // Centre + two rings find foreground faces and the wall around their silhouette.
        for(int ray=0;ray<13;ray++) {
            double radius=ray==0 ? 0 : ray<=8 ? .94 : .45;
            double angle=(ray<=8 ? ray-1 : ray-9)*Math.PI/(ray<=8 ? 4 : 2);
            Vec3 aim=direction.add(right.scale(Math.cos(angle)*spread*radius))
                    .add(up.scale(Math.sin(angle)*spread*radius)).normalized();
            var hit=context.trace(origin,aim,settings.range());
            visibleRange=Math.max(visibleRange,hit.beam().distance());
            if(!receivingFaces || !hit.beam().hitSurface()) continue;
            Vec3 normal=hit.beam().normal();
            double plane=dot(normal,hit.beam().end());
            Plane key=new Plane(normal,Math.round(plane*4096));
            if(!groups.containsKey(key) && groups.size()>=6) continue;
            Group group=groups.computeIfAbsent(key,ignored->new Group(normal,plane));
            group.queue.add(hit.cell());
        }
        if(!receivingFaces) return new Result(List.of(),Math.min(settings.range(),visibleRange));
        // A grazing face must not disappear simply because it falls between
        // the thirteen discovery rays on this scan. Revalidate its solid seed.
        for(SurfacePatch face:previous) {
            if(!inCone(face,origin,direction,lensRadius,spread,settings.range())
                    || dot(origin,face.normal())<=face.plane()) continue;
            Plane key=new Plane(face.normal(),Math.round(face.plane()*4096));
            if(!groups.containsKey(key) && groups.size()>=6) continue;
            Group group=groups.computeIfAbsent(key,ignored->new Group(face.normal(),face.plane()));
            Vec3 seed=face.center().add(face.normal().scale(-.001));
            group.queue.add(new BeamSampler.CellKey((int)Math.floor(seed.x()),(int)Math.floor(seed.y()),(int)Math.floor(seed.z())));
        }
        List<SurfacePatch> faces=new ArrayList<>();
        int budget=settings.surfaceCellBudget();
        // Round robin gives a corner/foreground face its share of the bounded scan.
        boolean pending=true;
        while(budget>0 && pending) {
            pending=false;
            for(Group group:groups.values()) {
                if(group.queue.isEmpty()) continue;
                pending=true;
                var key=group.queue.remove();
                if(!group.visited.add(key)) continue;
                if(--budget<0) break;
                var cell=context.cell(key);
                if(cell==null || cell.empty) continue;
                boolean extend=false;
                for(BoundingBox box:cell.boxes(key)) {
                    SurfacePatch face=face(box,group.plane);
                    if(face==null || !inCone(face,origin,direction,lensRadius,spread,settings.range())) continue;
                    extend=true;
                    Vec3 target=face.center().add(face.normal().scale(.003));
                    if(context.occupied(target)) continue;
                    Vec3 offset=target.add(origin.scale(-1));
                    double distance=Math.sqrt(offset.lengthSquared());
                    var occlusion=context.trace(origin,offset.scale(1/Math.max(.001,distance)),Math.max(.001,distance-.005));
                    if(occlusion.beam().distance()<distance-.015) continue;
                    if(!faces.contains(face)) faces.add(face);
                }
                if(extend) for(Vec3 axis:List.of(group.plane.u(),group.plane.v())) {
                    group.queue.add(key.step(axis,1)); group.queue.add(key.step(axis,-1));
                }
            }
        }
        merge(faces);
        faces.sort(Comparator.comparingDouble(face->face.center().add(origin.scale(-1)).lengthSquared()));
        if(faces.size()>settings.maxSurfacePatches()) faces=new ArrayList<>(faces.subList(0,settings.maxSurfacePatches()));
        return new Result(List.copyOf(faces),Math.min(settings.range(),visibleRange));
    }

    private static SurfacePatch face(BoundingBox box,SurfacePatch plane) {
        Vec3 min=new Vec3(box.getMinX(),box.getMinY(),box.getMinZ());
        Vec3 max=new Vec3(box.getMaxX(),box.getMaxY(),box.getMaxZ());
        double k=Math.max(dot(min,plane.normal()),dot(max,plane.normal()));
        if(Math.abs(k-plane.plane())>1e-5) return null;
        double a=dot(min,plane.u()),b=dot(max,plane.u()),c=dot(min,plane.v()),d=dot(max,plane.v());
        return new SurfacePatch(plane.normal(),plane.plane(),Math.min(a,b),Math.min(c,d),Math.max(a,b),Math.max(c,d));
    }
    private static boolean inCone(SurfacePatch face,Vec3 origin,Vec3 direction,double lens,double spread,double range) {
        Vec3 offset=face.center().add(origin.scale(-1));
        double z=dot(offset,direction),radius=Math.hypot(face.width(),face.height())*.5;
        if(z+radius<=0 || z-radius>=range) return false;
        // Keep a small coverage margin outside the optical edge. The shader
        // still clips the light, so cell-frontier changes happen off the spot.
        double radial=Math.max(0,offset.lengthSquared()-z*z),limit=lens+spread*Math.max(0,z)+radius+.35;
        return radial<=limit*limit;
    }
    static void merge(List<SurfacePatch> faces) {
        faces.sort(Comparator.comparingDouble((SurfacePatch f)->f.normal().x())
                .thenComparingDouble(f->f.normal().y()).thenComparingDouble(f->f.normal().z())
                .thenComparingDouble(SurfacePatch::plane).thenComparingDouble(SurfacePatch::minV)
                .thenComparingDouble(SurfacePatch::minU).thenComparingDouble(SurfacePatch::maxV).thenComparingDouble(SurfacePatch::maxU));
        boolean changed=true;
        while(changed) {
            changed=false;
            search: for(int a=0;a<faces.size();a++) for(int b=a+1;b<faces.size();b++) {
                SurfacePatch merged=faces.get(a).merge(faces.get(b));
                if(merged!=null) { faces.set(a,merged); faces.remove(b); changed=true; break search; }
            }
        }
    }
}
