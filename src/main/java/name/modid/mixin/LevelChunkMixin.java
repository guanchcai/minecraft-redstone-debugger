package name.modid.mixin;

import name.modid.RedstoneDebugger;
import name.modid.venv.VenvManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelChunk.class)
abstract class LevelChunkMixin {
    // Records every block state change for step back capability
    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void rdbSetBlockState(BlockPos pos, BlockState state, int flags, CallbackInfoReturnable<BlockState> cir) {
        BlockState old = cir.getReturnValue();
        if (old == null) {
            // Nothing changed
            return;
        }
        Level level = ((LevelChunk) (Object) this).getLevel();
        if (!VenvManager.isVenv(level) || !(level instanceof ServerLevel serverLevel)) {
            // Not in debugger
            return;
        }
        RedstoneDebugger.CONTROLLER.recordBlockChange(serverLevel, pos.immutable(), old, state);
    }
}