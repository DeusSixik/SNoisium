```
package net.minecraft.world.level.levelgen;

public class NoiseChunk
implements DensityFunction.ContextProvider,
DensityFunction.FunctionContext {
    private final NoiseSettings noiseSettings;
    final int cellCountXZ;
    final int cellCountY;
    final int cellNoiseMinY;
    private final int firstCellX;
    private final int firstCellZ;
    final int firstNoiseX;
    final int firstNoiseZ;
    final List<NoiseInterpolator> interpolators;
    final List<CacheAllInCell> cellCaches;
    private final Map<DensityFunction, DensityFunction> wrapped = new HashMap<DensityFunction, DensityFunction>();
    private final Long2IntMap preliminarySurfaceLevel = new Long2IntOpenHashMap();
    private final Aquifer aquifer;
    private final DensityFunction initialDensityNoJaggedness;
    private final BlockStateFiller blockStateRule;
    private final Blender blender;
    private final FlatCache blendAlpha;
    private final FlatCache blendOffset;
    private final DensityFunctions.BeardifierOrMarker beardifier;
    private long lastBlendingDataPos = ChunkPos.INVALID_CHUNK_POS;
    private Blender.BlendingOutput lastBlendingOutput = new Blender.BlendingOutput(1.0, 0.0);
    final int noiseSizeXZ;
    final int cellWidth;
    final int cellHeight;
    boolean interpolating;
    boolean fillingCell;
    private int cellStartBlockX;
    int cellStartBlockY;
    private int cellStartBlockZ;
    int inCellX;
    int inCellY;
    int inCellZ;
    long interpolationCounter;
    long arrayInterpolationCounter;
    int arrayIndex;
    private final DensityFunction.ContextProvider sliceFillingContextProvider = new DensityFunction.ContextProvider(){

        @Override
        public DensityFunction.FunctionContext forIndex(int i) {
            NoiseChunk.this.cellStartBlockY = (i + NoiseChunk.this.cellNoiseMinY) * NoiseChunk.this.cellHeight;
            ++NoiseChunk.this.interpolationCounter;
            NoiseChunk.this.inCellY = 0;
            NoiseChunk.this.arrayIndex = i;
            return NoiseChunk.this;
        }

        @Override
        public void fillAllDirectly(double[] ds, DensityFunction densityFunction) {
            for (int i = 0; i < NoiseChunk.this.cellCountY + 1; ++i) {
                NoiseChunk.this.cellStartBlockY = (i + NoiseChunk.this.cellNoiseMinY) * NoiseChunk.this.cellHeight;
                ++NoiseChunk.this.interpolationCounter;
                NoiseChunk.this.inCellY = 0;
                NoiseChunk.this.arrayIndex = i;
                ds[i] = densityFunction.compute(NoiseChunk.this);
            }
        }
    };

    public static NoiseChunk forChunk(ChunkAccess chunkAccess, RandomState randomState, DensityFunctions.BeardifierOrMarker beardifierOrMarker, NoiseGeneratorSettings noiseGeneratorSettings, Aquifer.FluidPicker fluidPicker, Blender blender) {
        NoiseSettings noiseSettings = noiseGeneratorSettings.noiseSettings().clampToHeightAccessor(chunkAccess);
        ChunkPos chunkPos = chunkAccess.getPos();
        int i = 16 / noiseSettings.getCellWidth();
        return new NoiseChunk(i, randomState, chunkPos.getMinBlockX(), chunkPos.getMinBlockZ(), noiseSettings, beardifierOrMarker, noiseGeneratorSettings, fluidPicker, blender);
    }

    public NoiseChunk(int i, RandomState randomState, int j, int k, NoiseSettings noiseSettings, DensityFunctions.BeardifierOrMarker beardifierOrMarker, NoiseGeneratorSettings noiseGeneratorSettings, Aquifer.FluidPicker fluidPicker, Blender blender) {
        int o;
        int n;
        this.noiseSettings = noiseSettings;
        this.cellWidth = noiseSettings.getCellWidth();
        this.cellHeight = noiseSettings.getCellHeight();
        this.cellCountXZ = i;
        this.cellCountY = Mth.floorDiv(noiseSettings.height(), this.cellHeight);
        this.cellNoiseMinY = Mth.floorDiv(noiseSettings.minY(), this.cellHeight);
        this.firstCellX = Math.floorDiv(j, this.cellWidth);
        this.firstCellZ = Math.floorDiv(k, this.cellWidth);
        this.interpolators = Lists.newArrayList();
        this.cellCaches = Lists.newArrayList();
        this.firstNoiseX = QuartPos.fromBlock(j);
        this.firstNoiseZ = QuartPos.fromBlock(k);
        this.noiseSizeXZ = QuartPos.fromBlock(i * this.cellWidth);
        this.blender = blender;
        this.beardifier = beardifierOrMarker;
        this.blendAlpha = new FlatCache(new BlendAlpha(), false);
        this.blendOffset = new FlatCache(new BlendOffset(), false);
        for (int l = 0; l <= this.noiseSizeXZ; ++l) {
            int m = this.firstNoiseX + l;
            n = QuartPos.toBlock(m);
            for (o = 0; o <= this.noiseSizeXZ; ++o) {
                int p = this.firstNoiseZ + o;
                int q = QuartPos.toBlock(p);
                Blender.BlendingOutput blendingOutput = blender.blendOffsetAndFactor(n, q);
                this.blendAlpha.values[l][o] = blendingOutput.alpha();
                this.blendOffset.values[l][o] = blendingOutput.blendingOffset();
            }
        }
        NoiseRouter noiseRouter = randomState.router();
        NoiseRouter noiseRouter2 = noiseRouter.mapAll(this::wrap);
        if (!noiseGeneratorSettings.isAquifersEnabled()) {
            this.aquifer = Aquifer.createDisabled(fluidPicker);
        } else {
            n = SectionPos.blockToSectionCoord(j);
            o = SectionPos.blockToSectionCoord(k);
            this.aquifer = Aquifer.create(this, new ChunkPos(n, o), noiseRouter2, randomState.aquiferRandom(), noiseSettings.minY(), noiseSettings.height(), fluidPicker);
        }
        ImmutableList.Builder builder = ImmutableList.builder();
        DensityFunction densityFunction = DensityFunctions.cacheAllInCell(DensityFunctions.add(noiseRouter2.finalDensity(), DensityFunctions.BeardifierMarker.INSTANCE)).mapAll(this::wrap);
        builder.add(functionContext -> this.aquifer.computeSubstance(functionContext, densityFunction.compute(functionContext)));
        if (noiseGeneratorSettings.oreVeinsEnabled()) {
            builder.add(OreVeinifier.create(noiseRouter2.veinToggle(), noiseRouter2.veinRidged(), noiseRouter2.veinGap(), randomState.oreRandom()));
        }
        this.blockStateRule = new MaterialRuleList((List<BlockStateFiller>)((Object)builder.build()));
        this.initialDensityNoJaggedness = noiseRouter2.initialDensityWithoutJaggedness();
    }

    protected Climate.Sampler cachedClimateSampler(NoiseRouter noiseRouter, List<Climate.ParameterPoint> list) {
        return new Climate.Sampler(noiseRouter.temperature().mapAll(this::wrap), noiseRouter.vegetation().mapAll(this::wrap), noiseRouter.continents().mapAll(this::wrap), noiseRouter.erosion().mapAll(this::wrap), noiseRouter.depth().mapAll(this::wrap), noiseRouter.ridges().mapAll(this::wrap), list);
    }

    @Nullable
    protected BlockState getInterpolatedState() {
        return this.blockStateRule.calculate(this);
    }

    @Override
    public int blockX() {
        return this.cellStartBlockX + this.inCellX;
    }

    @Override
    public int blockY() {
        return this.cellStartBlockY + this.inCellY;
    }

    @Override
    public int blockZ() {
        return this.cellStartBlockZ + this.inCellZ;
    }

    public int preliminarySurfaceLevel(int i, int j) {
        int k = QuartPos.toBlock(QuartPos.fromBlock(i));
        int l = QuartPos.toBlock(QuartPos.fromBlock(j));
        return this.preliminarySurfaceLevel.computeIfAbsent(ColumnPos.asLong(k, l), this::computePreliminarySurfaceLevel);
    }

    private int computePreliminarySurfaceLevel(long l) {
        int i = ColumnPos.getX(l);
        int j = ColumnPos.getZ(l);
        int k = this.noiseSettings.minY();
        for (int m = k + this.noiseSettings.height(); m >= k; m -= this.cellHeight) {
            DensityFunction.SinglePointContext singlePointContext = new DensityFunction.SinglePointContext(i, m, j);
            if (!(this.initialDensityNoJaggedness.compute(singlePointContext) > 0.390625)) continue;
            return m;
        }
        return Integer.MAX_VALUE;
    }

    @Override
    public Blender getBlender() {
        return this.blender;
    }

    private void fillSlice(boolean bl, int i) {
        this.cellStartBlockX = i * this.cellWidth;
        this.inCellX = 0;
        for (int j = 0; j < this.cellCountXZ + 1; ++j) {
            int k = this.firstCellZ + j;
            this.cellStartBlockZ = k * this.cellWidth;
            this.inCellZ = 0;
            ++this.arrayInterpolationCounter;
            for (NoiseInterpolator noiseInterpolator : this.interpolators) {
                double[] ds = (bl ? noiseInterpolator.slice0 : noiseInterpolator.slice1)[j];
                noiseInterpolator.fillArray(ds, this.sliceFillingContextProvider);
            }
        }
        ++this.arrayInterpolationCounter;
    }

    public void initializeForFirstCellX() {
        if (this.interpolating) {
            throw new IllegalStateException("Staring interpolation twice");
        }
        this.interpolating = true;
        this.interpolationCounter = 0L;
        this.fillSlice(true, this.firstCellX);
    }

    public void advanceCellX(int i) {
        this.fillSlice(false, this.firstCellX + i + 1);
        this.cellStartBlockX = (this.firstCellX + i) * this.cellWidth;
    }

    @Override
    public NoiseChunk forIndex(int i) {
        int j = Math.floorMod(i, this.cellWidth);
        int k = Math.floorDiv(i, this.cellWidth);
        int l = Math.floorMod(k, this.cellWidth);
        int m = this.cellHeight - 1 - Math.floorDiv(k, this.cellWidth);
        this.inCellX = l;
        this.inCellY = m;
        this.inCellZ = j;
        this.arrayIndex = i;
        return this;
    }

    @Override
    public void fillAllDirectly(double[] ds, DensityFunction densityFunction) {
        this.arrayIndex = 0;
        for (int i = this.cellHeight - 1; i >= 0; --i) {
            this.inCellY = i;
            for (int j = 0; j < this.cellWidth; ++j) {
                this.inCellX = j;
                int k = 0;
                while (k < this.cellWidth) {
                    this.inCellZ = k++;
                    ds[this.arrayIndex++] = densityFunction.compute(this);
                }
            }
        }
    }

    public void selectCellYZ(int i, int j) {
        this.interpolators.forEach(noiseInterpolator -> noiseInterpolator.selectCellYZ(i, j));
        this.fillingCell = true;
        this.cellStartBlockY = (i + this.cellNoiseMinY) * this.cellHeight;
        this.cellStartBlockZ = (this.firstCellZ + j) * this.cellWidth;
        ++this.arrayInterpolationCounter;
        for (CacheAllInCell cacheAllInCell : this.cellCaches) {
            cacheAllInCell.noiseFiller.fillArray(cacheAllInCell.values, this);
        }
        ++this.arrayInterpolationCounter;
        this.fillingCell = false;
    }

    public void updateForY(int i, double d) {
        this.inCellY = i - this.cellStartBlockY;
        this.interpolators.forEach(noiseInterpolator -> noiseInterpolator.updateForY(d));
    }

    public void updateForX(int i, double d) {
        this.inCellX = i - this.cellStartBlockX;
        this.interpolators.forEach(noiseInterpolator -> noiseInterpolator.updateForX(d));
    }

    public void updateForZ(int i, double d) {
        this.inCellZ = i - this.cellStartBlockZ;
        ++this.interpolationCounter;
        this.interpolators.forEach(noiseInterpolator -> noiseInterpolator.updateForZ(d));
    }

    public void stopInterpolation() {
        if (!this.interpolating) {
            throw new IllegalStateException("Staring interpolation twice");
        }
        this.interpolating = false;
    }

    public void swapSlices() {
        this.interpolators.forEach(NoiseInterpolator::swapSlices);
    }

    public Aquifer aquifer() {
        return this.aquifer;
    }

    protected int cellWidth() {
        return this.cellWidth;
    }

    protected int cellHeight() {
        return this.cellHeight;
    }

    Blender.BlendingOutput getOrComputeBlendingOutput(int i, int j) {
        Blender.BlendingOutput blendingOutput;
        long l = ChunkPos.asLong(i, j);
        if (this.lastBlendingDataPos == l) {
            return this.lastBlendingOutput;
        }
        this.lastBlendingDataPos = l;
        this.lastBlendingOutput = blendingOutput = this.blender.blendOffsetAndFactor(i, j);
        return blendingOutput;
    }

    protected DensityFunction wrap(DensityFunction densityFunction) {
        return this.wrapped.computeIfAbsent(densityFunction, this::wrapNew);
    }

    private DensityFunction wrapNew(DensityFunction densityFunction) {
        if (densityFunction instanceof DensityFunctions.Marker) {
            DensityFunctions.Marker marker = (DensityFunctions.Marker)densityFunction;
            return switch (marker.type()) {
                default -> throw new MatchException(null, null);
                case DensityFunctions.Marker.Type.Interpolated -> new NoiseInterpolator(marker.wrapped());
                case DensityFunctions.Marker.Type.FlatCache -> new FlatCache(marker.wrapped(), true);
                case DensityFunctions.Marker.Type.Cache2D -> new Cache2D(marker.wrapped());
                case DensityFunctions.Marker.Type.CacheOnce -> new CacheOnce(marker.wrapped());
                case DensityFunctions.Marker.Type.CacheAllInCell -> new CacheAllInCell(marker.wrapped());
            };
        }
        if (this.blender != Blender.empty()) {
            if (densityFunction == DensityFunctions.BlendAlpha.INSTANCE) {
                return this.blendAlpha;
            }
            if (densityFunction == DensityFunctions.BlendOffset.INSTANCE) {
                return this.blendOffset;
            }
        }
        if (densityFunction == DensityFunctions.BeardifierMarker.INSTANCE) {
            return this.beardifier;
        }
        if (densityFunction instanceof DensityFunctions.HolderHolder) {
            DensityFunctions.HolderHolder holderHolder = (DensityFunctions.HolderHolder)densityFunction;
            return holderHolder.function().value();
        }
        return densityFunction;
    }

    @Override
    public /* synthetic */ DensityFunction.FunctionContext forIndex(int i) {
        return this.forIndex(i);
    }

    class FlatCache
    implements DensityFunctions.MarkerOrMarked,
    NoiseChunkDensityFunction {
        private final DensityFunction noiseFiller;
        final double[][] values;

        FlatCache(DensityFunction densityFunction, boolean bl) {
            this.noiseFiller = densityFunction;
            this.values = new double[NoiseChunk.this.noiseSizeXZ + 1][NoiseChunk.this.noiseSizeXZ + 1];
            if (bl) {
                for (int i = 0; i <= NoiseChunk.this.noiseSizeXZ; ++i) {
                    int j = NoiseChunk.this.firstNoiseX + i;
                    int k = QuartPos.toBlock(j);
                    for (int l = 0; l <= NoiseChunk.this.noiseSizeXZ; ++l) {
                        int m = NoiseChunk.this.firstNoiseZ + l;
                        int n = QuartPos.toBlock(m);
                        this.values[i][l] = densityFunction.compute(new DensityFunction.SinglePointContext(k, 0, n));
                    }
                }
            }
        }

        @Override
        public double compute(DensityFunction.FunctionContext functionContext) {
            int i = QuartPos.fromBlock(functionContext.blockX());
            int j = QuartPos.fromBlock(functionContext.blockZ());
            int k = i - NoiseChunk.this.firstNoiseX;
            int l = j - NoiseChunk.this.firstNoiseZ;
            int m = this.values.length;
            if (k >= 0 && l >= 0 && k < m && l < m) {
                return this.values[k][l];
            }
            return this.noiseFiller.compute(functionContext);
        }

        @Override
        public void fillArray(double[] ds, DensityFunction.ContextProvider contextProvider) {
            contextProvider.fillAllDirectly(ds, this);
        }

        @Override
        public DensityFunction wrapped() {
            return this.noiseFiller;
        }

        @Override
        public DensityFunctions.Marker.Type type() {
            return DensityFunctions.Marker.Type.FlatCache;
        }
    }

    class BlendAlpha
    implements NoiseChunkDensityFunction {
        BlendAlpha() {
        }

        @Override
        public DensityFunction wrapped() {
            return DensityFunctions.BlendAlpha.INSTANCE;
        }

        @Override
        public DensityFunction mapAll(DensityFunction.Visitor visitor) {
            return this.wrapped().mapAll(visitor);
        }

        @Override
        public double compute(DensityFunction.FunctionContext functionContext) {
            return NoiseChunk.this.getOrComputeBlendingOutput(functionContext.blockX(), functionContext.blockZ()).alpha();
        }

        @Override
        public void fillArray(double[] ds, DensityFunction.ContextProvider contextProvider) {
            contextProvider.fillAllDirectly(ds, this);
        }

        @Override
        public double minValue() {
            return 0.0;
        }

        @Override
        public double maxValue() {
            return 1.0;
        }

        @Override
        public KeyDispatchDataCodec<? extends DensityFunction> codec() {
            return DensityFunctions.BlendAlpha.CODEC;
        }
    }

    class BlendOffset
    implements NoiseChunkDensityFunction {
        BlendOffset() {
        }

        @Override
        public DensityFunction wrapped() {
            return DensityFunctions.BlendOffset.INSTANCE;
        }

        @Override
        public DensityFunction mapAll(DensityFunction.Visitor visitor) {
            return this.wrapped().mapAll(visitor);
        }

        @Override
        public double compute(DensityFunction.FunctionContext functionContext) {
            return NoiseChunk.this.getOrComputeBlendingOutput(functionContext.blockX(), functionContext.blockZ()).blendingOffset();
        }

        @Override
        public void fillArray(double[] ds, DensityFunction.ContextProvider contextProvider) {
            contextProvider.fillAllDirectly(ds, this);
        }

        @Override
        public double minValue() {
            return Double.NEGATIVE_INFINITY;
        }

        @Override
        public double maxValue() {
            return Double.POSITIVE_INFINITY;
        }

        @Override
        public KeyDispatchDataCodec<? extends DensityFunction> codec() {
            return DensityFunctions.BlendOffset.CODEC;
        }
    }

    @FunctionalInterface
    public static interface BlockStateFiller {
        @Nullable
        public BlockState calculate(DensityFunction.FunctionContext var1);
    }

    public class NoiseInterpolator
    implements DensityFunctions.MarkerOrMarked,
    NoiseChunkDensityFunction {
        double[][] slice0;
        double[][] slice1;
        private final DensityFunction noiseFiller;
        private double noise000;
        private double noise001;
        private double noise100;
        private double noise101;
        private double noise010;
        private double noise011;
        private double noise110;
        private double noise111;
        private double valueXZ00;
        private double valueXZ10;
        private double valueXZ01;
        private double valueXZ11;
        private double valueZ0;
        private double valueZ1;
        private double value;

        NoiseInterpolator(DensityFunction densityFunction) {
            this.noiseFiller = densityFunction;
            this.slice0 = this.allocateSlice(NoiseChunk.this.cellCountY, NoiseChunk.this.cellCountXZ);
            this.slice1 = this.allocateSlice(NoiseChunk.this.cellCountY, NoiseChunk.this.cellCountXZ);
            NoiseChunk.this.interpolators.add(this);
        }

        private double[][] allocateSlice(int i, int j) {
            int k = j + 1;
            int l = i + 1;
            double[][] ds = new double[k][l];
            for (int m = 0; m < k; ++m) {
                ds[m] = new double[l];
            }
            return ds;
        }

        void selectCellYZ(int i, int j) {
            this.noise000 = this.slice0[j][i];
            this.noise001 = this.slice0[j + 1][i];
            this.noise100 = this.slice1[j][i];
            this.noise101 = this.slice1[j + 1][i];
            this.noise010 = this.slice0[j][i + 1];
            this.noise011 = this.slice0[j + 1][i + 1];
            this.noise110 = this.slice1[j][i + 1];
            this.noise111 = this.slice1[j + 1][i + 1];
        }

        void updateForY(double d) {
            this.valueXZ00 = Mth.lerp(d, this.noise000, this.noise010);
            this.valueXZ10 = Mth.lerp(d, this.noise100, this.noise110);
            this.valueXZ01 = Mth.lerp(d, this.noise001, this.noise011);
            this.valueXZ11 = Mth.lerp(d, this.noise101, this.noise111);
        }

        void updateForX(double d) {
            this.valueZ0 = Mth.lerp(d, this.valueXZ00, this.valueXZ10);
            this.valueZ1 = Mth.lerp(d, this.valueXZ01, this.valueXZ11);
        }

        void updateForZ(double d) {
            this.value = Mth.lerp(d, this.valueZ0, this.valueZ1);
        }

        @Override
        public double compute(DensityFunction.FunctionContext functionContext) {
            if (functionContext != NoiseChunk.this) {
                return this.noiseFiller.compute(functionContext);
            }
            if (!NoiseChunk.this.interpolating) {
                throw new IllegalStateException("Trying to sample interpolator outside the interpolation loop");
            }
            if (NoiseChunk.this.fillingCell) {
                return Mth.lerp3((double)NoiseChunk.this.inCellX / (double)NoiseChunk.this.cellWidth, (double)NoiseChunk.this.inCellY / (double)NoiseChunk.this.cellHeight, (double)NoiseChunk.this.inCellZ / (double)NoiseChunk.this.cellWidth, this.noise000, this.noise100, this.noise010, this.noise110, this.noise001, this.noise101, this.noise011, this.noise111);
            }
            return this.value;
        }

        @Override
        public void fillArray(double[] ds, DensityFunction.ContextProvider contextProvider) {
            if (NoiseChunk.this.fillingCell) {
                contextProvider.fillAllDirectly(ds, this);
                return;
            }
            this.wrapped().fillArray(ds, contextProvider);
        }

        @Override
        public DensityFunction wrapped() {
            return this.noiseFiller;
        }

        private void swapSlices() {
            double[][] ds = this.slice0;
            this.slice0 = this.slice1;
            this.slice1 = ds;
        }

        @Override
        public DensityFunctions.Marker.Type type() {
            return DensityFunctions.Marker.Type.Interpolated;
        }
    }

    class CacheAllInCell
    implements DensityFunctions.MarkerOrMarked,
    NoiseChunkDensityFunction {
        final DensityFunction noiseFiller;
        final double[] values;

        CacheAllInCell(DensityFunction densityFunction) {
            this.noiseFiller = densityFunction;
            this.values = new double[NoiseChunk.this.cellWidth * NoiseChunk.this.cellWidth * NoiseChunk.this.cellHeight];
            NoiseChunk.this.cellCaches.add(this);
        }

        @Override
        public double compute(DensityFunction.FunctionContext functionContext) {
            if (functionContext != NoiseChunk.this) {
                return this.noiseFiller.compute(functionContext);
            }
            if (!NoiseChunk.this.interpolating) {
                throw new IllegalStateException("Trying to sample interpolator outside the interpolation loop");
            }
            int i = NoiseChunk.this.inCellX;
            int j = NoiseChunk.this.inCellY;
            int k = NoiseChunk.this.inCellZ;
            if (i >= 0 && j >= 0 && k >= 0 && i < NoiseChunk.this.cellWidth && j < NoiseChunk.this.cellHeight && k < NoiseChunk.this.cellWidth) {
                return this.values[((NoiseChunk.this.cellHeight - 1 - j) * NoiseChunk.this.cellWidth + i) * NoiseChunk.this.cellWidth + k];
            }
            return this.noiseFiller.compute(functionContext);
        }

        @Override
        public void fillArray(double[] ds, DensityFunction.ContextProvider contextProvider) {
            contextProvider.fillAllDirectly(ds, this);
        }

        @Override
        public DensityFunction wrapped() {
            return this.noiseFiller;
        }

        @Override
        public DensityFunctions.Marker.Type type() {
            return DensityFunctions.Marker.Type.CacheAllInCell;
        }
    }

    static class Cache2D
    implements DensityFunctions.MarkerOrMarked,
    NoiseChunkDensityFunction {
        private final DensityFunction function;
        private long lastPos2D = ChunkPos.INVALID_CHUNK_POS;
        private double lastValue;

        Cache2D(DensityFunction densityFunction) {
            this.function = densityFunction;
        }

        @Override
        public double compute(DensityFunction.FunctionContext functionContext) {
            double d;
            int j;
            int i = functionContext.blockX();
            long l = ChunkPos.asLong(i, j = functionContext.blockZ());
            if (this.lastPos2D == l) {
                return this.lastValue;
            }
            this.lastPos2D = l;
            this.lastValue = d = this.function.compute(functionContext);
            return d;
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
            return DensityFunctions.Marker.Type.Cache2D;
        }
    }

    class CacheOnce
    implements DensityFunctions.MarkerOrMarked,
    NoiseChunkDensityFunction {
        private final DensityFunction function;
        private long lastCounter;
        private long lastArrayCounter;
        private double lastValue;
        @Nullable
        private double[] lastArray;

        CacheOnce(DensityFunction densityFunction) {
            this.function = densityFunction;
        }

        @Override
        public double compute(DensityFunction.FunctionContext functionContext) {
            double d;
            if (functionContext != NoiseChunk.this) {
                return this.function.compute(functionContext);
            }
            if (this.lastArray != null && this.lastArrayCounter == NoiseChunk.this.arrayInterpolationCounter) {
                return this.lastArray[NoiseChunk.this.arrayIndex];
            }
            if (this.lastCounter == NoiseChunk.this.interpolationCounter) {
                return this.lastValue;
            }
            this.lastCounter = NoiseChunk.this.interpolationCounter;
            this.lastValue = d = this.function.compute(functionContext);
            return d;
        }

        @Override
        public void fillArray(double[] ds, DensityFunction.ContextProvider contextProvider) {
            if (this.lastArray != null && this.lastArrayCounter == NoiseChunk.this.arrayInterpolationCounter) {
                System.arraycopy(this.lastArray, 0, ds, 0, ds.length);
                return;
            }
            this.wrapped().fillArray(ds, contextProvider);
            if (this.lastArray != null && this.lastArray.length == ds.length) {
                System.arraycopy(ds, 0, this.lastArray, 0, ds.length);
            } else {
                this.lastArray = (double[])ds.clone();
            }
            this.lastArrayCounter = NoiseChunk.this.arrayInterpolationCounter;
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

    static interface NoiseChunkDensityFunction
    extends DensityFunction {
        public DensityFunction wrapped();

        @Override
        default public double minValue() {
            return this.wrapped().minValue();
        }

        @Override
        default public double maxValue() {
            return this.wrapped().maxValue();
        }
    }
}
```

