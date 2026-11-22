package name.modid.debug;

import name.modid.venv.ContraptionSnapshot;
import name.modid.venv.VenvManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Position;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockEventData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
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
    private boolean restoring = false;

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

    public ErrorCode pause() {
        if (restoring) {
            return ErrorCode.BUSY;
        }
        if (isPaused()) {
            // already paused
            return ErrorCode.REPEATED_OP;
        }
        debugState = DebugState.PAUSED;
        return ErrorCode.SUCCESS;
    }

    public ErrorCode resume() {
        if (restoring) {
            return ErrorCode.BUSY;
        }
        if (!isPaused()) {
            return ErrorCode.REPEATED_OP;
        }
        debugState = DebugState.RUNNING;
        return ErrorCode.SUCCESS;
    }

    public void step(int n) {
        stepTicksRemaining += n;
    }

    /** Slow mode: redstone runs on 1 game tick out of every {@code ticksPerTick} (1 = normal speed). */
    public void setSpeed(int ticksPerTick) {
        tpt = ticksPerTick;
        slowTicks = 0; // restart the count so the new speed takes effect cleanly
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

    // TEMP debug for step-back recording: delete once /rdb back works
    public String getRecordingDebug() {
        TickRecord last = records.peekLast();
        if (last == null) {
            return "no tick records yet";
        }
        int totalChanges = records.stream().mapToInt(r -> r.changes().size()).sum();
        StringBuilder text = new StringBuilder()
                .append(records.size()).append(" tick records, ").append(totalChanges)
                .append(" block changes in total, extents ").append(extents);

        // Most recent tick that changed something (the very last tick is usually empty)
        int ticksAgo = 0;
        Iterator<TickRecord> newestFirst = records.descendingIterator();
        TickRecord shown = null;
        while (newestFirst.hasNext()) {
            TickRecord record = newestFirst.next();
            if (!record.changes().isEmpty()) {
                shown = record;
                break;
            }
            ticksAgo++;
        }
        if (shown == null) {
            return text.append("\nno tick has changed any block yet").toString();
        }
        text.append("\nlast change ").append(ticksAgo).append(" ticks ago: ")
                .append(shown.changes().size()).append(" block changes, ")
                .append(shown.held().size()).append(" held updates");
        // the first few changes, e.g. "1 64 2: redstone_wire[power=0] -> redstone_wire[power=15]"
        for (BlockChange change : shown.changes().stream().limit(5).toList()) {
            text.append("\n  ").append(change.pos().toShortString()).append(": ")
                    .append(shortState(change.oldState())).append(" -> ").append(shortState(change.newState()));
        }
        return text.toString();
    }

    // TEMP: "minecraft:redstone_wire[...power=15...]" -> "redstone_wire[...power=15...]"
    private static String shortState(BlockState state) {
        return state.toString().replace("Block{minecraft:", "").replace("}", "");
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

    record BlockChange(BlockPos pos, BlockState oldState, BlockState newState) {}
    record TickRecord(List<BlockChange> changes, Set<HeldUpdate> held /* + ticks, events later */) {}
    private final Deque<TickRecord> records = new ArrayDeque<>();
    private static final int MAX_RECORDS = 20000;

    public void recordBlockChange(ServerLevel venv, BlockPos pos, BlockState oldState, BlockState newState) {
        if (restoring) {
            // The change is due to us restoring
            return;
        }
        if (records.isEmpty()) {
            // This skips changes made by initialising
            return;
        }
        records.peekLast().changes().add(new BlockChange(pos, oldState, newState));
        BoundingBox box = getExtents(venv);
        if (box != null && !box.isInside(pos)) {
            extents = BoundingBox.encapsulating(box, new BoundingBox(pos));
        }
    }

    public void startTickRecord(ServerLevel venv) {
        records.addLast(new TickRecord(new ArrayList<>(),   // must be mutable: changes get added to it
                new LinkedHashSet<>(heldUpdates.getOrDefault(venv, Set.of()))));
        while (records.size() > MAX_RECORDS) records.removeFirst();
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
        records.clear();
        extents = null;
        restoring = false;
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
        restoring = false;
    }

    public void onServerStopped() {
        debugState = DebugState.OFF;
        restoring = false;
    }
}
