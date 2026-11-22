package name.modid.mixin;

import name.modid.RedstoneDebugger;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.ticks.ScheduledTick;
import net.minecraft.world.ticks.TickPriority;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;

@Mixin(ServerLevel.class)
abstract class ServerLevelMixin {
	@Inject(at = @At("HEAD"), method = "runBlockEvents", cancellable = true)
	private void rdbInit(CallbackInfo info) {
		if (RedstoneDebugger.CONTROLLER.isFrozenIn((ServerLevel) (Object)this)) {
			info.cancel();
		}
	}

	// Start of each level tick: if redstone runs this tick, replay the neighbour updates held while frozen
	@Inject(at = @At("HEAD"), method = "tick(Ljava/util/function/BooleanSupplier;)V")
	private void rdbReleaseHeldUpdates(BooleanSupplier haveTime, CallbackInfo info) {
		if (!RedstoneDebugger.CONTROLLER.isFrozenIn((ServerLevel) (Object)this)) {
			// Java thinks `this` is a ServerLevelMixin, but at runtime it's the real ServerLevel.
			// Casting through Object first is the standard mixin trick to tell the compiler that.
			RedstoneDebugger.CONTROLLER.releaseHeldUpdates((ServerLevel) (Object) this);
		}
	}

	@Inject (method = "tickTime()V", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/level/storage/ServerLevelData;setGameTime(J)V",
			shift = At.Shift.AFTER))
	private void rdbTickTime(CallbackInfo info) {
		if (((ServerLevel) (Object) this).dimension() == Level.OVERWORLD) {
			RedstoneDebugger.CONTROLLER.onGameTimeAdvanced();
		}
	}

	// Hook 2: RUN scheduled block ticks on redstone time.
	// Inside tick(), Minecraft calls blockTicks.tick(gameTime, 65536, ...) meaning
	// "run every scheduled block tick that is due at or before gameTime".
	// @ModifyArg swaps one argument of that call: we hand it redstone time instead.
	//   ordinal = 0 -> the 1st LevelTicks.tick(...) call in tick() is blockTicks (the 2nd is fluidTicks, left alone)
	//   index = 0   -> the 1st argument of that call (the time)
	@ModifyArg(method = "tick(Ljava/util/function/BooleanSupplier;)V",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/world/ticks/LevelTicks;tick(JILjava/util/function/BiConsumer;)V", ordinal = 0),
			index = 0)
	private long rdbBlockTicksUseRedstoneTime(long gameTime) {
		return RedstoneDebugger.CONTROLLER.toRedstoneTime((ServerLevel) (Object)this, gameTime);
	}

	// Hook 3: SCHEDULE new ticks on redstone time.
	// Every "tick me again in N ticks" (button release, repeater delay...) is created by this method,
	// which works out the due time as "now + delay". Normally ServerLevel inherits it from the
	// LevelAccessor interface, and Mixin can't inject into interface methods. So instead we ADD this
	// method to ServerLevel: a normal method in a mixin is copied into the target class, and since it
	// has exactly the same name and parameters, it replaces (overrides) the interface's version.
	// (That's why this one has no "rdb" prefix and is public: it must match exactly to override.)
	//
	// There are TWO versions of createTick, and vanilla builds the tick separately in each (the short one
	// does NOT call the long one), so both need overriding:
	//   createTick(pos, type, delay)           <- scheduleTick(pos, block, delay): buttons, torches, most blocks
	//   createTick(pos, type, delay, priority) <- scheduleTick(pos, block, delay, priority): e.g. repeaters
	public <T> ScheduledTick<T> createTick(BlockPos pos, T type, int delay) {
		ServerLevel self = (ServerLevel) (Object) this;
		// Same as vanilla, except for our "now". This constructor uses the default priority (NORMAL).
		return new ScheduledTick<>(type, pos, rdbNow(type) + delay, self.nextSubTickCount());
	}

	public <T> ScheduledTick<T> createTick(BlockPos pos, T type, int delay, TickPriority priority) {
		ServerLevel self = (ServerLevel) (Object) this;
		// Same as vanilla, except for our "now"
		return new ScheduledTick<>(type, pos, rdbNow(type) + delay, priority, self.nextSubTickCount());
	}

	// "Now" for a new scheduled tick. type is the Block (or Fluid) asking to be ticked. Only block ticks use
	// redstone time; fluid ticks keep using game time, so water and lava still flow while paused.
	private long rdbNow(Object type) {
		long gameTime = ((ServerLevel) (Object) this).getGameTime();
		return type instanceof Block ? RedstoneDebugger.CONTROLLER.toRedstoneTime((ServerLevel) (Object)this, gameTime) : gameTime;
	}
}