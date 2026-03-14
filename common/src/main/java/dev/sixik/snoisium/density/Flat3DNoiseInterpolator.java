package dev.sixik.snoisium.density;

import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.blending.Blender;

public class Flat3DNoiseInterpolator implements DensityFunctions.MarkerOrMarked, NoiseChunk.NoiseChunkDensityFunction {

    private final DensityFunction noiseFiller;
    private final double[] grid; // Наш плоский и быстрый 1D массив
    private final int sizeX, sizeY, sizeZ;
    private final int startBlockX, startBlockY, startBlockZ;

    private final NoiseChunk noiseChunk;

    private boolean isGridReady = false;

    // Переиспользуемый контекст, чтобы не создавать объекты в цикле (Zero-Allocation)
    private final MutableGridContext mutableContext = new MutableGridContext();

    public Flat3DNoiseInterpolator(DensityFunction densityFunction, NoiseChunk noiseChunk) {
        this.noiseFiller = densityFunction;

        this.noiseChunk = noiseChunk;

        // Количество точек сетки всегда на 1 больше, чем количество ячеек
        this.sizeX = noiseChunk.cellCountXZ + 1;
        this.sizeY = noiseChunk.cellCountY + 1;
        this.sizeZ = noiseChunk.cellCountXZ + 1;

        // Вычисляем абсолютные мировые координаты начала этого чанка
        // (Используем оригинальные поля NoiseChunk для точности)
        this.startBlockX = noiseChunk.firstCellX * noiseChunk.cellWidth;
        this.startBlockY = noiseChunk.cellNoiseMinY * noiseChunk.cellHeight;
        this.startBlockZ = noiseChunk.firstCellZ * noiseChunk.cellWidth;

        // Выделяем память один раз (например, 5 * 49 * 5 = 1225 элементов типа double).
        // Это ~9.8 КБ, что идеально помещается в L1-кэш процессора!
        this.grid = new double[this.sizeX * this.sizeY * this.sizeZ];
    }

    /**
     * 1. ФАЗА ПРЕДРАСЧЕТА (Вызывается 1 раз на весь чанк)
     * Мы заполняем плоский массив значениями базового шума в опорных точках ячеек.
     */
    public void fillGrid() {
        if (this.isGridReady) return;

        // ContextProvider нужен для обратной совместимости с ванильными DensityFunction
        DensityFunction.ContextProvider gridProvider = new DensityFunction.ContextProvider() {
            @Override
            public DensityFunction.FunctionContext forIndex(int index) {
                // Декодируем 1D индекс обратно в координаты сетки
                // Порядок осей (Y самая быстрая) сделан специально для L1/L2 кэша!
                int y = index % sizeY;
                int rem = index / sizeY;
                int z = rem % sizeZ;
                int x = rem / sizeZ;

                // Обновляем мутабельный контекст реальными координатами
                mutableContext.x = startBlockX + x * noiseChunk.cellWidth;
                mutableContext.y = startBlockY + y * noiseChunk.cellHeight;
                mutableContext.z = startBlockZ + z * noiseChunk.cellWidth;

                return mutableContext;
            }

            @Override
            public void fillAllDirectly(double[] ds, DensityFunction densityFunction) {
                for (int i = 0; i < ds.length; i++) {
                    ds[i] = densityFunction.compute(forIndex(i));
                }
            }
        };



        // Делегируем заполнение ванильной функции, подсунув наш "фальшивый" провайдер
        this.noiseFiller.fillArray(this.grid, gridProvider);
        this.isGridReady = true;
    }

    /**
     * 2. ФАЗА ИНТЕРПОЛЯЦИИ (Вызывается 65536 раз на чанк)
     * Здесь магия Data-Oriented Design. Никаких плавающих окон, только чистая математика.
     */

    @Override
    public double compute(DensityFunction.FunctionContext functionContext) {
        // Если запрос пришел не от генератора блоков (а извне), считаем честно без кэша
        if (functionContext != noiseChunk) {
            return this.noiseFiller.compute(functionContext);
        }

        if (!this.isGridReady) {
            this.fillGrid();
        }

        // Берем текущие абсолютные координаты блока (которые мы сетим в doFill)
        int blockX = noiseChunk.blockX();
        int blockY = noiseChunk.blockY();
        int blockZ = noiseChunk.blockZ();

        // Переводим в локальные координаты относительно начала интерполятора
        int localX = blockX - this.startBlockX;
        int localY = blockY - this.startBlockY;
        int localZ = blockZ - this.startBlockZ;

        int cellWidth = noiseChunk.cellWidth;
        int cellHeight = noiseChunk.cellHeight;

        // Находим координаты левого-нижнего угла ячейки в сетке
        int gx = localX / cellWidth;
        int gy = localY / cellHeight;
        int gz = localZ / cellWidth;

        // Вычисляем процент смещения (0.0 ... 1.0) внутри ячейки
        double deltaX = (double) (localX % cellWidth) / cellWidth;
        double deltaY = (double) (localY % cellHeight) / cellHeight;
        double deltaZ = (double) (localZ % cellWidth) / cellWidth;

        // Извлекаем 8 опорных точек куба из плоского кэша
        // Процессор проглотит это мгновенно, так как они лежат рядом в памяти
        double v000 = getGridValue(gx, gy, gz);
        double v100 = getGridValue(gx + 1, gy, gz);
        double v010 = getGridValue(gx, gy + 1, gz);
        double v110 = getGridValue(gx + 1, gy + 1, gz);
        double v001 = getGridValue(gx, gy, gz + 1);
        double v101 = getGridValue(gx + 1, gy, gz + 1);
        double v011 = getGridValue(gx, gy + 1, gz + 1);
        double v111 = getGridValue(gx + 1, gy + 1, gz + 1);

        // Трилинейная интерполяция
        return Mth.lerp3(deltaX, deltaY, deltaZ, v000, v100, v010, v110, v001, v101, v011, v111);
    }

    // Вспомогательный метод для получения значения по 3D координатам сетки
    private double getGridValue(int x, int y, int z) {
        // Формула 1D индекса. Ось Y меняется быстрее всего для оптимизации кэш-линий (spatial locality)
        return this.grid[(x * this.sizeZ + z) * this.sizeY + y];
    }

    // --- Обратная совместимость с MarkerOrMarked ---

    @Override
    public void fillArray(double[] ds, DensityFunction.ContextProvider contextProvider) {
        // Раз мы stateless, мы просто заполняем массив напрямую
        contextProvider.fillAllDirectly(ds, this);
    }

    @Override
    public DensityFunction wrapped() {
        return this.noiseFiller;
    }

    @Override
    public DensityFunctions.Marker.Type type() {
        return DensityFunctions.Marker.Type.Interpolated;
    }

    // --- Zero-Allocation контекст ---

    private class MutableGridContext implements DensityFunction.FunctionContext {
        int x, y, z;

        @Override public int blockX() { return x; }
        @Override public int blockY() { return y; }
        @Override public int blockZ() { return z; }

        @Override public Blender getBlender() {
            return noiseChunk.getBlender();
        }
    }
}
