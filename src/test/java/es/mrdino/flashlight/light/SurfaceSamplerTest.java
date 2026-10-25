package es.mrdino.flashlight.light;

import es.mrdino.flashlight.FlashlightSettings;
import es.mrdino.flashlight.math.Vec3;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.VoxelShape;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SurfaceSamplerTest {
    private final Map<BeamSampler.CellKey,BoundingBox> solids=new HashMap<>();
    private final Map<BeamSampler.CellKey,Material> materials=new HashMap<>();
    private FlashlightSettings settings() { return FlashlightSettings.load(new YamlConfiguration()); }
    private World world() {
        World world=mock(World.class);
        when(world.getMinHeight()).thenReturn(-64); when(world.getMaxHeight()).thenReturn(320);
        when(world.isChunkLoaded(anyInt(),anyInt())).thenReturn(true);
        Map<BeamSampler.CellKey,Block> blocks=new HashMap<>();
        when(world.getBlockAt(anyInt(),anyInt(),anyInt())).thenAnswer(call->{
            var key=new BeamSampler.CellKey(call.getArgument(0),call.getArgument(1),call.getArgument(2));
            return blocks.computeIfAbsent(key,k->{
                Block block=mock(Block.class); BoundingBox bounds=solids.get(k);
                when(block.getType()).thenAnswer(ignored->solids.containsKey(k) ? materials.getOrDefault(k,Material.STONE) : Material.AIR);
                if(bounds!=null) {
                    VoxelShape shape=mock(VoxelShape.class);
                    when(shape.getBoundingBoxes()).thenReturn(List.of(bounds.clone().shift(-k.x(),-k.y(),-k.z())));
                    when(block.getCollisionShape()).thenReturn(shape);
                    when(block.rayTrace(any(),any(),anyDouble(),any())).thenAnswer(trace->{
                        var start=(org.bukkit.Location)trace.getArgument(0);
                        RayTraceResult hit=bounds.rayTrace(start.toVector(),trace.getArgument(1),trace.getArgument(2));
                        return hit==null ? null : new RayTraceResult(hit.getHitPosition(),block,hit.getHitBlockFace());
                    });
                }
                return block;
            });
        });
        return world;
    }
    private void cube(int x,int y,int z) { solids.put(new BeamSampler.CellKey(x,y,z),new BoundingBox(x,y,z,x+1,y+1,z+1)); }

    @Test void lightsAForegroundBlockAndTheWallAroundItInOneBoundedScan() {
        for(int x=-4;x<=4;x++) for(int y=61;y<=69;y++) cube(x,y,8);
        cube(0,65,4);
        var result=new SurfaceSampler().sample(world(),new Vec3(.5,65.5,.5),new Vec3(0,0,1),.087,settings());
        assertTrue(result.patches().stream().anyMatch(f->Math.abs(f.center().z()-4)<1e-6),"Foreground face");
        assertTrue(result.patches().stream().anyMatch(f->Math.abs(f.center().z()-8)<1e-6),"Background wall");
        assertTrue(result.visibleRange()>7.5,"The centre hit must not truncate the rest of the cone");
        assertTrue(result.patches().size()<=12,"Hard entity budget");
        result.patches().stream().filter(f->Math.abs(f.center().z()-4)<1e-6).forEach(f->{
            assertEquals(1,f.width(),1e-6); assertEquals(1,f.height(),1e-6);
        });
    }

    @Test void respectsPartialCollisionFacesRatherThanInventingAFullCube() {
        solids.put(new BeamSampler.CellKey(0,65,4),new BoundingBox(0,65,4,1,65.5,5));
        var result=new SurfaceSampler().sample(world(),new Vec3(.5,65.25,.5),new Vec3(0,0,1),.087,settings());
        assertTrue(result.patches().stream().anyMatch(face->face.height()==.5));
    }

    @Test void invisibleHitboxesCannotReceiveLightOrHideTheVisibleWallBehindThem() {
        for(int x=-4;x<=4;x++) for(int y=61;y<=69;y++) cube(x,y,8);
        Material[] invisible={Material.BARRIER,Material.LIGHT,Material.STRUCTURE_VOID};
        for(int i=0;i<invisible.length;i++) {
            for(int x=-2;x<=2;x++) for(int y=63;y<=67;y++) {
                cube(x,y,i+3); materials.put(new BeamSampler.CellKey(x,y,i+3),invisible[i]);
            }
        }
        var result=new SurfaceSampler().sample(world(),new Vec3(.5,65.5,.5),new Vec3(0,0,1),.087,settings());
        assertFalse(result.patches().isEmpty());
        assertTrue(result.patches().stream().allMatch(face->Math.abs(face.center().z()-8)<1e-6));
    }

    @Test void previousCoverageIsRevalidatedInsteadOfRetainingLightOnARemovedBlock() {
        cube(0,65,4);
        World world=world(); var sampler=new SurfaceSampler();
        var first=sampler.sample(world,new Vec3(.5,65.5,.5),new Vec3(0,0,1),.087,settings());
        assertFalse(first.patches().isEmpty());
        solids.clear();
        var next=sampler.sample(new BeamSampler.Context(world,false),new Vec3(.5,65.5,.5),new Vec3(0,0,1),.087,settings(),first.patches());
        assertTrue(next.patches().isEmpty(),"Temporal coverage must still respect current blocks");
    }

    @Test void discoveryOrderDoesNotChangeTheMergedRectangles() {
        Vec3 n=new Vec3(0,0,1);
        var source=List.of(new SurfacePatch(n,-8,0,0,1,1),new SurfacePatch(n,-8,1,0,2,1),new SurfacePatch(n,-8,0,1,1,2));
        var reference=new ArrayList<>(source); SurfaceSampler.merge(reference);
        for(int seed=0;seed<20;seed++) {
            var shuffled=new ArrayList<>(source); java.util.Collections.shuffle(shuffled,new java.util.Random(seed));
            SurfaceSampler.merge(shuffled); assertEquals(reference,shuffled);
        }
    }

    @Test void mergingDoesNotBridgeAWindowOrAnLShapedGap() {
        Vec3 n=new Vec3(0,0,1);
        var faces=new ArrayList<>(List.of(new SurfacePatch(n,8,0,0,1,1),new SurfacePatch(n,8,1,0,2,1),new SurfacePatch(n,8,0,1,1,2)));
        SurfaceSampler.merge(faces);
        assertEquals(2,faces.size());
        assertEquals(3,faces.stream().mapToDouble(f->f.width()*f.height()).sum());
    }

    @Test void aHorizontalConeProjectsOntoTheFloorEvenWhenItsAxisNeverHitsIt() {
        var face=new SurfacePatch(new Vec3(0,1,0),64,0,-8,1,-4);
        var projection=SurfaceProjection.of(face,new Vec3(.5,65,.5),new Vec3(0,0,1),.087,28,20,1);
        assertNotNull(projection); assertTrue(projection.model().startsWith("surface_h_"));
    }
}
