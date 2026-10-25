package es.mrdino.flashlight.light;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Light;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.IntFunction;

/** Main-thread only. Every frame aggregates ALL players before changing any light. */
public final class LightEngine {
    private record Lease(Material original, int level) { }
    private record Change(Block block, BlockData data) { }

    private final NamespacedKey ledgerKey;
    private final Function<UUID, World> worlds;
    private final IntFunction<BlockData> lightData;
    private final Function<Material, BlockData> originalData;
    private final Map<BlockKey, Lease> active = new HashMap<>();
    private final Map<BlockKey.ChunkKey, Map<BlockKey, Lease>> chunks = new HashMap<>();

    public LightEngine(NamespacedKey ledgerKey) {
        this(ledgerKey, Bukkit::getWorld, cachedLights(), Material::createBlockData);
    }

    LightEngine(NamespacedKey ledgerKey, Function<UUID, World> worlds,
                IntFunction<BlockData> lightData, Function<Material, BlockData> originalData) {
        this.ledgerKey = ledgerKey;
        this.worlds = worlds;
        this.lightData = lightData;
        this.originalData = originalData;
    }

    private static IntFunction<BlockData> cachedLights() {
        BlockData[] levels = new BlockData[16];
        for (int level = 1; level <= 15; level++) {
            Light data = (Light) Material.LIGHT.createBlockData();
            data.setLevel(level);
            levels[level] = data;
        }
        return level -> levels[level];
    }

    public void apply(Map<BlockKey, Integer> requested) {
        List<Change> changes = new ArrayList<>();
        Set<BlockKey.ChunkKey> dirty = new HashSet<>();
        Set<BlockKey> externallyChanged = new HashSet<>();
        for (var entry : List.copyOf(active.entrySet())) {
            BlockKey key = entry.getKey();
            Lease lease = entry.getValue();
            Block block = loadedBlock(key);
            if (block == null) {
                // Its chunk journal remains available for the next ChunkLoadEvent.
                unregister(key, dirty);
                continue;
            }
            if (!isOwned(block, lease)) {
                unregister(key, dirty);
                externallyChanged.add(key);
                continue;
            }
            int wanted = Math.clamp(requested.getOrDefault(key, 0), 0, 15);
            if (wanted == 0) {
                unregister(key, dirty);
                changes.add(new Change(block, originalData.apply(lease.original())));
            } else if (wanted != lease.level()) {
                register(key, new Lease(lease.original(), wanted), dirty);
                changes.add(new Change(block, lightData.apply(wanted)));
            }
        }
        for (var entry : requested.entrySet()) {
            BlockKey key = entry.getKey();
            if (active.containsKey(key) || externallyChanged.contains(key)) continue;
            int level = Math.clamp(entry.getValue(), 0, 15);
            Block block = loadedBlock(key);
            if (level == 0 || block == null) continue;
            Material original = block.getType();
            // Never replace water, plants, structures, or light blocks belonging to a map/plugin.
            if (original != Material.AIR && original != Material.CAVE_AIR) continue;
            register(key, new Lease(original, level), dirty);
            changes.add(new Change(block, lightData.apply(level)));
        }
        // Journal first, then mutate blocks. Both are captured in the same chunk save.
        for (BlockKey.ChunkKey key : dirty) writeLedger(key);
        for (Change change : changes) change.block().setBlockData(change.data(), false);
    }

    private void register(BlockKey key, Lease lease, Set<BlockKey.ChunkKey> dirty) {
        active.put(key, lease);
        chunks.computeIfAbsent(key.chunk(), ignored -> new LinkedHashMap<>()).put(key, lease);
        dirty.add(key.chunk());
    }

    private void unregister(BlockKey key, Set<BlockKey.ChunkKey> dirty) {
        active.remove(key);
        Map<BlockKey, Lease> entries = chunks.get(key.chunk());
        if (entries != null) {
            entries.remove(key);
            if (entries.isEmpty()) chunks.remove(key.chunk());
        }
        dirty.add(key.chunk());
    }

    private Block loadedBlock(BlockKey key) {
        World world = worlds.apply(key.world());
        if (world == null || key.y() < world.getMinHeight() || key.y() >= world.getMaxHeight()
                || !world.isChunkLoaded(key.x() >> 4, key.z() >> 4)) return null;
        return world.getBlockAt(key.x(), key.y(), key.z());
    }

    private static boolean isOwned(Block block, Lease lease) {
        return block.getType() == Material.LIGHT && block.getBlockData() instanceof Light light
                && !light.isWaterlogged() && light.getLevel() == lease.level();
    }

    private void writeLedger(BlockKey.ChunkKey key) {
        World world = worlds.apply(key.world());
        if (world == null || !world.isChunkLoaded(key.x(), key.z())) return;
        Chunk chunk = world.getChunkAt(key.x(), key.z());
        Map<BlockKey, Lease> entries = chunks.get(key);
        if (entries == null || entries.isEmpty()) {
            chunk.getPersistentDataContainer().remove(ledgerKey);
            return;
        }
        List<LightLedger.Entry> saved = new ArrayList<>();
        entries.forEach((position, lease) -> saved.add(new LightLedger.Entry(
                position.x() & 15, position.y(), position.z() & 15, lease.original())));
        chunk.getPersistentDataContainer().set(ledgerKey, PersistentDataType.STRING, LightLedger.encode(saved));
    }

    public void recover(Chunk chunk) {
        String saved = chunk.getPersistentDataContainer().get(ledgerKey, PersistentDataType.STRING);
        if (saved == null) return;
        World world = chunk.getWorld();
        for (LightLedger.Entry entry : LightLedger.decode(saved, world.getMinHeight(), world.getMaxHeight())) {
            Block block = chunk.getBlock(entry.localX(), entry.y(), entry.localZ());
            if (block.getType() == Material.LIGHT && block.getBlockData() instanceof Light light
                    && !light.isWaterlogged()) {
                block.setBlockData(originalData.apply(entry.original()), false);
            }
        }
        chunk.getPersistentDataContainer().remove(ledgerKey);
    }

    public void unload(Chunk chunk) {
        BlockKey.ChunkKey key = new BlockKey.ChunkKey(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ());
        Map<BlockKey, Lease> entries = chunks.remove(key);
        if (entries != null) {
            entries.forEach((position, lease) -> {
                Block block = chunk.getBlock(position.x() & 15, position.y(), position.z() & 15);
                if (isOwned(block, lease)) block.setBlockData(originalData.apply(lease.original()), false);
                active.remove(position);
            });
        }
        if (entries == null) recover(chunk);
        else chunk.getPersistentDataContainer().remove(ledgerKey);
    }

    public void clear() { apply(Map.of()); }
    public int activeCount() { return active.size(); }
}