```
package net.minecraft.world.level.levelgen;

public interface DensityFunction {
    public static final Codec<DensityFunction> DIRECT_CODEC = DensityFunctions.DIRECT_CODEC;
    public static final Codec<Holder<DensityFunction>> CODEC = RegistryFileCodec.create(Registries.DENSITY_FUNCTION, DIRECT_CODEC);
    public static final Codec<DensityFunction> HOLDER_HELPER_CODEC = CODEC.xmap(DensityFunctions.HolderHolder::new, densityFunction -> {
        if (densityFunction instanceof DensityFunctions.HolderHolder) {
            DensityFunctions.HolderHolder holderHolder = (DensityFunctions.HolderHolder)densityFunction;
            return holderHolder.function();
        }
        return new Holder.Direct<DensityFunction>((DensityFunction)densityFunction);
    });

    public double compute(FunctionContext var1);

    public void fillArray(double[] var1, ContextProvider var2);

    public DensityFunction mapAll(Visitor var1);

    public double minValue();

    public double maxValue();

    public KeyDispatchDataCodec<? extends DensityFunction> codec();

    default public DensityFunction clamp(double d, double e) {
        return new DensityFunctions.Clamp(this, d, e);
    }

    default public DensityFunction abs() {
        return DensityFunctions.map(this, DensityFunctions.Mapped.Type.ABS);
    }

    default public DensityFunction square() {
        return DensityFunctions.map(this, DensityFunctions.Mapped.Type.SQUARE);
    }

    default public DensityFunction cube() {
        return DensityFunctions.map(this, DensityFunctions.Mapped.Type.CUBE);
    }

    default public DensityFunction halfNegative() {
        return DensityFunctions.map(this, DensityFunctions.Mapped.Type.HALF_NEGATIVE);
    }

    default public DensityFunction quarterNegative() {
        return DensityFunctions.map(this, DensityFunctions.Mapped.Type.QUARTER_NEGATIVE);
    }

    default public DensityFunction squeeze() {
        return DensityFunctions.map(this, DensityFunctions.Mapped.Type.SQUEEZE);
    }

    public record SinglePointContext(int blockX, int blockY, int blockZ) implements FunctionContext
    {
    }

    public static interface FunctionContext {
        public int blockX();

        public int blockY();

        public int blockZ();

        default public Blender getBlender() {
            return Blender.empty();
        }
    }

    public static interface SimpleFunction
    extends DensityFunction {
        @Override
        default public void fillArray(double[] ds, ContextProvider contextProvider) {
            contextProvider.fillAllDirectly(ds, this);
        }

        @Override
        default public DensityFunction mapAll(Visitor visitor) {
            return visitor.apply(this);
        }
    }

    public static interface Visitor {
        public DensityFunction apply(DensityFunction var1);

        default public NoiseHolder visitNoise(NoiseHolder noiseHolder) {
            return noiseHolder;
        }
    }

    public record NoiseHolder(Holder<NormalNoise.NoiseParameters> noiseData, @Nullable NormalNoise noise) {
        public static final Codec<NoiseHolder> CODEC = NormalNoise.NoiseParameters.CODEC.xmap(holder -> new NoiseHolder((Holder<NormalNoise.NoiseParameters>)holder, null), NoiseHolder::noiseData);

        public NoiseHolder(Holder<NormalNoise.NoiseParameters> holder) {
            this(holder, null);
        }

        public double getValue(double d, double e, double f) {
            return this.noise == null ? 0.0 : this.noise.getValue(d, e, f);
        }

        public double maxValue() {
            return this.noise == null ? 2.0 : this.noise.maxValue();
        }

        @Nullable
        public NormalNoise noise() {
            return this.noise;
        }
    }

    public static interface ContextProvider {
        public FunctionContext forIndex(int var1);

        public void fillAllDirectly(double[] var1, DensityFunction var2);
    }
}
```

