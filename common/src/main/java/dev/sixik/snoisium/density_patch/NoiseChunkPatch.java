package dev.sixik.snoisium.density_patch;

public interface NoiseChunkPatch {

    void prepareAllGrids();

    void setBlockPos(int x, int y, int z);
}
