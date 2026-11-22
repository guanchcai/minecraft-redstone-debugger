package name.modid.mixin;

import name.modid.RedstoneDebugger;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PistonMovingBlockEntity.class)
abstract class PistonMovingBlockEntityMixin {
    @Inject(at = @At("HEAD"), method = "tick", cancellable = true)
    private static void rdbHoldMovingPistons(Level level, BlockPos pos, BlockState state, PistonMovingBlockEntity entity, CallbackInfo info) {
        if (!level.isClientSide()) {
            if (RedstoneDebugger.CONTROLLER.isFrozenIn(level)) {
                info.cancel();
            }
        }
    }
}