```
package net.minecraft.world.level.levelgen;

public final class DensityFunctions {
    private static final Codec<DensityFunction> CODEC = BuiltInRegistries.DENSITY_FUNCTION_TYPE.byNameCodec().dispatch(densityFunction -> densityFunction.codec().codec(), Function.identity());
    protected static final double MAX_REASONABLE_NOISE_VALUE = 1000000.0;
    static final Codec<Double> NOISE_VALUE_CODEC = Codec.doubleRange(-1000000.0, 1000000.0);
    public static final Codec<DensityFunction> DIRECT_CODEC = Codec.either(NOISE_VALUE_CODEC, CODEC).xmap(either -> either.map(DensityFunctions::constant, Function.identity()), densityFunction -> {
        if (densityFunction instanceof Constant) {
            Constant constant = (Constant)densityFunction;
            return Either.left(constant.value());
        }
        return Either.right(densityFunction);
    });

    public static MapCodec<? extends DensityFunction> bootstrap(Registry<MapCodec<? extends DensityFunction>> registry) {
        DensityFunctions.register(registry, "blend_alpha", BlendAlpha.CODEC);
        DensityFunctions.register(registry, "blend_offset", BlendOffset.CODEC);
        DensityFunctions.register(registry, "beardifier", BeardifierMarker.CODEC);
        DensityFunctions.register(registry, "old_blended_noise", BlendedNoise.CODEC);
        for (Marker.Type type : Marker.Type.values()) {
            DensityFunctions.register(registry, type.getSerializedName(), type.codec);
        }
        DensityFunctions.register(registry, "noise", Noise.CODEC);
        DensityFunctions.register(registry, "end_islands", EndIslandDensityFunction.CODEC);
        DensityFunctions.register(registry, "weird_scaled_sampler", WeirdScaledSampler.CODEC);
        DensityFunctions.register(registry, "shifted_noise", ShiftedNoise.CODEC);
        DensityFunctions.register(registry, "range_choice", RangeChoice.CODEC);
        DensityFunctions.register(registry, "shift_a", ShiftA.CODEC);
        DensityFunctions.register(registry, "shift_b", ShiftB.CODEC);
        DensityFunctions.register(registry, "shift", Shift.CODEC);
        DensityFunctions.register(registry, "blend_density", BlendDensity.CODEC);
        DensityFunctions.register(registry, "clamp", Clamp.CODEC);
        for (Enum enum_ : Mapped.Type.values()) {
            DensityFunctions.register(registry, ((Mapped.Type)enum_).getSerializedName(), ((Mapped.Type)enum_).codec);
        }
        for (Enum enum_ : TwoArgumentSimpleFunction.Type.values()) {
            DensityFunctions.register(registry, ((TwoArgumentSimpleFunction.Type)enum_).getSerializedName(), ((TwoArgumentSimpleFunction.Type)enum_).codec);
        }
        DensityFunctions.register(registry, "spline", Spline.CODEC);
        DensityFunctions.register(registry, "constant", Constant.CODEC);
        return DensityFunctions.register(registry, "y_clamped_gradient", YClampedGradient.CODEC);
    }

    private static MapCodec<? extends DensityFunction> register(Registry<MapCodec<? extends DensityFunction>> registry, String string, KeyDispatchDataCodec<? extends DensityFunction> keyDispatchDataCodec) {
        return Registry.register(registry, string, keyDispatchDataCodec.codec());
    }

    static <A, O> KeyDispatchDataCodec<O> singleArgumentCodec(Codec<A> codec, Function<A, O> function, Function<O, A> function2) {
        return KeyDispatchDataCodec.of(((MapCodec)codec.fieldOf("argument")).xmap(function, function2));
    }

    static <O> KeyDispatchDataCodec<O> singleFunctionArgumentCodec(Function<DensityFunction, O> function, Function<O, DensityFunction> function2) {
        return DensityFunctions.singleArgumentCodec(DensityFunction.HOLDER_HELPER_CODEC, function, function2);
    }

    static <O> KeyDispatchDataCodec<O> doubleFunctionArgumentCodec(BiFunction<DensityFunction, DensityFunction, O> biFunction, Function<O, DensityFunction> function, Function<O, DensityFunction> function2) {
        return KeyDispatchDataCodec.of(RecordCodecBuilder.mapCodec(instance -> instance.group(((MapCodec)DensityFunction.HOLDER_HELPER_CODEC.fieldOf("argument1")).forGetter(function), ((MapCodec)DensityFunction.HOLDER_HELPER_CODEC.fieldOf("argument2")).forGetter(function2)).apply(instance, biFunction)));
    }

    static <O> KeyDispatchDataCodec<O> makeCodec(MapCodec<O> mapCodec) {
        return KeyDispatchDataCodec.of(mapCodec);
    }

    private DensityFunctions() {
    }

    public static DensityFunction interpolated(DensityFunction densityFunction) {
        return new Marker(Marker.Type.Interpolated, densityFunction);
    }

    public static DensityFunction flatCache(DensityFunction densityFunction) {
        return new Marker(Marker.Type.FlatCache, densityFunction);
    }

    public static DensityFunction cache2d(DensityFunction densityFunction) {
        return new Marker(Marker.Type.Cache2D, densityFunction);
    }

    public static DensityFunction cacheOnce(DensityFunction densityFunction) {
        return new Marker(Marker.Type.CacheOnce, densityFunction);
    }

    public static DensityFunction cacheAllInCell(DensityFunction densityFunction) {
        return new Marker(Marker.Type.CacheAllInCell, densityFunction);
    }

    public static DensityFunction mappedNoise(Holder<NormalNoise.NoiseParameters> holder, @Deprecated double d, double e, double f, double g) {
        return DensityFunctions.mapFromUnitTo(new Noise(new DensityFunction.NoiseHolder(holder), d, e), f, g);
    }

    public static DensityFunction mappedNoise(Holder<NormalNoise.NoiseParameters> holder, double d, double e, double f) {
        return DensityFunctions.mappedNoise(holder, 1.0, d, e, f);
    }

    public static DensityFunction mappedNoise(Holder<NormalNoise.NoiseParameters> holder, double d, double e) {
        return DensityFunctions.mappedNoise(holder, 1.0, 1.0, d, e);
    }

    public static DensityFunction shiftedNoise2d(DensityFunction densityFunction, DensityFunction densityFunction2, double d, Holder<NormalNoise.NoiseParameters> holder) {
        return new ShiftedNoise(densityFunction, DensityFunctions.zero(), densityFunction2, d, 0.0, new DensityFunction.NoiseHolder(holder));
    }

    public static DensityFunction noise(Holder<NormalNoise.NoiseParameters> holder) {
        return DensityFunctions.noise(holder, 1.0, 1.0);
    }

    public static DensityFunction noise(Holder<NormalNoise.NoiseParameters> holder, double d, double e) {
        return new Noise(new DensityFunction.NoiseHolder(holder), d, e);
    }

    public static DensityFunction noise(Holder<NormalNoise.NoiseParameters> holder, double d) {
        return DensityFunctions.noise(holder, 1.0, d);
    }

    public static DensityFunction rangeChoice(DensityFunction densityFunction, double d, double e, DensityFunction densityFunction2, DensityFunction densityFunction3) {
        return new RangeChoice(densityFunction, d, e, densityFunction2, densityFunction3);
    }

    public static DensityFunction shiftA(Holder<NormalNoise.NoiseParameters> holder) {
        return new ShiftA(new DensityFunction.NoiseHolder(holder));
    }

    public static DensityFunction shiftB(Holder<NormalNoise.NoiseParameters> holder) {
        return new ShiftB(new DensityFunction.NoiseHolder(holder));
    }

    public static DensityFunction shift(Holder<NormalNoise.NoiseParameters> holder) {
        return new Shift(new DensityFunction.NoiseHolder(holder));
    }

    public static DensityFunction blendDensity(DensityFunction densityFunction) {
        return new BlendDensity(densityFunction);
    }

    public static DensityFunction endIslands(long l) {
        return new EndIslandDensityFunction(l);
    }

    public static DensityFunction weirdScaledSampler(DensityFunction densityFunction, Holder<NormalNoise.NoiseParameters> holder, WeirdScaledSampler.RarityValueMapper rarityValueMapper) {
        return new WeirdScaledSampler(densityFunction, new DensityFunction.NoiseHolder(holder), rarityValueMapper);
    }

    public static DensityFunction add(DensityFunction densityFunction, DensityFunction densityFunction2) {
        return TwoArgumentSimpleFunction.create(TwoArgumentSimpleFunction.Type.ADD, densityFunction, densityFunction2);
    }

    public static DensityFunction mul(DensityFunction densityFunction, DensityFunction densityFunction2) {
        return TwoArgumentSimpleFunction.create(TwoArgumentSimpleFunction.Type.MUL, densityFunction, densityFunction2);
    }

    public static DensityFunction min(DensityFunction densityFunction, DensityFunction densityFunction2) {
        return TwoArgumentSimpleFunction.create(TwoArgumentSimpleFunction.Type.MIN, densityFunction, densityFunction2);
    }

    public static DensityFunction max(DensityFunction densityFunction, DensityFunction densityFunction2) {
        return TwoArgumentSimpleFunction.create(TwoArgumentSimpleFunction.Type.MAX, densityFunction, densityFunction2);
    }

    public static DensityFunction spline(CubicSpline<Spline.Point, Spline.Coordinate> cubicSpline) {
        return new Spline(cubicSpline);
    }

    public static DensityFunction zero() {
        return Constant.ZERO;
    }

    public static DensityFunction constant(double d) {
        return new Constant(d);
    }

    public static DensityFunction yClampedGradient(int i, int j, double d, double e) {
        return new YClampedGradient(i, j, d, e);
    }

    public static DensityFunction map(DensityFunction densityFunction, Mapped.Type type) {
        return Mapped.create(type, densityFunction);
    }

    private static DensityFunction mapFromUnitTo(DensityFunction densityFunction, double d, double e) {
        double f = (d + e) * 0.5;
        double g = (e - d) * 0.5;
        return DensityFunctions.add(DensityFunctions.constant(f), DensityFunctions.mul(DensityFunctions.constant(g), densityFunction));
    }

    public static DensityFunction blendAlpha() {
        return BlendAlpha.INSTANCE;
    }

    public static DensityFunction blendOffset() {
        return BlendOffset.INSTANCE;
    }

    public static DensityFunction lerp(DensityFunction densityFunction, DensityFunction densityFunction2, DensityFunction densityFunction3) {
        if (densityFunction2 instanceof Constant) {
            Constant constant = (Constant)densityFunction2;
            return DensityFunctions.lerp(densityFunction, constant.value, densityFunction3);
        }
        DensityFunction densityFunction4 = DensityFunctions.cacheOnce(densityFunction);
        DensityFunction densityFunction5 = DensityFunctions.add(DensityFunctions.mul(densityFunction4, DensityFunctions.constant(-1.0)), DensityFunctions.constant(1.0));
        return DensityFunctions.add(DensityFunctions.mul(densityFunction2, densityFunction5), DensityFunctions.mul(densityFunction3, densityFunction4));
    }

    public static DensityFunction lerp(DensityFunction densityFunction, double d, DensityFunction densityFunction2) {
        return DensityFunctions.add(DensityFunctions.mul(densityFunction, DensityFunctions.add(densityFunction2, DensityFunctions.constant(-d))), DensityFunctions.constant(d));
    }

    protected static enum BlendAlpha implements DensityFunction.SimpleFunction
    {
        INSTANCE;

        public static final KeyDispatchDataCodec<DensityFunction> CODEC;

        @Override
        public double compute(DensityFunction.FunctionContext functionContext) {
            return 1.0;
        }

        @Override
        public void fillArray(double[] ds, DensityFunction.ContextProvider contextProvider) {
            Arrays.fill(ds, 1.0);
        }

        @Override
        public double minValue() {
            return 1.0;
        }

        @Override
        public double maxValue() {
            return 1.0;
        }

        @Override
        public KeyDispatchDataCodec<? extends DensityFunction> codec() {
            return CODEC;
        }

        static {
            CODEC = KeyDispatchDataCodec.of(MapCodec.unit(INSTANCE));
        }
    }

    protected static enum BlendOffset implements DensityFunction.SimpleFunction
    {
        INSTANCE;

        public static final KeyDispatchDataCodec<DensityFunction> CODEC;

        @Override
        public double compute(DensityFunction.FunctionContext functionContext) {
            return 0.0;
        }

        @Override
        public void fillArray(double[] ds, DensityFunction.ContextProvider contextProvider) {
            Arrays.fill(ds, 0.0);
        }

        @Override
        public double minValue() {
            return 0.0;
        }

        @Override
        public double maxValue() {
            return 0.0;
        }

        @Override
        public KeyDispatchDataCodec<? extends DensityFunction> codec() {
            return CODEC;
        }

        static {
            CODEC = KeyDispatchDataCodec.of(MapCodec.unit(INSTANCE));
        }
    }

    protected static enum BeardifierMarker implements BeardifierOrMarker
    {
        INSTANCE;


        @Override
        public double compute(DensityFunction.FunctionContext functionContext) {
            return 0.0;
        }

        @Override
        public void fillArray(double[] ds, DensityFunction.ContextProvider contextProvider) {
            Arrays.fill(ds, 0.0);
        }

        @Override
        public double minValue() {
            return 0.0;
        }

        @Override
        public double maxValue() {
            return 0.0;
        }
    }

    protected record Marker(Type type, DensityFunction wrapped) implements MarkerOrMarked
    {
        @Override
        public double compute(DensityFunction.FunctionContext functionContext) {
            return this.wrapped.compute(functionContext);
        }

        @Override
        public void fillArray(double[] ds, DensityFunction.ContextProvider contextProvider) {
            this.wrapped.fillArray(ds, contextProvider);
        }

        @Override
        public double minValue() {
            return this.wrapped.minValue();
        }

        @Override
        public double maxValue() {
            return this.wrapped.maxValue();
        }

        static enum Type implements StringRepresentable
        {
            Interpolated("interpolated"),
            FlatCache("flat_cache"),
            Cache2D("cache_2d"),
            CacheOnce("cache_once"),
            CacheAllInCell("cache_all_in_cell");

            private final String name;
            final KeyDispatchDataCodec<MarkerOrMarked> codec = DensityFunctions.singleFunctionArgumentCodec(densityFunction -> new Marker(this, (DensityFunction)densityFunction), MarkerOrMarked::wrapped);

            private Type(String string2) {
                this.name = string2;
            }

            @Override
            public String getSerializedName() {
                return this.name;
            }
        }
    }

    protected record Noise(DensityFunction.NoiseHolder noise, @Deprecated double xzScale, double yScale) implements DensityFunction
    {
        public static final MapCodec<Noise> DATA_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(((MapCodec)DensityFunction.NoiseHolder.CODEC.fieldOf("noise")).forGetter(Noise::noise), ((MapCodec)Codec.DOUBLE.fieldOf("xz_scale")).forGetter(Noise::xzScale), ((MapCodec)Codec.DOUBLE.fieldOf("y_scale")).forGetter(Noise::yScale)).apply((Applicative<Noise, ?>)instance, Noise::new));
        public static final KeyDispatchDataCodec<Noise> CODEC = DensityFunctions.makeCodec(DATA_CODEC);

        @Override
        public double compute(DensityFunction.FunctionContext functionContext) {
            return this.noise.getValue((double)functionContext.blockX() * this.xzScale, (double)functionContext.blockY() * this.yScale, (double)functionContext.blockZ() * this.xzScale);
        }

        @Override
        public void fillArray(double[] ds, DensityFunction.ContextProvider contextProvider) {
            contextProvider.fillAllDirectly(ds, this);
        }

        @Override
        public DensityFunction mapAll(DensityFunction.Visitor visitor) {
            return visitor.apply(new Noise(visitor.visitNoise(this.noise), this.xzScale, this.yScale));
        }

        @Override
        public double minValue() {
            return -this.maxValue();
        }

        @Override
        public double maxValue() {
            return this.noise.maxValue();
        }

        @Override
        public KeyDispatchDataCodec<? extends DensityFunction> codec() {
            return CODEC;
        }

        @Deprecated
        public double xzScale() {
            return this.xzScale;
        }
    }

    protected static final class EndIslandDensityFunction
    implements DensityFunction.SimpleFunction {
        public static final KeyDispatchDataCodec<EndIslandDensityFunction> CODEC = KeyDispatchDataCodec.of(MapCodec.unit(new EndIslandDensityFunction(0L)));
        private static final float ISLAND_THRESHOLD = -0.9f;
        private final SimplexNoise islandNoise;

        public EndIslandDensityFunction(long l) {
            LegacyRandomSource randomSource = new LegacyRandomSource(l);
            randomSource.consumeCount(17292);
            this.islandNoise = new SimplexNoise(randomSource);
        }

        private static float getHeightValue(SimplexNoise simplexNoise, int i, int j) {
            int k = i / 2;
            int l = j / 2;
            int m = i % 2;
            int n = j % 2;
            float f = 100.0f - Mth.sqrt(i * i + j * j) * 8.0f;
            f = Mth.clamp(f, -100.0f, 80.0f);
            for (int o = -12; o <= 12; ++o) {
                for (int p = -12; p <= 12; ++p) {
                    long q = k + o;
                    long r = l + p;
                    if (q * q + r * r <= 4096L || !(simplexNoise.getValue(q, r) < (double)-0.9f)) continue;
                    float g = (Mth.abs(q) * 3439.0f + Mth.abs(r) * 147.0f) % 13.0f + 9.0f;
                    float h = m - o * 2;
                    float s = n - p * 2;
                    float t = 100.0f - Mth.sqrt(h * h + s * s) * g;
                    t = Mth.clamp(t, -100.0f, 80.0f);
                    f = Math.max(f, t);
                }
            }
            return f;
        }

        @Override
        public double compute(DensityFunction.FunctionContext functionContext) {
            return ((double)EndIslandDensityFunction.getHeightValue(this.islandNoise, functionContext.blockX() / 8, functionContext.blockZ() / 8) - 8.0) / 128.0;
        }

        @Override
        public double minValue() {
            return -0.84375;
        }

        @Override
        public double maxValue() {
            return 0.5625;
        }

        @Override
        public KeyDispatchDataCodec<? extends DensityFunction> codec() {
            return CODEC;
        }
    }

    protected record WeirdScaledSampler(DensityFunction input, DensityFunction.NoiseHolder noise, RarityValueMapper rarityValueMapper) implements TransformerWithContext
    {
        private static final MapCodec<WeirdScaledSampler> DATA_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(((MapCodec)DensityFunction.HOLDER_HELPER_CODEC.fieldOf("input")).forGetter(WeirdScaledSampler::input), ((MapCodec)DensityFunction.NoiseHolder.CODEC.fieldOf("noise")).forGetter(WeirdScaledSampler::noise), ((MapCodec)RarityValueMapper.CODEC.fieldOf("rarity_value_mapper")).forGetter(WeirdScaledSampler::rarityValueMapper)).apply((Applicative<WeirdScaledSampler, ?>)instance, WeirdScaledSampler::new));
        public static final KeyDispatchDataCodec<WeirdScaledSampler> CODEC = DensityFunctions.makeCodec(DATA_CODEC);

        @Override
        public double transform(DensityFunction.FunctionContext functionContext, double d) {
            double e = this.rarityValueMapper.mapper.get(d);
            return e * Math.abs(this.noise.getValue((double)functionContext.blockX() / e, (double)functionContext.blockY() / e, (double)functionContext.blockZ() / e));
        }

        @Override
        public DensityFunction mapAll(DensityFunction.Visitor visitor) {
            return visitor.apply(new WeirdScaledSampler(this.input.mapAll(visitor), visitor.visitNoise(this.noise), this.rarityValueMapper));
        }

        @Override
        public double minValue() {
            return 0.0;
        }

        @Override
        public double maxValue() {
            return this.rarityValueMapper.maxRarity * this.noise.maxValue();
        }

        @Override
        public KeyDispatchDataCodec<? extends DensityFunction> codec() {
            return CODEC;
        }

        public static enum RarityValueMapper implements StringRepresentable
        {
            TYPE1("type_1", NoiseRouterData.QuantizedSpaghettiRarity::getSpaghettiRarity3D, 2.0),
            TYPE2("type_2", NoiseRouterData.QuantizedSpaghettiRarity::getSphaghettiRarity2D, 3.0);

            public static final Codec<RarityValueMapper> CODEC;
            private final String name;
            final Double2DoubleFunction mapper;
            final double maxRarity;

            private RarityValueMapper(String string2, Double2DoubleFunction double2DoubleFunction, double d) {
                this.name = string2;
                this.mapper = double2DoubleFunction;
                this.maxRarity = d;
            }

            @Override
            public String getSerializedName() {
                return this.name;
            }

            static {
                CODEC = StringRepresentable.fromEnum(RarityValueMapper::values);
            }
        }
    }

    protected record ShiftedNoise(DensityFunction shiftX, DensityFunction shiftY, DensityFunction shiftZ, double xzScale, double yScale, DensityFunction.NoiseHolder noise) implements DensityFunction
    {
        private static final MapCodec<ShiftedNoise> DATA_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(((MapCodec)DensityFunction.HOLDER_HELPER_CODEC.fieldOf("shift_x")).forGetter(ShiftedNoise::shiftX), ((MapCodec)DensityFunction.HOLDER_HELPER_CODEC.fieldOf("shift_y")).forGetter(ShiftedNoise::shiftY), ((MapCodec)DensityFunction.HOLDER_HELPER_CODEC.fieldOf("shift_z")).forGetter(ShiftedNoise::shiftZ), ((MapCodec)Codec.DOUBLE.fieldOf("xz_scale")).forGetter(ShiftedNoise::xzScale), ((MapCodec)Codec.DOUBLE.fieldOf("y_scale")).forGetter(ShiftedNoise::yScale), ((MapCodec)DensityFunction.NoiseHolder.CODEC.fieldOf("noise")).forGetter(ShiftedNoise::noise)).apply((Applicative<ShiftedNoise, ?>)instance, ShiftedNoise::new));
        public static final KeyDispatchDataCodec<ShiftedNoise> CODEC = DensityFunctions.makeCodec(DATA_CODEC);

        @Override
        public double compute(DensityFunction.FunctionContext functionContext) {
            double d = (double)functionContext.blockX() * this.xzScale + this.shiftX.compute(functionContext);
            double e = (double)functionContext.blockY() * this.yScale + this.shiftY.compute(functionContext);
            double f = (double)functionContext.blockZ() * this.xzScale + this.shiftZ.compute(functionContext);
            return this.noise.getValue(d, e, f);
        }

        @Override
        public void fillArray(double[] ds, DensityFunction.ContextProvider contextProvider) {
            contextProvider.fillAllDirectly(ds, this);
        }

        @Override
        public DensityFunction mapAll(DensityFunction.Visitor visitor) {
            return visitor.apply(new ShiftedNoise(this.shiftX.mapAll(visitor), this.shiftY.mapAll(visitor), this.shiftZ.mapAll(visitor), this.xzScale, this.yScale, visitor.visitNoise(this.noise)));
        }

        @Override
        public double minValue() {
            return -this.maxValue();
        }

        @Override
        public double maxValue() {
            return this.noise.maxValue();
        }

        @Override
        public KeyDispatchDataCodec<? extends DensityFunction> codec() {
            return CODEC;
        }
    }

    record RangeChoice(DensityFunction input, double minInclusive, double maxExclusive, DensityFunction whenInRange, DensityFunction whenOutOfRange) implements DensityFunction
    {
        public static final MapCodec<RangeChoice> DATA_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(((MapCodec)DensityFunction.HOLDER_HELPER_CODEC.fieldOf("input")).forGetter(RangeChoice::input), ((MapCodec)NOISE_VALUE_CODEC.fieldOf("min_inclusive")).forGetter(RangeChoice::minInclusive), ((MapCodec)NOISE_VALUE_CODEC.fieldOf("max_exclusive")).forGetter(RangeChoice::maxExclusive), ((MapCodec)DensityFunction.HOLDER_HELPER_CODEC.fieldOf("when_in_range")).forGetter(RangeChoice::whenInRange), ((MapCodec)DensityFunction.HOLDER_HELPER_CODEC.fieldOf("when_out_of_range")).forGetter(RangeChoice::whenOutOfRange)).apply((Applicative<RangeChoice, ?>)instance, RangeChoice::new));
        public static final KeyDispatchDataCodec<RangeChoice> CODEC = DensityFunctions.makeCodec(DATA_CODEC);

        @Override
        public double compute(DensityFunction.FunctionContext functionContext) {
            double d = this.input.compute(functionContext);
            if (d >= this.minInclusive && d < this.maxExclusive) {
                return this.whenInRange.compute(functionContext);
            }
            return this.whenOutOfRange.compute(functionContext);
        }

        @Override
        public void fillArray(double[] ds, DensityFunction.ContextProvider contextProvider) {
            this.input.fillArray(ds, contextProvider);
            for (int i = 0; i < ds.length; ++i) {
                double d = ds[i];
                ds[i] = d >= this.minInclusive && d < this.maxExclusive ? this.whenInRange.compute(contextProvider.forIndex(i)) : this.whenOutOfRange.compute(contextProvider.forIndex(i));
            }
        }

        @Override
        public DensityFunction mapAll(DensityFunction.Visitor visitor) {
            return visitor.apply(new RangeChoice(this.input.mapAll(visitor), this.minInclusive, this.maxExclusive, this.whenInRange.mapAll(visitor), this.whenOutOfRange.mapAll(visitor)));
        }

        @Override
        public double minValue() {
            return Math.min(this.whenInRange.minValue(), this.whenOutOfRange.minValue());
        }

        @Override
        public double maxValue() {
            return Math.max(this.whenInRange.maxValue(), this.whenOutOfRange.maxValue());
        }

        @Override
        public KeyDispatchDataCodec<? extends DensityFunction> codec() {
            return CODEC;
        }
    }

    protected record ShiftA(DensityFunction.NoiseHolder offsetNoise) implements ShiftNoise
    {
        static final KeyDispatchDataCodec<ShiftA> CODEC = DensityFunctions.singleArgumentCodec(DensityFunction.NoiseHolder.CODEC, ShiftA::new, ShiftA::offsetNoise);

        @Override
        public double compute(DensityFunction.FunctionContext functionContext) {
            return this.compute(functionContext.blockX(), 0.0, functionContext.blockZ());
        }

        @Override
        public DensityFunction mapAll(DensityFunction.Visitor visitor) {
            return visitor.apply(new ShiftA(visitor.visitNoise(this.offsetNoise)));
        }

        @Override
        public KeyDispatchDataCodec<? extends DensityFunction> codec() {
            return CODEC;
        }
    }

    protected record ShiftB(DensityFunction.NoiseHolder offsetNoise) implements ShiftNoise
    {
        static final KeyDispatchDataCodec<ShiftB> CODEC = DensityFunctions.singleArgumentCodec(DensityFunction.NoiseHolder.CODEC, ShiftB::new, ShiftB::offsetNoise);

        @Override
        public double compute(DensityFunction.FunctionContext functionContext) {
            return this.compute(functionContext.blockZ(), functionContext.blockX(), 0.0);
        }

        @Override
        public DensityFunction mapAll(DensityFunction.Visitor visitor) {
            return visitor.apply(new ShiftB(visitor.visitNoise(this.offsetNoise)));
        }

        @Override
        public KeyDispatchDataCodec<? extends DensityFunction> codec() {
            return CODEC;
        }
    }

    protected record Shift(DensityFunction.NoiseHolder offsetNoise) implements ShiftNoise
    {
        static final KeyDispatchDataCodec<Shift> CODEC = DensityFunctions.singleArgumentCodec(DensityFunction.NoiseHolder.CODEC, Shift::new, Shift::offsetNoise);

        @Override
        public double compute(DensityFunction.FunctionContext functionContext) {
            return this.compute(functionContext.blockX(), functionContext.blockY(), functionContext.blockZ());
        }

        @Override
        public DensityFunction mapAll(DensityFunction.Visitor visitor) {
            return visitor.apply(new Shift(visitor.visitNoise(this.offsetNoise)));
        }

        @Override
        public KeyDispatchDataCodec<? extends DensityFunction> codec() {
            return CODEC;
        }
    }

    record BlendDensity(DensityFunction input) implements TransformerWithContext
    {
        static final KeyDispatchDataCodec<BlendDensity> CODEC = DensityFunctions.singleFunctionArgumentCodec(BlendDensity::new, BlendDensity::input);

        @Override
        public double transform(DensityFunction.FunctionContext functionContext, double d) {
            return functionContext.getBlender().blendDensity(functionContext, d);
        }

        @Override
        public DensityFunction mapAll(DensityFunction.Visitor visitor) {
            return visitor.apply(new BlendDensity(this.input.mapAll(visitor)));
        }

        @Override
        public double minValue() {
            return Double.NEGATIVE_INFINITY;
        }

        @Override
        public double maxValue() {
            return Double.POSITIVE_INFINITY;
        }

        @Override
        public KeyDispatchDataCodec<? extends DensityFunction> codec() {
            return CODEC;
        }
    }

    protected record Clamp(DensityFunction input, double minValue, double maxValue) implements PureTransformer
    {
        private static final MapCodec<Clamp> DATA_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(((MapCodec)DensityFunction.DIRECT_CODEC.fieldOf("input")).forGetter(Clamp::input), ((MapCodec)NOISE_VALUE_CODEC.fieldOf("min")).forGetter(Clamp::minValue), ((MapCodec)NOISE_VALUE_CODEC.fieldOf("max")).forGetter(Clamp::maxValue)).apply((Applicative<Clamp, ?>)instance, Clamp::new));
        public static final KeyDispatchDataCodec<Clamp> CODEC = DensityFunctions.makeCodec(DATA_CODEC);

        @Override
        public double transform(double d) {
            return Mth.clamp(d, this.minValue, this.maxValue);
        }

        @Override
        public DensityFunction mapAll(DensityFunction.Visitor visitor) {
            return new Clamp(this.input.mapAll(visitor), this.minValue, this.maxValue);
        }

        @Override
        public KeyDispatchDataCodec<? extends DensityFunction> codec() {
            return CODEC;
        }
    }

    protected record Mapped(Type type, DensityFunction input, double minValue, double maxValue) implements PureTransformer
    {
        public static Mapped create(Type type, DensityFunction densityFunction) {
            double d = densityFunction.minValue();
            double e = Mapped.transform(type, d);
            double f = Mapped.transform(type, densityFunction.maxValue());
            if (type == Type.ABS || type == Type.SQUARE) {
                return new Mapped(type, densityFunction, Math.max(0.0, d), Math.max(e, f));
            }
            return new Mapped(type, densityFunction, e, f);
        }

        private static double transform(Type type, double d) {
            return switch (type.ordinal()) {
                default -> throw new MatchException(null, null);
                case 0 -> Math.abs(d);
                case 1 -> d * d;
                case 2 -> d * d * d;
                case 3 -> {
                    if (d > 0.0) {
                        yield d;
                    }
                    yield d * 0.5;
                }
                case 4 -> {
                    if (d > 0.0) {
                        yield d;
                    }
                    yield d * 0.25;
                }
                case 5 -> {
                    double e = Mth.clamp(d, -1.0, 1.0);
                    yield e / 2.0 - e * e * e / 24.0;
                }
            };
        }

        @Override
        public double transform(double d) {
            return Mapped.transform(this.type, d);
        }

        @Override
        public Mapped mapAll(DensityFunction.Visitor visitor) {
            return Mapped.create(this.type, this.input.mapAll(visitor));
        }

        @Override
        public KeyDispatchDataCodec<? extends DensityFunction> codec() {
            return this.type.codec;
        }

        @Override
        public /* synthetic */ DensityFunction mapAll(DensityFunction.Visitor visitor) {
            return this.mapAll(visitor);
        }

        static enum Type implements StringRepresentable
        {
            ABS("abs"),
            SQUARE("square"),
            CUBE("cube"),
            HALF_NEGATIVE("half_negative"),
            QUARTER_NEGATIVE("quarter_negative"),
            SQUEEZE("squeeze");

            private final String name;
            final KeyDispatchDataCodec<Mapped> codec = DensityFunctions.singleFunctionArgumentCodec(densityFunction -> Mapped.create(this, densityFunction), Mapped::input);

            private Type(String string2) {
                this.name = string2;
            }

            @Override
            public String getSerializedName() {
                return this.name;
            }
        }
    }

    static interface TwoArgumentSimpleFunction
    extends DensityFunction {
        public static final Logger LOGGER = LogUtils.getLogger();

        public static TwoArgumentSimpleFunction create(Type type, DensityFunction densityFunction, DensityFunction densityFunction2) {
            double i;
            double d = densityFunction.minValue();
            double e = densityFunction2.minValue();
            double f = densityFunction.maxValue();
            double g = densityFunction2.maxValue();
            if (type == Type.MIN || type == Type.MAX) {
                boolean bl2;
                boolean bl = d >= g;
                boolean bl3 = bl2 = e >= f;
                if (bl || bl2) {
                    LOGGER.warn("Creating a " + String.valueOf(type) + " function between two non-overlapping inputs: " + String.valueOf(densityFunction) + " and " + String.valueOf(densityFunction2));
                }
            }
            double h = switch (type.ordinal()) {
                default -> throw new MatchException(null, null);
                case 0 -> d + e;
                case 3 -> Math.max(d, e);
                case 2 -> Math.min(d, e);
                case 1 -> d > 0.0 && e > 0.0 ? d * e : (f < 0.0 && g < 0.0 ? f * g : Math.min(d * g, f * e));
            };
            switch (type.ordinal()) {
                default: {
                    throw new MatchException(null, null);
                }
                case 0: {
                    double d2 = f + g;
                    break;
                }
                case 3: {
                    double d2 = Math.max(f, g);
                    break;
                }
                case 2: {
                    double d2 = Math.min(f, g);
                    break;
                }
                case 1: {
                    double d2 = d > 0.0 && e > 0.0 ? f * g : (i = f < 0.0 && g < 0.0 ? d * e : Math.max(d * e, f * g));
                }
            }
            if (type == Type.MUL || type == Type.ADD) {
                if (densityFunction instanceof Constant) {
                    Constant constant = (Constant)densityFunction;
                    return new MulOrAdd(type == Type.ADD ? MulOrAdd.Type.ADD : MulOrAdd.Type.MUL, densityFunction2, h, i, constant.value);
                }
                if (densityFunction2 instanceof Constant) {
                    Constant constant = (Constant)densityFunction2;
                    return new MulOrAdd(type == Type.ADD ? MulOrAdd.Type.ADD : MulOrAdd.Type.MUL, densityFunction, h, i, constant.value);
                }
            }
            return new Ap2(type, densityFunction, densityFunction2, h, i);
        }

        public Type type();

        public DensityFunction argument1();

        public DensityFunction argument2();

        @Override
        default public KeyDispatchDataCodec<? extends DensityFunction> codec() {
            return this.type().codec;
        }

        public static enum Type implements StringRepresentable
        {
            ADD("add"),
            MUL("mul"),
            MIN("min"),
            MAX("max");

            final KeyDispatchDataCodec<TwoArgumentSimpleFunction> codec = DensityFunctions.doubleFunctionArgumentCodec((densityFunction, densityFunction2) -> TwoArgumentSimpleFunction.create(this, densityFunction, densityFunction2), TwoArgumentSimpleFunction::argument1, TwoArgumentSimpleFunction::argument2);
            private final String name;

            private Type(String string2) {
                this.name = string2;
            }

            @Override
            public String getSerializedName() {
                return this.name;
            }
        }
    }

    public record Spline(CubicSpline<Point, Coordinate> spline) implements DensityFunction
    {
        private static final Codec<CubicSpline<Point, Coordinate>> SPLINE_CODEC = CubicSpline.codec(Coordinate.CODEC);
        private static final MapCodec<Spline> DATA_CODEC = ((MapCodec)SPLINE_CODEC.fieldOf("spline")).xmap(Spline::new, Spline::spline);
        public static final KeyDispatchDataCodec<Spline> CODEC = DensityFunctions.makeCodec(DATA_CODEC);

        @Override
        public double compute(DensityFunction.FunctionContext functionContext) {
            return this.spline.apply(new Point(functionContext));
        }

        @Override
        public double minValue() {
            return this.spline.minValue();
        }

        @Override
        public double maxValue() {
            return this.spline.maxValue();
        }

        @Override
        public void fillArray(double[] ds, DensityFunction.ContextProvider contextProvider) {
            contextProvider.fillAllDirectly(ds, this);
        }

        @Override
        public DensityFunction mapAll(DensityFunction.Visitor visitor) {
            return visitor.apply(new Spline(this.spline.mapAll((I coordinate) -> coordinate.mapAll(visitor))));
        }

        @Override
        public KeyDispatchDataCodec<? extends DensityFunction> codec() {
            return CODEC;
        }

        public record Point(DensityFunction.FunctionContext context) {
        }

        public record Coordinate(Holder<DensityFunction> function) implements ToFloatFunction<Point>
        {
            public static final Codec<Coordinate> CODEC = DensityFunction.CODEC.xmap(Coordinate::new, Coordinate::function);

            @Override
            public String toString() {
                Optional<ResourceKey<DensityFunction>> optional = this.function.unwrapKey();
                if (optional.isPresent()) {
                    ResourceKey<DensityFunction> resourceKey = optional.get();
                    if (resourceKey == NoiseRouterData.CONTINENTS) {
                        return "continents";
                    }
                    if (resourceKey == NoiseRouterData.EROSION) {
                        return "erosion";
                    }
                    if (resourceKey == NoiseRouterData.RIDGES) {
                        return "weirdness";
                    }
                    if (resourceKey == NoiseRouterData.RIDGES_FOLDED) {
                        return "ridges";
                    }
                }
                return "Coordinate[" + String.valueOf(this.function) + "]";
            }

            @Override
            public float apply(Point point) {
                return (float)this.function.value().compute(point.context());
            }

            @Override
            public float minValue() {
                return this.function.isBound() ? (float)this.function.value().minValue() : Float.NEGATIVE_INFINITY;
            }

            @Override
            public float maxValue() {
                return this.function.isBound() ? (float)this.function.value().maxValue() : Float.POSITIVE_INFINITY;
            }

            public Coordinate mapAll(DensityFunction.Visitor visitor) {
                return new Coordinate(new Holder.Direct<DensityFunction>(this.function.value().mapAll(visitor)));
            }
        }
    }

    record Constant(double value) implements DensityFunction.SimpleFunction
    {
        static final KeyDispatchDataCodec<Constant> CODEC = DensityFunctions.singleArgumentCodec(NOISE_VALUE_CODEC, Constant::new, Constant::value);
        static final Constant ZERO = new Constant(0.0);

        @Override
        public double compute(DensityFunction.FunctionContext functionContext) {
            return this.value;
        }

        @Override
        public void fillArray(double[] ds, DensityFunction.ContextProvider contextProvider) {
            Arrays.fill(ds, this.value);
        }

        @Override
        public double minValue() {
            return this.value;
        }

        @Override
        public double maxValue() {
            return this.value;
        }

        @Override
        public KeyDispatchDataCodec<? extends DensityFunction> codec() {
            return CODEC;
        }
    }

    record YClampedGradient(int fromY, int toY, double fromValue, double toValue) implements DensityFunction.SimpleFunction
    {
        private static final MapCodec<YClampedGradient> DATA_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(((MapCodec)Codec.intRange(DimensionType.MIN_Y * 2, DimensionType.MAX_Y * 2).fieldOf("from_y")).forGetter(YClampedGradient::fromY), ((MapCodec)Codec.intRange(DimensionType.MIN_Y * 2, DimensionType.MAX_Y * 2).fieldOf("to_y")).forGetter(YClampedGradient::toY), ((MapCodec)NOISE_VALUE_CODEC.fieldOf("from_value")).forGetter(YClampedGradient::fromValue), ((MapCodec)NOISE_VALUE_CODEC.fieldOf("to_value")).forGetter(YClampedGradient::toValue)).apply((Applicative<YClampedGradient, ?>)instance, YClampedGradient::new));
        public static final KeyDispatchDataCodec<YClampedGradient> CODEC = DensityFunctions.makeCodec(DATA_CODEC);

        @Override
        public double compute(DensityFunction.FunctionContext functionContext) {
            return Mth.clampedMap((double)functionContext.blockY(), (double)this.fromY, (double)this.toY, this.fromValue, this.toValue);
        }

        @Override
        public double minValue() {
            return Math.min(this.fromValue, this.toValue);
        }

        @Override
        public double maxValue() {
            return Math.max(this.fromValue, this.toValue);
        }

        @Override
        public KeyDispatchDataCodec<? extends DensityFunction> codec() {
            return CODEC;
        }
    }

    record Ap2(TwoArgumentSimpleFunction.Type type, DensityFunction argument1, DensityFunction argument2, double minValue, double maxValue) implements TwoArgumentSimpleFunction
    {
        @Override
        public double compute(DensityFunction.FunctionContext functionContext) {
            double d = this.argument1.compute(functionContext);
            return switch (this.type.ordinal()) {
                default -> throw new MatchException(null, null);
                case 0 -> d + this.argument2.compute(functionContext);
                case 1 -> {
                    if (d == 0.0) {
                        yield 0.0;
                    }
                    yield d * this.argument2.compute(functionContext);
                }
                case 2 -> {
                    if (d < this.argument2.minValue()) {
                        yield d;
                    }
                    yield Math.min(d, this.argument2.compute(functionContext));
                }
                case 3 -> d > this.argument2.maxValue() ? d : Math.max(d, this.argument2.compute(functionContext));
            };
        }

        @Override
        public void fillArray(double[] ds, DensityFunction.ContextProvider contextProvider) {
            this.argument1.fillArray(ds, contextProvider);
            switch (this.type.ordinal()) {
                case 0: {
                    double[] es = new double[ds.length];
                    this.argument2.fillArray(es, contextProvider);
                    for (int i = 0; i < ds.length; ++i) {
                        ds[i] = ds[i] + es[i];
                    }
                    break;
                }
                case 1: {
                    for (int j = 0; j < ds.length; ++j) {
                        double d = ds[j];
                        ds[j] = d == 0.0 ? 0.0 : d * this.argument2.compute(contextProvider.forIndex(j));
                    }
                    break;
                }
                case 2: {
                    double e = this.argument2.minValue();
                    for (int k = 0; k < ds.length; ++k) {
                        double f = ds[k];
                        ds[k] = f < e ? f : Math.min(f, this.argument2.compute(contextProvider.forIndex(k)));
                    }
                    break;
                }
                case 3: {
                    double e = this.argument2.maxValue();
                    for (int k = 0; k < ds.length; ++k) {
                        double f = ds[k];
                        ds[k] = f > e ? f : Math.max(f, this.argument2.compute(contextProvider.forIndex(k)));
                    }
                    break;
                }
            }
        }

        @Override
        public DensityFunction mapAll(DensityFunction.Visitor visitor) {
            return visitor.apply(TwoArgumentSimpleFunction.create(this.type, this.argument1.mapAll(visitor), this.argument2.mapAll(visitor)));
        }
    }

    record MulOrAdd(Type specificType, DensityFunction input, double minValue, double maxValue, double argument) implements PureTransformer,
    TwoArgumentSimpleFunction
    {
        @Override
        public TwoArgumentSimpleFunction.Type type() {
            return this.specificType == Type.MUL ? TwoArgumentSimpleFunction.Type.MUL : TwoArgumentSimpleFunction.Type.ADD;
        }

        @Override
        public DensityFunction argument1() {
            return DensityFunctions.constant(this.argument);
        }

        @Override
        public DensityFunction argument2() {
            return this.input;
        }

        @Override
        public double transform(double d) {
            return switch (this.specificType.ordinal()) {
                default -> throw new MatchException(null, null);
                case 0 -> d * this.argument;
                case 1 -> d + this.argument;
            };
        }

        @Override
        public DensityFunction mapAll(DensityFunction.Visitor visitor) {
            double g;
            double f;
            DensityFunction densityFunction = this.input.mapAll(visitor);
            double d = densityFunction.minValue();
            double e = densityFunction.maxValue();
            if (this.specificType == Type.ADD) {
                f = d + this.argument;
                g = e + this.argument;
            } else if (this.argument >= 0.0) {
                f = d * this.argument;
                g = e * this.argument;
            } else {
                f = e * this.argument;
                g = d * this.argument;
            }
            return new MulOrAdd(this.specificType, densityFunction, f, g, this.argument);
        }

        static enum Type {
            MUL,
            ADD;

        }
    }

    static interface ShiftNoise
    extends DensityFunction {
        public DensityFunction.NoiseHolder offsetNoise();

        @Override
        default public double minValue() {
            return -this.maxValue();
        }

        @Override
        default public double maxValue() {
            return this.offsetNoise().maxValue() * 4.0;
        }

        default public double compute(double d, double e, double f) {
            return this.offsetNoise().getValue(d * 0.25, e * 0.25, f * 0.25) * 4.0;
        }

        @Override
        default public void fillArray(double[] ds, DensityFunction.ContextProvider contextProvider) {
            contextProvider.fillAllDirectly(ds, this);
        }
    }

    public static interface MarkerOrMarked
    extends DensityFunction {
        public Marker.Type type();

        public DensityFunction wrapped();

        @Override
        default public KeyDispatchDataCodec<? extends DensityFunction> codec() {
            return this.type().codec;
        }

        @Override
        default public DensityFunction mapAll(DensityFunction.Visitor visitor) {
            return visitor.apply(new Marker(this.type(), this.wrapped().mapAll(visitor)));
        }
    }

    @VisibleForDebug
    public record HolderHolder(Holder<DensityFunction> function) implements DensityFunction
    {
        @Override
        public double compute(DensityFunction.FunctionContext functionContext) {
            return this.function.value().compute(functionContext);
        }

        @Override
        public void fillArray(double[] ds, DensityFunction.ContextProvider contextProvider) {
            this.function.value().fillArray(ds, contextProvider);
        }

        @Override
        public DensityFunction mapAll(DensityFunction.Visitor visitor) {
            return visitor.apply(new HolderHolder(new Holder.Direct<DensityFunction>(this.function.value().mapAll(visitor))));
        }

        @Override
        public double minValue() {
            return this.function.isBound() ? this.function.value().minValue() : Double.NEGATIVE_INFINITY;
        }

        @Override
        public double maxValue() {
            return this.function.isBound() ? this.function.value().maxValue() : Double.POSITIVE_INFINITY;
        }

        @Override
        public KeyDispatchDataCodec<? extends DensityFunction> codec() {
            throw new UnsupportedOperationException("Calling .codec() on HolderHolder");
        }
    }

    public static interface BeardifierOrMarker
    extends DensityFunction.SimpleFunction {
        public static final KeyDispatchDataCodec<DensityFunction> CODEC = KeyDispatchDataCodec.of(MapCodec.unit(BeardifierMarker.INSTANCE));

        @Override
        default public KeyDispatchDataCodec<? extends DensityFunction> codec() {
            return CODEC;
        }
    }

    static interface PureTransformer
    extends DensityFunction {
        public DensityFunction input();

        @Override
        default public double compute(DensityFunction.FunctionContext functionContext) {
            return this.transform(this.input().compute(functionContext));
        }

        @Override
        default public void fillArray(double[] ds, DensityFunction.ContextProvider contextProvider) {
            this.input().fillArray(ds, contextProvider);
            for (int i = 0; i < ds.length; ++i) {
                ds[i] = this.transform(ds[i]);
            }
        }

        public double transform(double var1);
    }

    static interface TransformerWithContext
    extends DensityFunction {
        public DensityFunction input();

        @Override
        default public double compute(DensityFunction.FunctionContext functionContext) {
            return this.transform(functionContext, this.input().compute(functionContext));
        }

        @Override
        default public void fillArray(double[] ds, DensityFunction.ContextProvider contextProvider) {
            this.input().fillArray(ds, contextProvider);
            for (int i = 0; i < ds.length; ++i) {
                ds[i] = this.transform(contextProvider.forIndex(i), ds[i]);
            }
        }

        public double transform(DensityFunction.FunctionContext var1, double var2);
    }
}
```

