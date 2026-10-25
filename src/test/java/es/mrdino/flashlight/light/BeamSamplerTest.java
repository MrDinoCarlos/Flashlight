package es.mrdino.flashlight.light;

import es.mrdino.flashlight.math.Vec3;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BeamSamplerTest {
    private World world() {
        World world=mock(World.class);
        when(world.getMinHeight()).thenReturn(-64); when(world.getMaxHeight()).thenReturn(320);
        when(world.isChunkLoaded(anyInt(),anyInt())).thenReturn(true);
        Block air=mock(Block.class); when(air.getType()).thenReturn(Material.AIR);
        when(world.getBlockAt(anyInt(),anyInt(),anyInt())).thenReturn(air);
        return world;
    }

    @Test void stopsAtTheActualCollisionShapeAndReturnsItsReceivingFace() {
        World world=world(); Block slab=mock(Block.class); when(slab.getType()).thenReturn(Material.STONE_SLAB);
        when(world.getBlockAt(0,65,4)).thenReturn(slab);
        when(slab.rayTrace(any(),any(),eq(20.0),eq(FluidCollisionMode.ALWAYS)))
                .thenReturn(new RayTraceResult(new Vector(.5,65.25,4.75),slab,BlockFace.NORTH));
        var result=new BeamSampler().trace(world,new Vec3(.5,65.25,.5),new Vec3(0,0,1),20,false);
        assertTrue(result.hitSurface()); assertEquals(4.25,result.distance());
        assertEquals(new Vec3(0,0,-1),result.normal());
        verify(world,never()).getBlockAt(0,65,5);
    }

    @Test void doesNotLoadChunksOrInventAnImpactAtAnUnloadedBoundary() {
        World world=world(); when(world.isChunkLoaded(0,1)).thenReturn(false);
        var result=new BeamSampler().trace(world,new Vec3(.5,65.5,.5),new Vec3(0,0,1),32,false);
        assertFalse(result.hitSurface()); assertEquals(15.45,result.distance(),1e-8);
        verify(world,never()).getBlockAt(0,65,16);
    }

    @Test void invisibleAdministrativeBlocksNeitherStopTheBeamNorProvideACollisionSurface() {
        World world=world();
        Material[] invisible={Material.BARRIER,Material.LIGHT,Material.STRUCTURE_VOID};
        for(int i=0;i<invisible.length;i++) {
            Block block=mock(Block.class); when(block.getType()).thenReturn(invisible[i]);
            when(world.getBlockAt(0,65,i+1)).thenReturn(block);
            var cell=new BeamSampler.Cell(block,false);
            assertTrue(cell.empty,invisible[i]+" must be optically empty even with solid collision");
            assertTrue(cell.boxes(new BeamSampler.CellKey(0,65,i+1)).isEmpty());
        }
        Block wall=mock(Block.class); when(wall.getType()).thenReturn(Material.STONE);
        when(world.getBlockAt(0,65,5)).thenReturn(wall);
        when(wall.rayTrace(any(),any(),anyDouble(),any()))
                .thenReturn(new RayTraceResult(new Vector(.5,65.5,5),wall,BlockFace.NORTH));
        var result=new BeamSampler().trace(world,new Vec3(.5,65.5,.5),new Vec3(0,0,1),20,false);
        assertTrue(result.hitSurface()); assertEquals(4.5,result.distance());
    }

    @Test void cachedBlocksCannotBypassAChunkThatUnloadedBetweenScans() {
        World world=world(); var context=new BeamSampler.Context(world,false);
        Vec3 origin=new Vec3(.5,65.5,.5),direction=new Vec3(0,0,1);
        context.trace(origin,direction,8);
        when(world.isChunkLoaded(0,0)).thenReturn(false);
        clearInvocations(world); context.chunks.clear();
        var result=context.trace(origin,direction,8).beam();
        assertFalse(result.hitSurface()); assertEquals(0,result.distance());
        verify(world,never()).getBlockAt(anyInt(),anyInt(),anyInt());
    }
}
