package es.mrdino.flashlight.light;

import java.util.UUID;

public record BlockKey(UUID world, int x, int y, int z) {
    public ChunkKey chunk() { return new ChunkKey(world, x >> 4, z >> 4); }
    public record ChunkKey(UUID world, int x, int z) { }
}