```
package net.minecraft.world.level.levelgen;

public final class NoiseBasedChunkGenerator
extends ChunkGenerator {
    public static final MapCodec<NoiseBasedChunkGenerator> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(((MapCodec)BiomeSource.CODEC.fieldOf("biome_source")).forGetter(noiseBasedChunkGenerator -> noiseBasedChunkGenerator.biomeSource), ((MapCodec)NoiseGeneratorSettings.CODEC.fieldOf("settings")).forGetter(noiseBasedChunkGenerator -> noiseBasedChunkGenerator.settings)).apply((Applicative<NoiseBasedChunkGenerator, ?>)instance, instance.stable(NoiseBasedChunkGenerator::new)));
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private final Holder<NoiseGeneratorSettings> settings;
    private final Supplier<Aquifer.FluidPicker> globalFluidPicker;

    public NoiseBasedChunkGenerator(BiomeSource biomeSource, Holder<NoiseGeneratorSettings> holder) {
        super(biomeSource);
        this.settings = holder;
        this.globalFluidPicker = Suppliers.memoize(() -> NoiseBasedChunkGenerator.createFluidPicker((NoiseGeneratorSettings)holder.value()));
    }

    private static Aquifer.FluidPicker createFluidPicker(NoiseGeneratorSettings noiseGeneratorSettings) {
        Aquifer.FluidStatus fluidStatus = new Aquifer.FluidStatus(-54, Blocks.LAVA.defaultBlockState());
        int i = noiseGeneratorSettings.seaLevel();
        Aquifer.FluidStatus fluidStatus2 = new Aquifer.FluidStatus(i, noiseGeneratorSettings.defaultFluid());
        Aquifer.FluidStatus fluidStatus3 = new Aquifer.FluidStatus(DimensionType.MIN_Y * 2, Blocks.AIR.defaultBlockState());
        return (j, k, l) -> {
            if (k < Math.min(-54, i)) {
                return fluidStatus;
            }
            return fluidStatus2;
        };
    }

    @Override
    public CompletableFuture<ChunkAccess> createBiomes(RandomState randomState, Blender blender, StructureManager structureManager, ChunkAccess chunkAccess) {
        return CompletableFuture.supplyAsync(Util.wrapThreadWithTaskName("init_biomes", () -> {
            this.doCreateBiomes(blender, randomState, structureManager, chunkAccess);
            return chunkAccess;
        }), Util.backgroundExecutor());
    }

    private void doCreateBiomes(Blender blender, RandomState randomState, StructureManager structureManager, ChunkAccess chunkAccess2) {
        NoiseChunk noiseChunk = chunkAccess2.getOrCreateNoiseChunk(chunkAccess -> this.createNoiseChunk((ChunkAccess)chunkAccess, structureManager, blender, randomState));
        BiomeResolver biomeResolver = BelowZeroRetrogen.getBiomeResolver(blender.getBiomeResolver(this.biomeSource), chunkAccess2);
        chunkAccess2.fillBiomesFromNoise(biomeResolver, noiseChunk.cachedClimateSampler(randomState.router(), this.settings.value().spawnTarget()));
    }

    private NoiseChunk createNoiseChunk(ChunkAccess chunkAccess, StructureManager structureManager, Blender blender, RandomState randomState) {
        return NoiseChunk.forChunk(chunkAccess, randomState, Beardifier.forStructuresInChunk(structureManager, chunkAccess.getPos()), this.settings.value(), this.globalFluidPicker.get(), blender);
    }

    @Override
    protected MapCodec<? extends ChunkGenerator> codec() {
        return CODEC;
    }

    public Holder<NoiseGeneratorSettings> generatorSettings() {
        return this.settings;
    }

    public boolean stable(ResourceKey<NoiseGeneratorSettings> resourceKey) {
        return this.settings.is(resourceKey);
    }

    @Override
    public int getBaseHeight(int i, int j, Heightmap.Types types, LevelHeightAccessor levelHeightAccessor, RandomState randomState) {
        return this.iterateNoiseColumn(levelHeightAccessor, randomState, i, j, null, types.isOpaque()).orElse(levelHeightAccessor.getMinBuildHeight());
    }

    @Override
    public NoiseColumn getBaseColumn(int i, int j, LevelHeightAccessor levelHeightAccessor, RandomState randomState) {
        MutableObject<NoiseColumn> mutableObject = new MutableObject<NoiseColumn>();
        this.iterateNoiseColumn(levelHeightAccessor, randomState, i, j, mutableObject, null);
        return mutableObject.getValue();
    }

    @Override
    public void addDebugScreenInfo(List<String> list, RandomState randomState, BlockPos blockPos) {
        DecimalFormat decimalFormat = new DecimalFormat("0.000");
        NoiseRouter noiseRouter = randomState.router();
        DensityFunction.SinglePointContext singlePointContext = new DensityFunction.SinglePointContext(blockPos.getX(), blockPos.getY(), blockPos.getZ());
        double d = noiseRouter.ridges().compute(singlePointContext);
        list.add("NoiseRouter T: " + decimalFormat.format(noiseRouter.temperature().compute(singlePointContext)) + " V: " + decimalFormat.format(noiseRouter.vegetation().compute(singlePointContext)) + " C: " + decimalFormat.format(noiseRouter.continents().compute(singlePointContext)) + " E: " + decimalFormat.format(noiseRouter.erosion().compute(singlePointContext)) + " D: " + decimalFormat.format(noiseRouter.depth().compute(singlePointContext)) + " W: " + decimalFormat.format(d) + " PV: " + decimalFormat.format(NoiseRouterData.peaksAndValleys((float)d)) + " AS: " + decimalFormat.format(noiseRouter.initialDensityWithoutJaggedness().compute(singlePointContext)) + " N: " + decimalFormat.format(noiseRouter.finalDensity().compute(singlePointContext)));
    }

    private OptionalInt iterateNoiseColumn(LevelHeightAccessor levelHeightAccessor, RandomState randomState, int i, int j, @Nullable MutableObject<NoiseColumn> mutableObject, @Nullable Predicate<BlockState> predicate) {
        BlockState[] blockStates;
        NoiseSettings noiseSettings = this.settings.value().noiseSettings().clampToHeightAccessor(levelHeightAccessor);
        int k = noiseSettings.getCellHeight();
        int l = noiseSettings.minY();
        int m = Mth.floorDiv(l, k);
        int n = Mth.floorDiv(noiseSettings.height(), k);
        if (n <= 0) {
            return OptionalInt.empty();
        }
        if (mutableObject == null) {
            blockStates = null;
        } else {
            blockStates = new BlockState[noiseSettings.height()];
            mutableObject.setValue(new NoiseColumn(l, blockStates));
        }
        int o = noiseSettings.getCellWidth();
        int p = Math.floorDiv(i, o);
        int q = Math.floorDiv(j, o);
        int r = Math.floorMod(i, o);
        int s = Math.floorMod(j, o);
        int t = p * o;
        int u = q * o;
        double d = (double)r / (double)o;
        double e = (double)s / (double)o;
        NoiseChunk noiseChunk = new NoiseChunk(1, randomState, t, u, noiseSettings, DensityFunctions.BeardifierMarker.INSTANCE, this.settings.value(), this.globalFluidPicker.get(), Blender.empty());
        noiseChunk.initializeForFirstCellX();
        noiseChunk.advanceCellX(0);
        for (int v = n - 1; v >= 0; --v) {
            noiseChunk.selectCellYZ(v, 0);
            for (int w = k - 1; w >= 0; --w) {
                BlockState blockState2;
                int x = (m + v) * k + w;
                double f = (double)w / (double)k;
                noiseChunk.updateForY(x, f);
                noiseChunk.updateForX(i, d);
                noiseChunk.updateForZ(j, e);
                BlockState blockState = noiseChunk.getInterpolatedState();
                BlockState blockState3 = blockState2 = blockState == null ? this.settings.value().defaultBlock() : blockState;
                if (blockStates != null) {
                    int y = v * k + w;
                    blockStates[y] = blockState2;
                }
                if (predicate == null || !predicate.test(blockState2)) continue;
                noiseChunk.stopInterpolation();
                return OptionalInt.of(x + 1);
            }
        }
        noiseChunk.stopInterpolation();
        return OptionalInt.empty();
    }

    @Override
    public void buildSurface(WorldGenRegion worldGenRegion, StructureManager structureManager, RandomState randomState, ChunkAccess chunkAccess) {
        if (SharedConstants.debugVoidTerrain(chunkAccess.getPos())) {
            return;
        }
        WorldGenerationContext worldGenerationContext = new WorldGenerationContext(this, worldGenRegion);
        this.buildSurface(chunkAccess, worldGenerationContext, randomState, structureManager, worldGenRegion.getBiomeManager(), worldGenRegion.registryAccess().registryOrThrow(Registries.BIOME), Blender.of(worldGenRegion));
    }

    @VisibleForTesting
    public void buildSurface(ChunkAccess chunkAccess2, WorldGenerationContext worldGenerationContext, RandomState randomState, StructureManager structureManager, BiomeManager biomeManager, Registry<Biome> registry, Blender blender) {
        NoiseChunk noiseChunk = chunkAccess2.getOrCreateNoiseChunk(chunkAccess -> this.createNoiseChunk((ChunkAccess)chunkAccess, structureManager, blender, randomState));
        NoiseGeneratorSettings noiseGeneratorSettings = this.settings.value();
        randomState.surfaceSystem().buildSurface(randomState, biomeManager, registry, noiseGeneratorSettings.useLegacyRandomSource(), worldGenerationContext, chunkAccess2, noiseChunk, noiseGeneratorSettings.surfaceRule());
    }

    @Override
    public void applyCarvers(WorldGenRegion worldGenRegion, long l, RandomState randomState, BiomeManager biomeManager, StructureManager structureManager, ChunkAccess chunkAccess2, GenerationStep.Carving carving) {
        BiomeManager biomeManager2 = biomeManager.withDifferentSource((i, j, k) -> this.biomeSource.getNoiseBiome(i, j, k, randomState.sampler()));
        WorldgenRandom worldgenRandom = new WorldgenRandom(new LegacyRandomSource(RandomSupport.generateUniqueSeed()));
        int i2 = 8;
        ChunkPos chunkPos = chunkAccess2.getPos();
        NoiseChunk noiseChunk = chunkAccess2.getOrCreateNoiseChunk(chunkAccess -> this.createNoiseChunk((ChunkAccess)chunkAccess, structureManager, Blender.of(worldGenRegion), randomState));
        Aquifer aquifer = noiseChunk.aquifer();
        CarvingContext carvingContext = new CarvingContext(this, worldGenRegion.registryAccess(), chunkAccess2.getHeightAccessorForGeneration(), noiseChunk, randomState, this.settings.value().surfaceRule());
        CarvingMask carvingMask = ((ProtoChunk)chunkAccess2).getOrCreateCarvingMask(carving);
        for (int j2 = -8; j2 <= 8; ++j2) {
            for (int k2 = -8; k2 <= 8; ++k2) {
                ChunkPos chunkPos2 = new ChunkPos(chunkPos.x + j2, chunkPos.z + k2);
                ChunkAccess chunkAccess22 = worldGenRegion.getChunk(chunkPos2.x, chunkPos2.z);
                BiomeGenerationSettings biomeGenerationSettings = chunkAccess22.carverBiome(() -> this.getBiomeGenerationSettings(this.biomeSource.getNoiseBiome(QuartPos.fromBlock(chunkPos2.getMinBlockX()), 0, QuartPos.fromBlock(chunkPos2.getMinBlockZ()), randomState.sampler())));
                Iterable<Holder<ConfiguredWorldCarver<?>>> iterable = biomeGenerationSettings.getCarvers(carving);
                int m = 0;
                for (Holder<ConfiguredWorldCarver<?>> holder : iterable) {
                    ConfiguredWorldCarver<?> configuredWorldCarver = holder.value();
                    worldgenRandom.setLargeFeatureSeed(l + (long)m, chunkPos2.x, chunkPos2.z);
                    if (configuredWorldCarver.isStartChunk(worldgenRandom)) {
                        configuredWorldCarver.carve(carvingContext, chunkAccess2, biomeManager2::getBiome, worldgenRandom, aquifer, chunkPos2, carvingMask);
                    }
                    ++m;
                }
            }
        }
    }

    @Override
    public CompletableFuture<ChunkAccess> fillFromNoise(Blender blender, RandomState randomState, StructureManager structureManager, ChunkAccess chunkAccess) {
        NoiseSettings noiseSettings = this.settings.value().noiseSettings().clampToHeightAccessor(chunkAccess.getHeightAccessorForGeneration());
        int i = noiseSettings.minY();
        int j = Mth.floorDiv(i, noiseSettings.getCellHeight());
        int k = Mth.floorDiv(noiseSettings.height(), noiseSettings.getCellHeight());
        if (k <= 0) {
            return CompletableFuture.completedFuture(chunkAccess);
        }
        return CompletableFuture.supplyAsync(Util.wrapThreadWithTaskName("wgen_fill_noise", () -> {
            int l = chunkAccess.getSectionIndex(k * noiseSettings.getCellHeight() - 1 + i);
            int m = chunkAccess.getSectionIndex(i);
            HashSet<LevelChunkSection> set = Sets.newHashSet();
            for (int n = l; n >= m; --n) {
                LevelChunkSection levelChunkSection = chunkAccess.getSection(n);
                levelChunkSection.acquire();
                set.add(levelChunkSection);
            }
            try {
                ChunkAccess chunkAccess2 = this.doFill(blender, structureManager, randomState, chunkAccess, j, k);
                return chunkAccess2;
            } finally {
                for (LevelChunkSection levelChunkSection2 : set) {
                    levelChunkSection2.release();
                }
            }
        }), Util.backgroundExecutor());
    }

    private ChunkAccess doFill(Blender blender, StructureManager structureManager, RandomState randomState, ChunkAccess chunkAccess2, int i, int j) {
        NoiseChunk noiseChunk = chunkAccess2.getOrCreateNoiseChunk(chunkAccess -> this.createNoiseChunk((ChunkAccess)chunkAccess, structureManager, blender, randomState));
        Heightmap heightmap = chunkAccess2.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
        Heightmap heightmap2 = chunkAccess2.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        ChunkPos chunkPos = chunkAccess2.getPos();
        int k = chunkPos.getMinBlockX();
        int l = chunkPos.getMinBlockZ();
        Aquifer aquifer = noiseChunk.aquifer();
        noiseChunk.initializeForFirstCellX();
        BlockPos.MutableBlockPos mutableBlockPos = new BlockPos.MutableBlockPos();
        int m = noiseChunk.cellWidth();
        int n = noiseChunk.cellHeight();
        int o = 16 / m;
        int p = 16 / m;
        for (int q = 0; q < o; ++q) {
            noiseChunk.advanceCellX(q);
            for (int r = 0; r < p; ++r) {
                int s = chunkAccess2.getSectionsCount() - 1;
                LevelChunkSection levelChunkSection = chunkAccess2.getSection(s);
                for (int t = j - 1; t >= 0; --t) {
                    noiseChunk.selectCellYZ(t, r);
                    for (int u = n - 1; u >= 0; --u) {
                        int v = (i + t) * n + u;
                        int w = v & 0xF;
                        int x = chunkAccess2.getSectionIndex(v);
                        if (s != x) {
                            s = x;
                            levelChunkSection = chunkAccess2.getSection(x);
                        }
                        double d = (double)u / (double)n;
                        noiseChunk.updateForY(v, d);
                        for (int y = 0; y < m; ++y) {
                            int z = k + q * m + y;
                            int aa = z & 0xF;
                            double e = (double)y / (double)m;
                            noiseChunk.updateForX(z, e);
                            for (int ab = 0; ab < m; ++ab) {
                                int ac = l + r * m + ab;
                                int ad = ac & 0xF;
                                double f = (double)ab / (double)m;
                                noiseChunk.updateForZ(ac, f);
                                BlockState blockState = noiseChunk.getInterpolatedState();
                                if (blockState == null) {
                                    blockState = this.settings.value().defaultBlock();
                                }
                                if ((blockState = this.debugPreliminarySurfaceLevel(noiseChunk, z, v, ac, blockState)) == AIR || SharedConstants.debugVoidTerrain(chunkAccess2.getPos())) continue;
                                levelChunkSection.setBlockState(aa, w, ad, blockState, false);
                                heightmap.update(aa, v, ad, blockState);
                                heightmap2.update(aa, v, ad, blockState);
                                if (!aquifer.shouldScheduleFluidUpdate() || blockState.getFluidState().isEmpty()) continue;
                                mutableBlockPos.set(z, v, ac);
                                chunkAccess2.markPosForPostprocessing(mutableBlockPos);
                            }
                        }
                    }
                }
            }
            noiseChunk.swapSlices();
        }
        noiseChunk.stopInterpolation();
        return chunkAccess2;
    }

    private BlockState debugPreliminarySurfaceLevel(NoiseChunk noiseChunk, int i, int j, int k, BlockState blockState) {
        return blockState;
    }

    @Override
    public int getGenDepth() {
        return this.settings.value().noiseSettings().height();
    }

    @Override
    public int getSeaLevel() {
        return this.settings.value().seaLevel();
    }

    @Override
    public int getMinY() {
        return this.settings.value().noiseSettings().minY();
    }

    @Override
    public void spawnOriginalMobs(WorldGenRegion worldGenRegion) {
        if (this.settings.value().disableMobGeneration()) {
            return;
        }
        ChunkPos chunkPos = worldGenRegion.getCenter();
        Holder<Biome> holder = worldGenRegion.getBiome(chunkPos.getWorldPosition().atY(worldGenRegion.getMaxBuildHeight() - 1));
        WorldgenRandom worldgenRandom = new WorldgenRandom(new LegacyRandomSource(RandomSupport.generateUniqueSeed()));
        worldgenRandom.setDecorationSeed(worldGenRegion.getSeed(), chunkPos.getMinBlockX(), chunkPos.getMinBlockZ());
        NaturalSpawner.spawnMobsForChunkGeneration(worldGenRegion, holder, chunkPos, worldgenRandom);
    }
}
```

