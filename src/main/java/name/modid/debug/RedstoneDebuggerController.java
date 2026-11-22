package name.modid.debug;

import name.modid.venv.ContraptionSnapshot;
import name.modid.venv.VenvManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Position;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockEventData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.redstone.Orientation;
import org.jetbrains.annotations.Nullable;

import java.util.*;

public class RedstoneDebuggerController {
    enum DebugState {
        OFF,
        PAUSED,
        RUNNING
    }

    private DebugState debugState = DebugState.OFF;
    private int tpt = 1; // ticks per redstone tick;
    private int slowTicks = 0;
    private int stepTicksRemaining = 0;
    private boolean frozen = false;
    private long ticksHeld;
    private @Nullable BoundingBox extents = null;

    public long toRedstoneTime(Level level, long gameTime) {
        return VenvManager.isVenv(level) ? gameTime - ticksHeld : gameTime;
    }

    // ----- Core functions -----
    /** Starts debugging with tick slowdown rate speed */
    public void startDebug() {
        tpt = 1;
        debugState = DebugState.RUNNING;
        clearSnapshots();
    }

    public void pause() {
        if (isPaused()) {
            // already paused
            return;
        }
        debugState = DebugState.PAUSED;
    }

    public void resume() {
        if (isPaused()) {
            debugState = DebugState.RUNNING;
        } else {
            // It is not paused, TODO: Throw a warning?
        }
    }

    public void step(int n) {
        stepTicksRemaining += n;
    }

    // ----- Private field queries -----
    public boolean isPaused() {
        return debugState == DebugState.PAUSED;
    }

    /** True on game ticks where redstone must not run (paused, or a skipped tick in slow mode). Decided in onServerTickStart. */
    public boolean isFrozenIn(Level level) {
        return frozen && VenvManager.isVenv(level);
    }

    public boolean isRunning() {
        return debugState == DebugState.RUNNING;
    }

    public String getState() {
        return switch (debugState) {
            case OFF -> "off";
            case PAUSED -> "paused";
            case RUNNING -> "running, current ticks per tick: " + tpt;
        };
    }

    // ----- Held neighbour updates -----

    /** Everything needed to replay one neighbour update later. */
    private record HeldUpdate(BlockPos pos, Block sourceBlock, Orientation orientation, boolean movedByPiston) {}

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
    // ----- Snapshot for stepping back -----

    record TickSnapshot(BlockPos origin, ContraptionSnapshot worldSnapshot, Set<HeldUpdate> heldUpdates, List<BlockEventData> blockEventDataList) {}

    private final Deque<TickSnapshot> snapshots = new ArrayDeque<>();
    public void takeSnapshot(ServerLevel venv) {
        snapshots.addLast(
                new TickSnapshot(extents.getCenter(), ContraptionSnapshot.capture(venv, extents),
                        new LinkedHashSet<>(heldUpdates.getOrDefault(venv, Set.of())),
                        List.of())
        );
    }

    /** The area to snapshot. */
    public @Nullable BoundingBox getExtents(ServerLevel venv) {
        if (extents == null) {
            BoundingBox box = VenvManager.currentBox(venv.getServer());
            if (box == null) {
                return null;
            }
            // our own copy, because encapsulate() changes the box it's called on
            extents = new BoundingBox(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ());
        }
        return extents;
    }

    public void clearSnapshots() {
        snapshots.clear();
        extents = null;
    }

    // ----- Functions hooked to minecraft functions -----

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

    /**
     * Clears all debugger state when a server (world) starts. Called from RedstoneDebugger on SERVER_STARTING.
     * Not related to {@code /rdb reset}, which restores the VENV contraption (see VenvManager.reset).
     */
    public void onServerStarting() {
        ticksHeld = 0;
        stepTicksRemaining = 0;
        frozen = false;
        debugState = DebugState.OFF;
        heldUpdates.clear();
        slowTicks = 0;
        extents = null;
    }

    public void onServerStopped() {
        debugState = DebugState.OFF;
    }
}
