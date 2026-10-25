package es.mrdino.flashlight.light;

import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Light;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LightEngineTest {
    private final UUID worldId = UUID.randomUUID();
    private final NamespacedKey ledgerKey = new NamespacedKey("flashlight", "temporary_lights");
    private final Map<BlockKey, Block> blocks = new HashMap<>();
    private final Map<Material, BlockData> originals = new HashMap<>();
    private World world;
    private Chunk chunk;
    private PersistentDataContainer pdc;
    private AtomicReference<String> journal;
    private LightEngine engine;

    @BeforeEach
    void setup() {
        world = mock(World.class);
        chunk = mock(Chunk.class);
        pdc = mock(PersistentDataContainer.class);
        journal = new AtomicReference<>();
        when(world.getUID()).thenReturn(worldId);
        when(world.getMinHeight()).thenReturn(-64);
        when(world.getMaxHeight()).thenReturn(320);
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
        when(world.getChunkAt(anyInt(), anyInt())).thenReturn(chunk);
        when(chunk.getWorld()).thenReturn(world);
        when(chunk.getPersistentDataContainer()).thenReturn(pdc);
        when(pdc.get(ledgerKey, PersistentDataType.STRING)).thenAnswer(ignored -> journal.get());
        doAnswer(call -> { journal.set(call.getArgument(2)); return null; })
                .when(pdc).set(eq(ledgerKey), eq(PersistentDataType.STRING), anyString());
        doAnswer(call -> { journal.set(null); return null; }).when(pdc).remove(ledgerKey);
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(call ->
                blocks.get(new BlockKey(worldId, call.getArgument(0), call.getArgument(1), call.getArgument(2))));
        when(chunk.getBlock(anyInt(), anyInt(), anyInt())).thenAnswer(call ->
                blocks.get(new BlockKey(worldId, call.getArgument(0), call.getArgument(1), call.getArgument(2))));
        engine = new LightEngine(ledgerKey, id -> id.equals(worldId) ? world : null,
                this::lightData, this::originalData);
    }

    private BlockData originalData(Material material) {
        return originals.computeIfAbsent(material, type -> {
            BlockData data = mock(BlockData.class);
            when(data.getMaterial()).thenReturn(type);
            return data;
        });
    }

    private Light lightData(int level) {
        Light data = mock(Light.class);
        when(data.getMaterial()).thenReturn(Material.LIGHT);
        when(data.getLevel()).thenReturn(level);
        return data;
    }

    private Block block(BlockKey key, Material type) {
        Block block = mock(Block.class);
        AtomicReference<BlockData> current = new AtomicReference<>(originalData(type));
        when(block.getType()).thenAnswer(ignored -> current.get().getMaterial());
        when(block.getBlockData()).thenAnswer(ignored -> current.get());
        doAnswer(call -> {
            BlockData replacement = call.getArgument(0);
            if (replacement.getMaterial() == Material.LIGHT) {
                assertNotNull(journal.get(), "Recovery data must exist BEFORE a light block is placed.");
            }
            current.set(replacement);
            return null;
        }).when(block).setBlockData(any(BlockData.class), eq(false));
        blocks.put(key, block);
        return block;
    }

    @Test
    void overlappingPlayersKeepTheStrongestLightUntilTheLastOneLeaves() {
        BlockKey key = new BlockKey(worldId, 2, 64, 3);
        Block block = block(key, Material.CAVE_AIR);
        Map<BlockKey, Integer> both = new HashMap<>();
        both.merge(key, 7, Math::max);
        both.merge(key, 12, Math::max);
        engine.apply(both);
        assertEquals(12, ((Light) block.getBlockData()).getLevel());
        engine.apply(Map.of(key, 7));
        assertEquals(7, ((Light) block.getBlockData()).getLevel());
        engine.clear();
        assertEquals(Material.CAVE_AIR, block.getType());
        assertEquals(0, engine.activeCount());
        assertNull(journal.get());
    }

    @Test
    void unchangedFramesDoNotSendRedundantBlockUpdates() {
        BlockKey key = new BlockKey(worldId, 2, 64, 3);
        Block block = block(key, Material.AIR);
        engine.apply(Map.of(key, 10));
        engine.apply(Map.of(key, 10));
        verify(block, times(1)).setBlockData(any(BlockData.class), eq(false));
    }

    @Test
    void neverReplacesWaterStructuresOrPreexistingLightBlocks() {
        int x = 0;
        Map<BlockKey, Integer> request = new HashMap<>();
        for (Material type : new Material[]{Material.WATER, Material.STONE, Material.LIGHT, Material.SHORT_GRASS}) {
            BlockKey key = new BlockKey(worldId, x++, 64, 0);
            Block block = block(key, type);
            request.put(key, 12);
            engine.apply(request);
            assertEquals(type, block.getType());
            verify(block, never()).setBlockData(any(BlockData.class), anyBoolean());
        }
        assertEquals(0, engine.activeCount());
    }

    @Test
    void preservesABlockPlacedByAnotherPluginAndDoesNotLoadChunks() {
        BlockKey key = new BlockKey(worldId, 2, 64, 3);
        Block block = block(key, Material.AIR);
        engine.apply(Map.of(key, 10));
        block.setBlockData(originalData(Material.STONE), false);
        engine.clear();
        assertEquals(Material.STONE, block.getType());
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(false);
        clearInvocations(world);
        engine.apply(Map.of(new BlockKey(worldId, 100, 64, 100), 10));
        verify(world, never()).getBlockAt(anyInt(), anyInt(), anyInt());
        verify(world, never()).getChunkAt(anyInt(), anyInt());
    }

    @Test
    void chunkUnloadRestoresAirAndStartupRecoversARecordedLightAfterACrash() {
        BlockKey key = new BlockKey(worldId, 2, 64, 3);
        Block block = block(key, Material.AIR);
        engine.apply(Map.of(key, 10));
        String saved = journal.get();
        engine.unload(chunk);
        assertEquals(Material.AIR, block.getType());
        assertNull(journal.get());
        journal.set(saved);
        block.setBlockData(lightData(10), false);
        LightEngine restarted = new LightEngine(ledgerKey, ignored -> world, this::lightData, this::originalData);
        restarted.recover(chunk);
        assertEquals(Material.AIR, block.getType());
        assertNull(journal.get());
    }

    @Test
    void unloadingDoesNotEraseAnExternalLightWithADifferentLevel() {
        BlockKey key = new BlockKey(worldId, 2, 64, 3);
        Block block = block(key, Material.AIR);
        engine.apply(Map.of(key, 10));
        block.setBlockData(lightData(15), false);
        engine.unload(chunk);
        assertEquals(15, ((Light) block.getBlockData()).getLevel());
    }

    @Test
    void malformedRecoveryEntriesCannotOverwriteRealBlocksOrEscapeTheChunk() {
        String malformed = "1\n2,64,3,AIR\n99,64,3,AIR\n2,9999,3,AIR\n2,64,3,STONE\nbroken\n";
        assertEquals(1, LightLedger.decode(malformed, -64, 320).size());
        assertTrue(LightLedger.decode("2\n2,64,3,AIR\n", -64, 320).isEmpty());
    }
}