```
package net.minecraft.world.level.levelgen;

public class Heightmap {
    private static final Logger LOGGER = LogUtils.getLogger();
    static final Predicate<BlockState> NOT_AIR = blockState -> !blockState.isAir();
    static final Predicate<BlockState> MATERIAL_MOTION_BLOCKING = BlockBehaviour.BlockStateBase::blocksMotion;
    private final BitStorage data;
    private final Predicate<BlockState> isOpaque;
    private final ChunkAccess chunk;

    public Heightmap(ChunkAccess chunkAccess, Types types) {
        this.isOpaque = types.isOpaque();
        this.chunk = chunkAccess;
        int i = Mth.ceillog2(chunkAccess.getHeight() + 1);
        this.data = new SimpleBitStorage(i, 256);
    }

    public static void primeHeightmaps(ChunkAccess chunkAccess, Set<Types> set) {
        int i = set.size();
        ObjectArrayList objectList = new ObjectArrayList(i);
        Iterator objectListIterator = objectList.iterator();
        int j = chunkAccess.getHighestSectionPosition() + 16;
        BlockPos.MutableBlockPos mutableBlockPos = new BlockPos.MutableBlockPos();
        for (int k = 0; k < 16; ++k) {
            block1: for (int l = 0; l < 16; ++l) {
                for (Types types : set) {
                    objectList.add(chunkAccess.getOrCreateHeightmapUnprimed(types));
                }
                for (int m = j - 1; m >= chunkAccess.getMinBuildHeight(); --m) {
                    mutableBlockPos.set(k, m, l);
                    BlockState blockState = chunkAccess.getBlockState(mutableBlockPos);
                    if (blockState.is(Blocks.AIR)) continue;
                    while (objectListIterator.hasNext()) {
                        Heightmap heightmap = (Heightmap)objectListIterator.next();
                        if (!heightmap.isOpaque.test(blockState)) continue;
                        heightmap.setHeight(k, l, m + 1);
                        objectListIterator.remove();
                    }
                    if (objectList.isEmpty()) continue block1;
                    objectListIterator.back(i);
                }
            }
        }
    }

    public boolean update(int i, int j, int k, BlockState blockState) {
        int l = this.getFirstAvailable(i, k);
        if (j <= l - 2) {
            return false;
        }
        if (this.isOpaque.test(blockState)) {
            if (j >= l) {
                this.setHeight(i, k, j + 1);
                return true;
            }
        } else if (l - 1 == j) {
            BlockPos.MutableBlockPos mutableBlockPos = new BlockPos.MutableBlockPos();
            for (int m = j - 1; m >= this.chunk.getMinBuildHeight(); --m) {
                mutableBlockPos.set(i, m, k);
                if (!this.isOpaque.test(this.chunk.getBlockState(mutableBlockPos))) continue;
                this.setHeight(i, k, m + 1);
                return true;
            }
            this.setHeight(i, k, this.chunk.getMinBuildHeight());
            return true;
        }
        return false;
    }

    public int getFirstAvailable(int i, int j) {
        return this.getFirstAvailable(Heightmap.getIndex(i, j));
    }

    public int getHighestTaken(int i, int j) {
        return this.getFirstAvailable(Heightmap.getIndex(i, j)) - 1;
    }

    private int getFirstAvailable(int i) {
        return this.data.get(i) + this.chunk.getMinBuildHeight();
    }

    private void setHeight(int i, int j, int k) {
        this.data.set(Heightmap.getIndex(i, j), k - this.chunk.getMinBuildHeight());
    }

    public void setRawData(ChunkAccess chunkAccess, Types types, long[] ls) {
        long[] ms = this.data.getRaw();
        if (ms.length == ls.length) {
            System.arraycopy(ls, 0, ms, 0, ls.length);
            return;
        }
        LOGGER.warn("Ignoring heightmap data for chunk " + String.valueOf(chunkAccess.getPos()) + ", size does not match; expected: " + ms.length + ", got: " + ls.length);
        Heightmap.primeHeightmaps(chunkAccess, EnumSet.of(types));
    }

    public long[] getRawData() {
        return this.data.getRaw();
    }

    private static int getIndex(int i, int j) {
        return i + j * 16;
    }

    public static enum Types implements StringRepresentable
    {
        WORLD_SURFACE_WG("WORLD_SURFACE_WG", Usage.WORLDGEN, NOT_AIR),
        WORLD_SURFACE("WORLD_SURFACE", Usage.CLIENT, NOT_AIR),
        OCEAN_FLOOR_WG("OCEAN_FLOOR_WG", Usage.WORLDGEN, MATERIAL_MOTION_BLOCKING),
        OCEAN_FLOOR("OCEAN_FLOOR", Usage.LIVE_WORLD, MATERIAL_MOTION_BLOCKING),
        MOTION_BLOCKING("MOTION_BLOCKING", Usage.CLIENT, blockState -> blockState.blocksMotion() || !blockState.getFluidState().isEmpty()),
        MOTION_BLOCKING_NO_LEAVES("MOTION_BLOCKING_NO_LEAVES", Usage.LIVE_WORLD, blockState -> (blockState.blocksMotion() || !blockState.getFluidState().isEmpty()) && !(blockState.getBlock() instanceof LeavesBlock));

        public static final Codec<Types> CODEC;
        private final String serializationKey;
        private final Usage usage;
        private final Predicate<BlockState> isOpaque;

        private Types(String string2, Usage usage, Predicate<BlockState> predicate) {
            this.serializationKey = string2;
            this.usage = usage;
            this.isOpaque = predicate;
        }

        public String getSerializationKey() {
            return this.serializationKey;
        }

        public boolean sendToClient() {
            return this.usage == Usage.CLIENT;
        }

        public boolean keepAfterWorldgen() {
            return this.usage != Usage.WORLDGEN;
        }

        public Predicate<BlockState> isOpaque() {
            return this.isOpaque;
        }

        @Override
        public String getSerializedName() {
            return this.serializationKey;
        }

        static {
            CODEC = StringRepresentable.fromEnum(Types::values);
        }
    }

    public static enum Usage {
        WORLDGEN,
        LIVE_WORLD,
        CLIENT;

    }
}
```

