package dev.sixik.snoisium.mixin;

import com.google.common.collect.Lists;
import dev.sixik.snoisium.density.DoDCacheAllInCell;
import dev.sixik.snoisium.density.DoDCacheOnce;
import dev.sixik.snoisium.density.Flat3DNoiseInterpolator;
import dev.sixik.snoisium.density_patch.NoiseChunkPatch;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(NoiseChunk.class)
public class NoiseChunkMixin implements NoiseChunkPatch {

    @Shadow
    public int cellStartBlockX;
    @Shadow
    public int inCellX;
    @Shadow
    public int cellStartBlockY;
    @Shadow
    public int inCellY;
    @Shadow
    public int cellStartBlockZ;
    @Shadow
    public int inCellZ;
    final List<Flat3DNoiseInterpolator> flatInterpolators = Lists.newArrayList();

    @Redirect(method = "wrapNew", at = @At(value = "NEW", target = "(Lnet/minecraft/world/level/levelgen/NoiseChunk;Lnet/minecraft/world/level/levelgen/DensityFunction;)Lnet/minecraft/world/level/levelgen/NoiseChunk$NoiseInterpolator;"))
    public NoiseChunk.NoiseInterpolator bts$wrapNew$null(NoiseChunk arg, DensityFunction arg2) {
        return null;
    }

    @Inject(method = "wrapNew", at = @At("HEAD"), cancellable = true)
    public void bts$wrapNew(DensityFunction densityFunction, CallbackInfoReturnable<DensityFunction> cir) {
        if (densityFunction instanceof DensityFunctions.Marker(
                DensityFunctions.Marker.Type type, DensityFunction wrapped
        )) {

            switch (type) {
                case Interpolated -> {
                    Flat3DNoiseInterpolator flat = new Flat3DNoiseInterpolator(wrapped, (NoiseChunk)(Object)this);
                    this.flatInterpolators.add(flat);
                    cir.setReturnValue(flat);
                }
                case CacheOnce -> cir.setReturnValue(new DoDCacheOnce(wrapped));
                case CacheAllInCell -> cir.setReturnValue(new DoDCacheAllInCell(wrapped));
                default -> {}
            }

        }

    }

    @Override
    public void prepareAllGrids() {
        for (Flat3DNoiseInterpolator flat : this.flatInterpolators) {
            flat.fillGrid();
        }
    }

    @Override
    public void setBlockPos(int x, int y, int z) {
        this.cellStartBlockX = x; // Переиспользуем поля как абсолютные координаты
        this.inCellX = 0;
        this.cellStartBlockY = y;
        this.inCellY = 0;
        this.cellStartBlockZ = z;
        this.inCellZ = 0;
    }
}
