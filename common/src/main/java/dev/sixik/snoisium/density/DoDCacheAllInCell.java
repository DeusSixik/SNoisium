package dev.sixik.snoisium.density;

import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseChunk;

public class DoDCacheAllInCell implements DensityFunctions.MarkerOrMarked, NoiseChunk.NoiseChunkDensityFunction {
    private final DensityFunction function;

    public DoDCacheAllInCell(DensityFunction densityFunction) {
        this.function = densityFunction;
    }

    @Override
    public double compute(DensityFunction.FunctionContext context) {
        // Никаких массивов inCellX/Y/Z. Просто прямое вычисление!
        return this.function.compute(context);
    }

    @Override
    public void fillArray(double[] ds, DensityFunction.ContextProvider contextProvider) {
        contextProvider.fillAllDirectly(ds, this);
    }

    @Override
    public DensityFunction wrapped() {
        return this.function;
    }

    @Override
    public DensityFunctions.Marker.Type type() {
        return DensityFunctions.Marker.Type.CacheAllInCell;
    }
}