```
package net.minecraft.world.level.levelgen;

public interface Aquifer {
    public static Aquifer create(NoiseChunk noiseChunk, ChunkPos chunkPos, NoiseRouter noiseRouter, PositionalRandomFactory positionalRandomFactory, int i, int j, FluidPicker fluidPicker) {
        return new NoiseBasedAquifer(noiseChunk, chunkPos, noiseRouter, positionalRandomFactory, i, j, fluidPicker);
    }

    public static Aquifer createDisabled(final FluidPicker fluidPicker) {
        return new Aquifer(){

            @Override
            @Nullable
            public BlockState computeSubstance(DensityFunction.FunctionContext functionContext, double d) {
                if (d > 0.0) {
                    return null;
                }
                return fluidPicker.computeFluid(functionContext.blockX(), functionContext.blockY(), functionContext.blockZ()).at(functionContext.blockY());
            }

            @Override
            public boolean shouldScheduleFluidUpdate() {
                return false;
            }
        };
    }

    @Nullable
    public BlockState computeSubstance(DensityFunction.FunctionContext var1, double var2);

    public boolean shouldScheduleFluidUpdate();

    public static class NoiseBasedAquifer
    implements Aquifer {
        private static final int X_RANGE = 10;
        private static final int Y_RANGE = 9;
        private static final int Z_RANGE = 10;
        private static final int X_SEPARATION = 6;
        private static final int Y_SEPARATION = 3;
        private static final int Z_SEPARATION = 6;
        private static final int X_SPACING = 16;
        private static final int Y_SPACING = 12;
        private static final int Z_SPACING = 16;
        private static final int MAX_REASONABLE_DISTANCE_TO_AQUIFER_CENTER = 11;
        private static final double FLOWING_UPDATE_SIMULARITY = NoiseBasedAquifer.similarity(Mth.square(10), Mth.square(12));
        private final NoiseChunk noiseChunk;
        private final DensityFunction barrierNoise;
        private final DensityFunction fluidLevelFloodednessNoise;
        private final DensityFunction fluidLevelSpreadNoise;
        private final DensityFunction lavaNoise;
        private final PositionalRandomFactory positionalRandomFactory;
        private final FluidStatus[] aquiferCache;
        private final long[] aquiferLocationCache;
        private final FluidPicker globalFluidPicker;
        private final DensityFunction erosion;
        private final DensityFunction depth;
        private boolean shouldScheduleFluidUpdate;
        private final int minGridX;
        private final int minGridY;
        private final int minGridZ;
        private final int gridSizeX;
        private final int gridSizeZ;
        private static final int[][] SURFACE_SAMPLING_OFFSETS_IN_CHUNKS = new int[][]{{0, 0}, {-2, -1}, {-1, -1}, {0, -1}, {1, -1}, {-3, 0}, {-2, 0}, {-1, 0}, {1, 0}, {-2, 1}, {-1, 1}, {0, 1}, {1, 1}};

        NoiseBasedAquifer(NoiseChunk noiseChunk, ChunkPos chunkPos, NoiseRouter noiseRouter, PositionalRandomFactory positionalRandomFactory, int i, int j, FluidPicker fluidPicker) {
            this.noiseChunk = noiseChunk;
            this.barrierNoise = noiseRouter.barrierNoise();
            this.fluidLevelFloodednessNoise = noiseRouter.fluidLevelFloodednessNoise();
            this.fluidLevelSpreadNoise = noiseRouter.fluidLevelSpreadNoise();
            this.lavaNoise = noiseRouter.lavaNoise();
            this.erosion = noiseRouter.erosion();
            this.depth = noiseRouter.depth();
            this.positionalRandomFactory = positionalRandomFactory;
            this.minGridX = this.gridX(chunkPos.getMinBlockX()) - 1;
            this.globalFluidPicker = fluidPicker;
            int k = this.gridX(chunkPos.getMaxBlockX()) + 1;
            this.gridSizeX = k - this.minGridX + 1;
            this.minGridY = this.gridY(i) - 1;
            int l = this.gridY(i + j) + 1;
            int m = l - this.minGridY + 1;
            this.minGridZ = this.gridZ(chunkPos.getMinBlockZ()) - 1;
            int n = this.gridZ(chunkPos.getMaxBlockZ()) + 1;
            this.gridSizeZ = n - this.minGridZ + 1;
            int o = this.gridSizeX * m * this.gridSizeZ;
            this.aquiferCache = new FluidStatus[o];
            this.aquiferLocationCache = new long[o];
            Arrays.fill(this.aquiferLocationCache, Long.MAX_VALUE);
        }

        private int getIndex(int i, int j, int k) {
            int l = i - this.minGridX;
            int m = j - this.minGridY;
            int n = k - this.minGridZ;
            return (m * this.gridSizeZ + n) * this.gridSizeX + l;
        }

        @Override
        @Nullable
        public BlockState computeSubstance(DensityFunction.FunctionContext functionContext, double d) {
            double ah;
            double h;
            BlockState blockState;
            int i = functionContext.blockX();
            int j = functionContext.blockY();
            int k = functionContext.blockZ();
            if (d > 0.0) {
                this.shouldScheduleFluidUpdate = false;
                return null;
            }
            FluidStatus fluidStatus = this.globalFluidPicker.computeFluid(i, j, k);
            if (fluidStatus.at(j).is(Blocks.LAVA)) {
                this.shouldScheduleFluidUpdate = false;
                return Blocks.LAVA.defaultBlockState();
            }
            int l = Math.floorDiv(i - 5, 16);
            int m = Math.floorDiv(j + 1, 12);
            int n = Math.floorDiv(k - 5, 16);
            int o = Integer.MAX_VALUE;
            int p = Integer.MAX_VALUE;
            int q = Integer.MAX_VALUE;
            long r = 0L;
            long s = 0L;
            long t = 0L;
            for (int u = 0; u <= 1; ++u) {
                for (int v = -1; v <= 1; ++v) {
                    for (int w = 0; w <= 1; ++w) {
                        long ac;
                        int x = l + u;
                        int y = m + v;
                        int z = n + w;
                        int aa = this.getIndex(x, y, z);
                        long ab = this.aquiferLocationCache[aa];
                        if (ab != Long.MAX_VALUE) {
                            ac = ab;
                        } else {
                            RandomSource randomSource = this.positionalRandomFactory.at(x, y, z);
                            this.aquiferLocationCache[aa] = ac = BlockPos.asLong(x * 16 + randomSource.nextInt(10), y * 12 + randomSource.nextInt(9), z * 16 + randomSource.nextInt(10));
                        }
                        int ad = BlockPos.getX(ac) - i;
                        int ae = BlockPos.getY(ac) - j;
                        int af = BlockPos.getZ(ac) - k;
                        int ag = ad * ad + ae * ae + af * af;
                        if (o >= ag) {
                            t = s;
                            s = r;
                            r = ac;
                            q = p;
                            p = o;
                            o = ag;
                            continue;
                        }
                        if (p >= ag) {
                            t = s;
                            s = ac;
                            q = p;
                            p = ag;
                            continue;
                        }
                        if (q < ag) continue;
                        t = ac;
                        q = ag;
                    }
                }
            }
            FluidStatus fluidStatus2 = this.getAquiferStatus(r);
            double e = NoiseBasedAquifer.similarity(o, p);
            BlockState blockState2 = blockState = fluidStatus2.at(j);
            if (e <= 0.0) {
                this.shouldScheduleFluidUpdate = e >= FLOWING_UPDATE_SIMULARITY;
                return blockState2;
            }
            if (blockState.is(Blocks.WATER) && this.globalFluidPicker.computeFluid(i, j - 1, k).at(j - 1).is(Blocks.LAVA)) {
                this.shouldScheduleFluidUpdate = true;
                return blockState2;
            }
            MutableDouble mutableDouble = new MutableDouble(Double.NaN);
            FluidStatus fluidStatus3 = this.getAquiferStatus(s);
            double f = e * this.calculatePressure(functionContext, mutableDouble, fluidStatus2, fluidStatus3);
            if (d + f > 0.0) {
                this.shouldScheduleFluidUpdate = false;
                return null;
            }
            FluidStatus fluidStatus4 = this.getAquiferStatus(t);
            double g = NoiseBasedAquifer.similarity(o, q);
            if (g > 0.0 && d + (h = e * g * this.calculatePressure(functionContext, mutableDouble, fluidStatus2, fluidStatus4)) > 0.0) {
                this.shouldScheduleFluidUpdate = false;
                return null;
            }
            double h2 = NoiseBasedAquifer.similarity(p, q);
            if (h2 > 0.0 && d + (ah = e * h2 * this.calculatePressure(functionContext, mutableDouble, fluidStatus3, fluidStatus4)) > 0.0) {
                this.shouldScheduleFluidUpdate = false;
                return null;
            }
            this.shouldScheduleFluidUpdate = true;
            return blockState2;
        }

        @Override
        public boolean shouldScheduleFluidUpdate() {
            return this.shouldScheduleFluidUpdate;
        }

        private static double similarity(int i, int j) {
            double d = 25.0;
            return 1.0 - (double)Math.abs(j - i) / 25.0;
        }

        private double calculatePressure(DensityFunction.FunctionContext functionContext, MutableDouble mutableDouble, FluidStatus fluidStatus, FluidStatus fluidStatus2) {
            double r;
            double p;
            int i = functionContext.blockY();
            BlockState blockState = fluidStatus.at(i);
            BlockState blockState2 = fluidStatus2.at(i);
            if (blockState.is(Blocks.LAVA) && blockState2.is(Blocks.WATER) || blockState.is(Blocks.WATER) && blockState2.is(Blocks.LAVA)) {
                return 2.0;
            }
            int j = Math.abs(fluidStatus.fluidLevel - fluidStatus2.fluidLevel);
            if (j == 0) {
                return 0.0;
            }
            double d = 0.5 * (double)(fluidStatus.fluidLevel + fluidStatus2.fluidLevel);
            double e = (double)i + 0.5 - d;
            double f = (double)j / 2.0;
            double g = 0.0;
            double h = 2.5;
            double k = 1.5;
            double l = 3.0;
            double m = 10.0;
            double n = 3.0;
            double o = f - Math.abs(e);
            double q = e > 0.0 ? ((p = 0.0 + o) > 0.0 ? p / 1.5 : p / 2.5) : ((p = 3.0 + o) > 0.0 ? p / 3.0 : p / 10.0);
            p = 2.0;
            if (q < -2.0 || q > 2.0) {
                r = 0.0;
            } else {
                double s = mutableDouble.getValue();
                if (Double.isNaN(s)) {
                    double t = this.barrierNoise.compute(functionContext);
                    mutableDouble.setValue(t);
                    r = t;
                } else {
                    r = s;
                }
            }
            return 2.0 * (r + q);
        }

        private int gridX(int i) {
            return Math.floorDiv(i, 16);
        }

        private int gridY(int i) {
            return Math.floorDiv(i, 12);
        }

        private int gridZ(int i) {
            return Math.floorDiv(i, 16);
        }

        private FluidStatus getAquiferStatus(long l) {
            FluidStatus fluidStatus2;
            int o;
            int n;
            int i = BlockPos.getX(l);
            int j = BlockPos.getY(l);
            int k = BlockPos.getZ(l);
            int m = this.gridX(i);
            int p = this.getIndex(m, n = this.gridY(j), o = this.gridZ(k));
            FluidStatus fluidStatus = this.aquiferCache[p];
            if (fluidStatus != null) {
                return fluidStatus;
            }
            this.aquiferCache[p] = fluidStatus2 = this.computeFluid(i, j, k);
            return fluidStatus2;
        }

        private FluidStatus computeFluid(int i, int j, int k) {
            FluidStatus fluidStatus = this.globalFluidPicker.computeFluid(i, j, k);
            int l = Integer.MAX_VALUE;
            int m = j + 12;
            int n = j - 12;
            boolean bl = false;
            for (int[] is : SURFACE_SAMPLING_OFFSETS_IN_CHUNKS) {
                FluidStatus fluidStatus2;
                boolean bl3;
                boolean bl2;
                int o = i + SectionPos.sectionToBlockCoord(is[0]);
                int p = k + SectionPos.sectionToBlockCoord(is[1]);
                int q = this.noiseChunk.preliminarySurfaceLevel(o, p);
                int r = q + 8;
                boolean bl4 = bl2 = is[0] == 0 && is[1] == 0;
                if (bl2 && n > r) {
                    return fluidStatus;
                }
                boolean bl5 = bl3 = m > r;
                if ((bl3 || bl2) && !(fluidStatus2 = this.globalFluidPicker.computeFluid(o, r, p)).at(r).isAir()) {
                    if (bl2) {
                        bl = true;
                    }
                    if (bl3) {
                        return fluidStatus2;
                    }
                }
                l = Math.min(l, q);
            }
            int s = this.computeSurfaceLevel(i, j, k, fluidStatus, l, bl);
            return new FluidStatus(s, this.computeFluidType(i, j, k, fluidStatus, s));
        }

        private int computeSurfaceLevel(int i, int j, int k, FluidStatus fluidStatus, int l, boolean bl) {
            int m;
            double e;
            double d;
            DensityFunction.SinglePointContext singlePointContext = new DensityFunction.SinglePointContext(i, j, k);
            if (OverworldBiomeBuilder.isDeepDarkRegion(this.erosion, this.depth, singlePointContext)) {
                d = -1.0;
                e = -1.0;
            } else {
                m = l + 8 - j;
                int n = 64;
                double f = bl ? Mth.clampedMap((double)m, 0.0, 64.0, 1.0, 0.0) : 0.0;
                double g = Mth.clamp(this.fluidLevelFloodednessNoise.compute(singlePointContext), -1.0, 1.0);
                double h = Mth.map(f, 1.0, 0.0, -0.3, 0.8);
                double o = Mth.map(f, 1.0, 0.0, -0.8, 0.4);
                d = g - o;
                e = g - h;
            }
            m = e > 0.0 ? fluidStatus.fluidLevel : (d > 0.0 ? this.computeRandomizedFluidSurfaceLevel(i, j, k, l) : DimensionType.WAY_BELOW_MIN_Y);
            return m;
        }

        private int computeRandomizedFluidSurfaceLevel(int i, int j, int k, int l) {
            int m = 16;
            int n = 40;
            int o = Math.floorDiv(i, 16);
            int p = Math.floorDiv(j, 40);
            int q = Math.floorDiv(k, 16);
            int r = p * 40 + 20;
            int s = 10;
            double d = this.fluidLevelSpreadNoise.compute(new DensityFunction.SinglePointContext(o, p, q)) * 10.0;
            int t = Mth.quantize(d, 3);
            int u = r + t;
            return Math.min(l, u);
        }

        private BlockState computeFluidType(int i, int j, int k, FluidStatus fluidStatus, int l) {
            BlockState blockState = fluidStatus.fluidType;
            if (l <= -10 && l != DimensionType.WAY_BELOW_MIN_Y && fluidStatus.fluidType != Blocks.LAVA.defaultBlockState()) {
                int q;
                int p;
                int m = 64;
                int n = 40;
                int o = Math.floorDiv(i, 64);
                double d = this.lavaNoise.compute(new DensityFunction.SinglePointContext(o, p = Math.floorDiv(j, 40), q = Math.floorDiv(k, 64)));
                if (Math.abs(d) > 0.3) {
                    blockState = Blocks.LAVA.defaultBlockState();
                }
            }
            return blockState;
        }
    }

    public static interface FluidPicker {
        public FluidStatus computeFluid(int var1, int var2, int var3);
    }

    public static final class FluidStatus {
        final int fluidLevel;
        final BlockState fluidType;

        public FluidStatus(int i, BlockState blockState) {
            this.fluidLevel = i;
            this.fluidType = blockState;
        }

        public BlockState at(int i) {
            return i < this.fluidLevel ? this.fluidType : Blocks.AIR.defaultBlockState();
        }
    }
}
```