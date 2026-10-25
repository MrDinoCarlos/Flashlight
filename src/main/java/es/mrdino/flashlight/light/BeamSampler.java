package es.mrdino.flashlight.light;

import es.mrdino.flashlight.FlashlightSettings;
import es.mrdino.flashlight.math.Vec3;
import es.mrdino.flashlight.math.VoxelRay;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.BoundingBox;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Collision only. The resource-pack shader renders the light; no LIGHT blocks. */
public final class BeamSampler {
    public record Beam(Vec3 end, Vec3 normal, double distance, boolean hitSurface) { }
    record CellKey(int x,int y,int z) {
        CellKey step(Vec3 axis,int sign) { return new CellKey(x+(int)axis.x()*sign,y+(int)axis.y()*sign,z+(int)axis.z()*sign); }
    }
    record Hit(Beam beam,CellKey cell) { }
    static final class Cell {
        final Block block;
        final boolean empty;
        List<BoundingBox> boxes;
        Cell(Block block,boolean waterproof) {
            this.block=block;
            Material type=block.getType();
            // Administrative hitboxes have collision, but no visible receiving surface.
            empty=type.isAir() || type==Material.LIGHT || type==Material.BARRIER || type==Material.STRUCTURE_VOID
                    || waterproof && type==Material.WATER
                    || !block.isLiquid() && block.isPassable();
        }
        List<BoundingBox> boxes(CellKey key) {
            if(boxes==null) boxes=empty ? List.of() : block.isLiquid()
                    ? List.of(new BoundingBox(key.x,key.y,key.z,key.x+1,key.y+1,key.z+1))
                    : block.getCollisionShape().getBoundingBoxes().stream()
                    .map(box->box.clone().shift(key.x,key.y,key.z)).toList();
            return boxes;
        }
    }
    /** One bounded scan shares block/chunk reads between its rays; it never loads a chunk. */
    static final class Context {
        final World world;
        final boolean waterproof;
        final int minHeight,maxHeight;
        final Map<CellKey,Cell> cells=new HashMap<>();
        final Map<Long,Boolean> chunks=new HashMap<>();
        Context(World world,boolean waterproof) {
            this.world=world; this.waterproof=waterproof;
            minHeight=world.getMinHeight(); maxHeight=world.getMaxHeight();
        }
        Cell cell(CellKey key) {
            if(key.y<minHeight || key.y>=maxHeight) return null;
            int cx=key.x>>4,cz=key.z>>4;
            long chunk=((long)cx<<32)^(cz&0xffffffffL);
            if(!chunks.computeIfAbsent(chunk,ignored->world.isChunkLoaded(cx,cz))) return null;
            return cells.computeIfAbsent(key,ignored->new Cell(world.getBlockAt(key.x,key.y,key.z),waterproof));
        }
        boolean occupied(Vec3 point) {
            CellKey key=new CellKey((int)Math.floor(point.x()),(int)Math.floor(point.y()),(int)Math.floor(point.z()));
            Cell cell=cell(key);
            return cell==null || cell.boxes(key).stream().anyMatch(box->box.contains(point.x(),point.y(),point.z()));
        }
        Hit trace(Vec3 origin,Vec3 direction,double range) {
            Hit[] result={new Hit(new Beam(origin.add(direction.scale(range)),direction.scale(-1),range,false),null)};
            Location start=new Location(world,origin.x(),origin.y(),origin.z());
            var vector=direction.toBukkit();
            VoxelRay.walk(origin,direction,range,(x,y,z,entered,leaves)-> {
                CellKey key=new CellKey(x,y,z);
                Cell cell=cell(key);
                if(cell==null) {
                    double distance=Math.max(0,entered-.05);
                    result[0]=new Hit(new Beam(origin.add(direction.scale(distance)),direction.scale(-1),distance,false),null);
                    return false;
                }
                if(cell.empty) return true;
                RayTraceResult hit=cell.block.rayTrace(start,vector,range,waterproof ? FluidCollisionMode.NEVER : FluidCollisionMode.ALWAYS);
                if(hit==null) return true;
                Vec3 point=Vec3.of(hit.getHitPosition());
                Vec3 normal=hit.getHitBlockFace()==null ? direction.scale(-1) : Vec3.of(hit.getHitBlockFace().getDirection());
                result[0]=new Hit(new Beam(point,normal,Math.sqrt(point.add(origin.scale(-1)).lengthSquared()),true),key);
                return false;
            });
            return result[0];
        }
    }

    public Beam sample(World world, Vec3 origin, Vec3 direction, FlashlightSettings settings) {
        return trace(world, origin, direction, settings.range(), settings.waterproof());
    }

    public Beam trace(World world, Vec3 origin, Vec3 direction, double range, boolean waterproof) {
        return new Context(world,waterproof).trace(origin,direction,range).beam();
    }
}
