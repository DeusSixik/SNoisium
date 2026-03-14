package dev.sixik.snoisium.density;

import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseChunk;

public class DoDCacheOnce implements DensityFunctions.MarkerOrMarked, NoiseChunk.NoiseChunkDensityFunction {
    private final DensityFunction function;

    // Храним последние запрошенные координаты
    private int lastX = Integer.MIN_VALUE;
    private int lastY = Integer.MIN_VALUE;
    private int lastZ = Integer.MIN_VALUE;
    private double lastValue;

    public DoDCacheOnce(DensityFunction densityFunction) {
        this.function = densityFunction;
    }

    @Override
    public double compute(DensityFunction.FunctionContext context) {
        int x = context.blockX();
        int y = context.blockY();
        int z = context.blockZ();

        // Если координаты не изменились, мгновенно отдаем кэш (Cache Hit)
        if (this.lastX == x && this.lastY == y && this.lastZ == z) {
            return this.lastValue;
        }

        // Иначе пересчитываем и обновляем (Cache Miss)
        this.lastX = x;
        this.lastY = y;
        this.lastZ = z;
        this.lastValue = this.function.compute(context);
        return this.lastValue;
    }

    @Override
    public void fillArray(double[] ds, DensityFunction.ContextProvider contextProvider) {
        this.function.fillArray(ds, contextProvider);
    }

    @Override
    public DensityFunction wrapped() {
        return this.function;
    }

    @Override
    public DensityFunctions.Marker.Type type() {
        return DensityFunctions.Marker.Type.CacheOnce;
    }
}