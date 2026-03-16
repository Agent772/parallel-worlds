package com.agent772.parallelworlds.generation;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;

/**
 * Generates chunk positions spiraling outward from a center point.
 * Direction order: North → East → South → West (clockwise).
 * All state is serializable to NBT for resumability.
 */
public class SpiralIterator {
    private final int centerX;
    private final int centerZ;
    private final int radius;

    private int currentX;
    private int currentZ;
    private int dx;
    private int dz;
    private int stepsInCurrentDirection;
    private int stepsInCurrentSide;
    private int sidesCompleted;
    private long chunksReturned;
    private final long totalChunks;

    public SpiralIterator(int centerX, int centerZ, int radius) {
        this.centerX = centerX;
        this.centerZ = centerZ;
        this.radius = radius;
        this.totalChunks = (long) (2 * radius + 1) * (2 * radius + 1);

        // Start at center
        this.currentX = centerX;
        this.currentZ = centerZ;
        // First direction: North (dz = -1)
        this.dx = 0;
        this.dz = -1;
        this.stepsInCurrentDirection = 1;
        this.stepsInCurrentSide = 0;
        this.sidesCompleted = 0;
        this.chunksReturned = 0;
    }

    public boolean hasNext() {
        return chunksReturned < totalChunks;
    }

    /**
     * Peek at the next chunk position without consuming it.
     * Returns null if the iterator is exhausted.
     */
    public ChunkPos peek() {
        if (!hasNext()) return null;
        return new ChunkPos(currentX, currentZ);
    }

    /**
     * Peek ahead N positions without consuming from the iterator.
     * Returns up to {@code count} upcoming chunk positions.
     * Position 0 = same as peek() (the immediate next).
     */
    public java.util.List<ChunkPos> peekAhead(int count) {
        java.util.List<ChunkPos> result = new java.util.ArrayList<>(count);
        if (!hasNext()) return result;

        // Save state
        int savedX = currentX, savedZ = currentZ;
        int savedDx = dx, savedDz = dz;
        int savedStepsDir = stepsInCurrentDirection;
        int savedStepsSide = stepsInCurrentSide;
        int savedSides = sidesCompleted;
        long savedReturned = chunksReturned;

        for (int i = 0; i < count && hasNext(); i++) {
            result.add(new ChunkPos(currentX, currentZ));
            chunksReturned++;
            if (chunksReturned < totalChunks) {
                advance();
            }
        }

        // Restore state
        currentX = savedX; currentZ = savedZ;
        dx = savedDx; dz = savedDz;
        stepsInCurrentDirection = savedStepsDir;
        stepsInCurrentSide = savedStepsSide;
        sidesCompleted = savedSides;
        chunksReturned = savedReturned;

        return result;
    }

    public ChunkPos next() {
        if (!hasNext()) {
            throw new java.util.NoSuchElementException("SpiralIterator exhausted");
        }

        ChunkPos pos = new ChunkPos(currentX, currentZ);
        chunksReturned++;

        // Advance to next position
        if (chunksReturned < totalChunks) {
            advance();
        }

        return pos;
    }

    private void advance() {
        // First chunk (center) doesn't advance direction
        if (chunksReturned == 1) {
            // Move in the initial direction (North)
            currentX += dx;
            currentZ += dz;
            stepsInCurrentSide = 1;
            return;
        }

        stepsInCurrentSide++;

        if (stepsInCurrentSide >= stepsInCurrentDirection) {
            // Turn clockwise: N→E→S→W
            stepsInCurrentSide = 0;
            sidesCompleted++;

            int oldDx = dx;
            dx = -dz;
            dz = oldDx;

            // After every 2 sides, increase the arm length
            if (sidesCompleted % 2 == 0) {
                stepsInCurrentDirection++;
            }
        }

        currentX += dx;
        currentZ += dz;
    }

    public long getTotalChunks() {
        return totalChunks;
    }

    public long getChunksReturned() {
        return chunksReturned;
    }

    public CompoundTag toNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("centerX", centerX);
        tag.putInt("centerZ", centerZ);
        tag.putInt("radius", radius);
        tag.putInt("currentX", currentX);
        tag.putInt("currentZ", currentZ);
        tag.putInt("dx", dx);
        tag.putInt("dz", dz);
        tag.putInt("stepsInCurrentDirection", stepsInCurrentDirection);
        tag.putInt("stepsInCurrentSide", stepsInCurrentSide);
        tag.putInt("sidesCompleted", sidesCompleted);
        tag.putLong("chunksReturned", chunksReturned);
        return tag;
    }

    public static SpiralIterator fromNbt(CompoundTag tag) {
        int centerX = tag.getInt("centerX");
        int centerZ = tag.getInt("centerZ");
        int radius = tag.getInt("radius");

        SpiralIterator iterator = new SpiralIterator(centerX, centerZ, radius);
        iterator.currentX = tag.getInt("currentX");
        iterator.currentZ = tag.getInt("currentZ");
        iterator.dx = tag.getInt("dx");
        iterator.dz = tag.getInt("dz");
        iterator.stepsInCurrentDirection = tag.getInt("stepsInCurrentDirection");
        iterator.stepsInCurrentSide = tag.getInt("stepsInCurrentSide");
        iterator.sidesCompleted = tag.getInt("sidesCompleted");
        iterator.chunksReturned = tag.getLong("chunksReturned");
        return iterator;
    }
}
