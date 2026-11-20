package name.modid.venv;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.ticks.*;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * An immutable copy of all redstone-affected objects within a box (block states,
 * block entity data, pending scheduled ticks).
 * Positions are relative to the box's minimum corner.
 */
public final class ContraptionSnapshot {
  private static final Logger LOGGER = LogUtils.getLogger();

  /** Flags for placing blocks without anything reacting */
  private static final int SILENT_PLACE = Block.UPDATE_CLIENTS // show change to players
      | Block.UPDATE_KNOWN_SHAPE                   // don't reshape neighbours
      | Block.UPDATE_SUPPRESS_DROPS                // replaced blocks don't drop
      | Block.UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS // removed containers don't spill items
      | Block.UPDATE_SKIP_ON_PLACE;                // suppress onPlace (no new ticks, no recalc)

  /** One block in the snapshot */
  private record BlockEntry(
      BlockPos offset,
      BlockState state,
      @Nullable CompoundTag blockEntityTag,
      DataComponentMap blockEntityComponents
  ) {
  }

  /** One pending scheduled tick */
  private record TickEntry<T>(
      BlockPos offset,
      T type,
      long delay, // ticks relative to capture time
      TickPriority priority
  ) {
  }

  private final int xSpan;
  private final int ySpan;
  private final int zSpan;
  private final List<BlockEntry> blocks;
  private final List<TickEntry<Block>> blockTicks;
  private final List<TickEntry<Fluid>> fluidTicks;

  private ContraptionSnapshot(
      int xSpan, int ySpan, int zSpan,
      List<BlockEntry> blocks,
      List<TickEntry<Block>> blockTicks,
      List<TickEntry<Fluid>> fluidTicks
  ) {
    this.xSpan = xSpan;
    this.ySpan = ySpan;
    this.zSpan = zSpan;
    this.blocks = List.copyOf(blocks);
    this.blockTicks = List.copyOf(blockTicks);
    this.fluidTicks = List.copyOf(fluidTicks);
  }

  /** Copies the contents of {@code box} in {@code level}. The box must be loaded. */
  public static ContraptionSnapshot capture(ServerLevel level, BoundingBox box) {
    BlockPos min = new BlockPos(box.minX(), box.minY(), box.minZ()); // origin of relative coords
    BlockPos max = new BlockPos(box.maxX(), box.maxY(), box.maxZ());
    List<BlockEntry> blocks = new ArrayList<>(box.getXSpan() * box.getYSpan() * box.getZSpan());

    // logs errs from block entities that failed to save
    try (ProblemReporter.ScopedCollector reporter = new ProblemReporter.ScopedCollector(LOGGER)) {
      for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
        BlockState state = level.getBlockState(pos);
        BlockEntity blockEntity = level.getBlockEntity(pos);

        CompoundTag tag = null;                   // stays null if there is no block entity
        DataComponentMap components = DataComponentMap.EMPTY;
        if (blockEntity != null) {
          TagValueOutput output = TagValueOutput.createWithContext(
              reporter.forChild(blockEntity.problemPath()), // err msg for failed block
              level.registryAccess()                        // needed to save item/block ID
          );
          blockEntity.saveCustomOnly(output);
          tag = output.buildResult();             // NBT format
          components = blockEntity.components();
        }
        blocks.add(new BlockEntry(pos.subtract(min), state, tag, components));
      }
    }

    return new ContraptionSnapshot(
        box.getXSpan(), box.getYSpan(), box.getZSpan(), blocks,
        captureTicks(level, box, min, LevelChunk::getBlockTicks),
        captureTicks(level, box, min, LevelChunk::getFluidTicks)
    );
  }

  /** Collects pending ticks inside {@code box}, converted to relative delays. */
  private static <T> List<TickEntry<T>> captureTicks(
      ServerLevel level, BoundingBox box, BlockPos min,
      Function<LevelChunk, TickContainerAccess<T>> getter
  ) {
    // visit every touched chunk
    List<ScheduledTick<T>> found = new ArrayList<>();
    int minChunkX = SectionPos.blockToSectionCoord(box.minX());
    int maxChunkX = SectionPos.blockToSectionCoord(box.maxX());
    int minChunkZ = SectionPos.blockToSectionCoord(box.minZ());
    int maxChunkZ = SectionPos.blockToSectionCoord(box.maxZ());
    for (int cx = minChunkX; cx <= maxChunkX; cx++) {
      for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
        if (getter.apply(level.getChunk(cx, cz)) instanceof LevelChunkTicks<T> ticks) {
          ticks.getAll()
              .filter(tick -> box.isInside(tick.pos())) // keep ticks inside the box
              .forEach(found::add);
        }
      }
    }
    found.sort(ScheduledTick.DRAIN_ORDER);

    long now = level.getGameTime();
    List<TickEntry<T>> entries = new ArrayList<>(found.size());
    for (ScheduledTick<T> tick : found) {
      entries.add(new TickEntry<>(
          tick.pos().subtract(min),
          tick.type(),
          Math.max(0, tick.triggerTick() - now),
          tick.priority()
      ));
    }

    return entries;
  }

  /**
   * Places this snapshot with its minimum corner at {@code origin}
   * without triggering block updates.
   */
  public void place(ServerLevel level, BlockPos origin) {
    BoundingBox dst = boxAt(origin);
    level.getBlockTicks().clearArea(dst);
    level.getFluidTicks().clearArea(dst);

    for (BlockEntry entry : blocks) {
      level.setBlock(origin.offset(entry.offset()), Blocks.BARRIER.defaultBlockState(), SILENT_PLACE);
    }
    for (BlockEntry entry : blocks) {
      level.setBlock(origin.offset(entry.offset()), entry.state(), SILENT_PLACE);
    }

    try (ProblemReporter.ScopedCollector reporter = new ProblemReporter.ScopedCollector(LOGGER)) {
      for (BlockEntry entry : blocks) {
        if (entry.blockEntityTag() == null) {
          continue;
        }
        BlockPos pos = origin.offset(entry.offset());
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity != null) {
          blockEntity.loadCustomOnly(TagValueInput.create(
              reporter.forChild(blockEntity.problemPath()),
              level.registryAccess(),
              entry.blockEntityTag()
          ));
          blockEntity.setComponents(entry.blockEntityComponents());
          blockEntity.setChanged();
          level.sendBlockUpdated(pos, entry.state(), entry.state(), Block.UPDATE_CLIENTS);
        }
      }
    }

    scheduleTicks(level, level.getBlockTicks(), origin, blockTicks);
    scheduleTicks(level, level.getFluidTicks(), origin, fluidTicks);
  }

  private static <T> void scheduleTicks(
      ServerLevel level,
      LevelTicks<T> target,
      BlockPos origin,
      List<TickEntry<T>> entries
  ) {
    long now = level.getGameTime();
    for (TickEntry<T> entry : entries) {
      target.schedule(new ScheduledTick<>(
          entry.type(),
          origin.offset(entry.offset()),
          now + entry.delay(),
          entry.priority(),
          level.nextSubTickCount()
      ));
    }
  }

  /** The box this snapshot would occupy if placed with its minimum corner at {@code origin}. */
  public BoundingBox boxAt(BlockPos origin) {
    return new BoundingBox(
        origin.getX(), origin.getY(), origin.getZ(),
        origin.getX() + xSpan - 1,
        origin.getY() + ySpan - 1,
        origin.getZ() + zSpan - 1
    );
  }

  public int blockCount() {
    return (int) blocks.stream().filter(entry -> !entry.state().isAir()).count();
  }

  public int pendingTickCount() {
    return blockTicks.size() + fluidTicks.size();
  }
}
