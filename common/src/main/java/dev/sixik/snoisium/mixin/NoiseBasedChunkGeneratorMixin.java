package dev.sixik.snoisium.mixin;

import dev.sixik.snoisium.density_patch.NoiseChunkPatch;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(NoiseBasedChunkGenerator.class)
public abstract class NoiseBasedChunkGeneratorMixin {


    @Shadow
    protected abstract NoiseChunk createNoiseChunk(ChunkAccess chunkAccess, StructureManager structureManager, Blender blender, RandomState randomState);

    @Shadow
    @Final
    private Holder<NoiseGeneratorSettings> settings;

    /**
     * @author Sixik
     * @reason
     */
    @Overwrite
    public final ChunkAccess doFill(Blender blender, StructureManager structureManager, RandomState randomState, ChunkAccess chunkAccess2, int i, int j) {
        NoiseChunk noiseChunk = chunkAccess2.getOrCreateNoiseChunk(chunkAccess -> this.createNoiseChunk((ChunkAccess)chunkAccess, structureManager, blender, randomState));
        Heightmap heightmap = chunkAccess2.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
        Heightmap heightmap2 = chunkAccess2.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        ChunkPos chunkPos = chunkAccess2.getPos();
        int startX = chunkPos.getMinBlockX();
        int startZ = chunkPos.getMinBlockZ();

        NoiseChunkPatch noiseChunkPatch = (NoiseChunkPatch)noiseChunk;

        Aquifer aquifer = noiseChunk.aquifer();

        // 1. Предрасчет плоских 3D-массивов интерполяции (вызывается 1 раз на чанк!)
        noiseChunkPatch.prepareAllGrids();

        BlockPos.MutableBlockPos mutableBlockPos = new BlockPos.MutableBlockPos();
        int minY = i * noiseChunk.cellHeight();
        int maxY = (i + j) * noiseChunk.cellHeight();

        // 2. Линейный DoD-обход без стейтфул-итераторов
        for (int localX = 0; localX < 16; ++localX) {
            int absoluteX = startX + localX;

            for (int localZ = 0; localZ < 16; ++localZ) {
                int absoluteZ = startZ + localZ;

                // Идем сверху вниз (как в оригинале для оптимизации освещения/высот)
                for (int absoluteY = maxY - 1; absoluteY >= minY; --absoluteY) {

                    // Обновляем абсолютные координаты в контексте
                    noiseChunkPatch.setBlockPos(absoluteX, absoluteY, absoluteZ);

                    // Вычисляем финальный стейт (внутри вызовется наш Mth.lerp3 из плоского кэша)
                    BlockState blockState = noiseChunk.getInterpolatedState();
                    if (blockState == null) {
                        blockState = this.settings.value().defaultBlock();
                    }

                    if (blockState.isAir() || SharedConstants.debugVoidTerrain(chunkAccess2.getPos())) continue;

                    // Обычная логика установки блока ванилы
                    int sectionIndex = chunkAccess2.getSectionIndex(absoluteY);
                    LevelChunkSection section = chunkAccess2.getSection(sectionIndex);
                    section.setBlockState(localX, absoluteY & 15, localZ, blockState, false);

                    heightmap.update(localX, absoluteY, localZ, blockState);
                    heightmap2.update(localX, absoluteY, localZ, blockState);

                    if (aquifer.shouldScheduleFluidUpdate() && !blockState.getFluidState().isEmpty()) {
                        mutableBlockPos.set(absoluteX, absoluteY, absoluteZ);
                        chunkAccess2.markPosForPostprocessing(mutableBlockPos);
                    }
                }
            }
        }
        return chunkAccess2;
    }
}
