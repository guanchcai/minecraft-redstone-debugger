package name.modid.mixin;

import name.modid.RedstoneDebugger;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.redstone.Orientation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Every neighbour update ("a block next to you changed") passes through handleNeighborChanged
 * before reaching the block's own neighborChanged(). Redstone dust, lamps, doors, pistons and
 * our IndicatorBlock all react through it. On frozen ticks (paused, or skipped in slow mode),
 * save the update instead of running it.
 */
@Mixin(BlockBehaviour.BlockStateBase.class)
abstract class BlockStateBaseMixin {
	@Inject(at = @At("HEAD"), method = "handleNeighborChanged", cancellable = true)
	private void rdbHoldNeighborUpdate(Level level, BlockPos pos, Block sourceBlock, Orientation orientation,
									   boolean movedByPiston, CallbackInfo info) {
		// Only on the server: the client runs this method too, but the server decides what redstone does
		if (level instanceof ServerLevel serverLevel && RedstoneDebugger.CONTROLLER.isFrozen()) {
			RedstoneDebugger.CONTROLLER.holdNeighborUpdate(serverLevel, pos, sourceBlock, orientation, movedByPiston);
			info.cancel(); // don't run it now; releaseHeldUpdates replays it later
		}
	}
}
