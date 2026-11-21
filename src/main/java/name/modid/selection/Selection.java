package name.modid.selection;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jspecify.annotations.Nullable;

/**
 * A player's <b>immutable</b> capture area as defined by its opposite corners.
 * Either corner may be unset while the player is still picking.
 */
public record Selection(
    ResourceKey<Level> dimension,  // corners only make sense in one dimension
    @Nullable BlockPos pos1,       // null until set
    @Nullable BlockPos pos2) {

  /** A selection in {@code dimension} with neither corner set. */
  public static Selection empty(ResourceKey<Level> dimension) {
    return new Selection(dimension, null, null);
  }

  public boolean isComplete() {
    return pos1 != null && pos2 != null;
  }

  /** The selected box, or null if a corner is missing. */
  public @Nullable BoundingBox box() {
    return isComplete() ? BoundingBox.fromCorners(pos1, pos2) : null;
  }

  public Selection withPos1(BlockPos pos) {
    return new Selection(dimension, pos.immutable(), pos2);
  }

  public Selection withPos2(BlockPos pos) {
    return new Selection(dimension, pos1, pos.immutable());
  }

  /**
   * Grows the box just enough to contain {@code pos}. Sets a corner if missing,
   * so the first sneak-clicks act like normal clicks.
   */
  public Selection including(BlockPos pos) {
    if (pos1 == null) {
      return withPos1(pos);
    }
    if (pos2 == null) {
      return withPos2(pos);
    }
    Selection result = this;
    for (Direction face : Direction.values()) {                // each of the 6 faces
      int past = result.distancePast(face, pos);
      if (past > 0) {
        result = result.moveFace(face, past);                   // push just that face out to pos
      }
    }
    return result;
  }

  /** Pushes the {@code face} side out by {@code amount} blocks. Requires a complete selection. */
  public Selection expand(Direction face, int amount) {
    return moveFace(face, amount);
  }

  /**
   * Pulls the {@code face} side in by {@code amount} blocks, stopping at 1 block thick.
   * Requires a complete selection.
   */
  public Selection shrink(Direction face, int amount) {
    BoundingBox box = box();
    int span = face.getAxis().choose(box.getXSpan(), box.getYSpan(), box.getZSpan());
    return moveFace(face, -Math.min(amount, span - 1));         // never past the opposite face
  }

  /** Moves the whole box {@code amount} blocks toward {@code direction}. Requires a complete selection. */
  public Selection shift(Direction direction, int amount) {
    return new Selection(dimension, pos1.relative(direction, amount), pos2.relative(direction, amount));
  }

  /**
   * Pulls each face in to {@code content}, which must lie inside the box.
   * Requires a complete selection.
   */
  public Selection fittedTo(BoundingBox content) {
    BlockPos min = new BlockPos(content.minX(), content.minY(), content.minZ());
    BlockPos max = new BlockPos(content.maxX(), content.maxY(), content.maxZ());
    Selection result = this;
    for (Direction face : Direction.values()) {                // each of the 6 faces
      BlockPos edge = face.getAxisDirection() == Direction.AxisDirection.POSITIVE ? max : min;
      result = result.moveFace(face, result.distancePast(face, edge)); // negative: pulls the face in
    }
    return result;
  }

  /** One line for chat, e.g. "Selection: 5x3x7 (105 blocks)." */
  public Component describe() {
    BoundingBox box = box();
    if (box == null) {
      return Component.literal("Now set corner " + (pos1 == null ? 1 : 2) + ".");
    }
    long volume = (long) box.getXSpan() * box.getYSpan() * box.getZSpan();
    return Component.literal(
        String.format("Selection: %dx%dx%d (%d blocks).",
        box.getXSpan(), box.getYSpan(), box.getZSpan(), volume)
    );
  }

  /**
   * How many blocks {@code pos} lies beyond the {@code face} side of the box.
   * Zero or negative if it's on the inner side. Requires a complete selection.
   */
  private int distancePast(Direction face, BlockPos pos) {
    BoundingBox box = box();
    Direction.Axis axis = face.getAxis();
    int faceCoord = face.getAxisDirection() == Direction.AxisDirection.POSITIVE
        ? axis.choose(box.maxX(), box.maxY(), box.maxZ())
        : axis.choose(box.minX(), box.minY(), box.minZ());
    int posCoord = axis.choose(pos.getX(), pos.getY(), pos.getZ());
    return (posCoord - faceCoord) * face.getAxisDirection().getStep(); // flip sign for -X/-Y/-Z faces
  }

  /**
   * Moves the {@code face} side of the box {@code amount} blocks outward (negative: inward).
   * Only the corner forming that face moves, so pos1 and pos2 keep their colours.
   * Requires a complete selection.
   */
  private Selection moveFace(Direction face, int amount) {
    Direction.Axis axis = face.getAxis();
    int c1 = axis.choose(pos1.getX(), pos1.getY(), pos1.getZ());
    int c2 = axis.choose(pos2.getX(), pos2.getY(), pos2.getZ());
    boolean positive = face.getAxisDirection() == Direction.AxisDirection.POSITIVE;
    boolean pos1FormsFace = positive ? c1 > c2 : c1 < c2;      // tie (1 block thick): move pos2
    return pos1FormsFace
        ? new Selection(dimension, pos1.relative(face, amount), pos2)
        : new Selection(dimension, pos1, pos2.relative(face, amount));
  }
}
