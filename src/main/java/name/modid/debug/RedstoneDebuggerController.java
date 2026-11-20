package name.modid.debug;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.redstone.Orientation;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public class RedstoneDebuggerController {
    enum DebugState {
        OFF,
        PAUSED,
        RUNNING
    }

    private DebugState debugState = DebugState.OFF;
    private int tpt = 2; // ticks per redstone tick;
    private int slowTicks = 0;
    private int stepTicksRemaining = 0;
    private boolean frozen = false;
    private DebugState previousState;
    private long ticksHeld;

    public long toRedstoneTime(long gameTime) {
        return gameTime - ticksHeld;
    }

    public void start(int speed) {
        previousState = DebugState.RUNNING;
        tpt = speed;
        if (!isOff()) {
            return;
        }
        debugState = DebugState.RUNNING;
    }

    public void stop() {
        previousState = DebugState.OFF;
        if (!isRunning()) {
            return;
        }
        debugState = DebugState.OFF;
    }

    public void pause() {
        if (isPaused()) {
            // already paused
            return;
        }
        previousState = debugState;
        debugState = DebugState.PAUSED;
    }

    public void resume() {
        if (isPaused()) {
            debugState = previousState;
        } else {
            // It is not paused, TODO: Throw a warning?
        }
    }

    public void step(int n) {
        stepTicksRemaining += n;
    }

    public boolean isPaused() {
        return debugState == DebugState.PAUSED;
    }

    /** True on game ticks where redstone must not run (paused, or a skipped tick in slow mode). Decided in onServerTickStart. */
    public boolean isFrozen() {
        return frozen;
    }

    public boolean isOff() {
        return debugState == DebugState.OFF;
    }

    public boolean isRunning() {
        return debugState == DebugState.RUNNING;
    }

    public String getState() {
        return switch (debugState) {
            case OFF -> "off";
            case PAUSED -> "paused";
            case RUNNING -> "running, current ticks per redstone tick: " + tpt;
        };
    }

    // ----- Held neighbour updates -----

    /** Everything needed to replay one neighbour update later. */
    private record HeldUpdate(BlockPos pos, Block sourceBlock, Orientation orientation, boolean movedByPiston) {
    }

    // Held updates per dimension (overworld, nether, end).
    // A LinkedHashSet ignores duplicates (spamming one block adds nothing new) and keeps first-added order.
    private final Map<ServerLevel, Set<HeldUpdate>> heldUpdates = new HashMap<>();

    /** Called from BlockStateBaseMixin, instead of running a neighbour update, while paused. */
    public void holdNeighborUpdate(ServerLevel level, BlockPos pos, Block sourceBlock,
                                   Orientation orientation, boolean movedByPiston) {
        heldUpdates.computeIfAbsent(level, l -> new LinkedHashSet<>())
                // immutable(): Minecraft often passes one mutable BlockPos that it keeps changing, so store a copy
                .add(new HeldUpdate(pos.immutable(), sourceBlock, orientation, movedByPiston));
    }

    /** Called from ServerLevelMixin at the start of each tick while not paused. Replays held updates in order. */
    public void releaseHeldUpdates(ServerLevel level) {
        Set<HeldUpdate> updates = heldUpdates.remove(level);
        if (updates == null) {
            return; // nothing held for this dimension
        }
        for (HeldUpdate update : updates) {
            if (level.isLoaded(update.pos())) { // don't force-load chunks that were unloaded meanwhile
                // Use the block that is there *now*, so it reacts to the current world
                level.neighborChanged(level.getBlockState(update.pos()), update.pos(), update.sourceBlock(),
                        update.orientation(), update.movedByPiston());
            }
        }
    }

    public void onGameTimeAdvanced() {
        if (frozen) {
            ticksHeld += 1;
        }
    }

    public void onServerTickStart() {
        if (stepTicksRemaining > 0) {
            frozen = false;
            stepTicksRemaining -= 1;
            return;
        }
        if (isRunning()) {
            slowTicks += 1;
            frozen = slowTicks < tpt;
            slowTicks %= tpt;
        } else if (isPaused()) {
            frozen = true;
        } else {
            frozen = false;
        }
    }
}
