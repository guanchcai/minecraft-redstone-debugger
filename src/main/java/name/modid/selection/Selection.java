package name.modid.selection;

import net.minecraft.core.BlockPos;
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
}
