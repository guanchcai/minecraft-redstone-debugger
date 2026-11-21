package name.modid.selection;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

/** Server-side store of each player's selection. */
public final class SelectionManager {
  /** Selections by player UUID. */
  private static final Map<UUID, Selection> SELECTIONS = new HashMap<>();

  private SelectionManager() { // static-only utility class
  }

  /** The player's selection, or null if they haven't set any corner. */
  public static @Nullable Selection get(ServerPlayer player) {
    return SELECTIONS.get(player.getUUID());
  }

  /** Sets corner 1 in the player's current dimension. */
  public static Selection setPos1(ServerPlayer player, BlockPos pos) {
    return update(player, currentOrEmpty(player).withPos1(pos));
  }

  /** Sets corner 2 in the player's current dimension. */
  public static Selection setPos2(ServerPlayer player, BlockPos pos) {
    return update(player, currentOrEmpty(player).withPos2(pos));
  }

  /** Forgets all selections, so they can't leak into the next world opened in singleplayer. */
  public static void onServerStopped() {
    SELECTIONS.clear();
  }

  /** The player's selection, or a fresh one if they have none, or it's in another dimension. */
  private static Selection currentOrEmpty(ServerPlayer player) {
    @Nullable Selection current = SELECTIONS.get(player.getUUID());
    if (current == null || current.dimension() != player.level().dimension()) {
      return Selection.empty(player.level().dimension());       // new dimension: start over
    }
    return current;
  }

  /** Every change goes through here. */
  private static Selection update(ServerPlayer player, Selection selection) {
    SELECTIONS.put(player.getUUID(), selection);
    return selection;
  }
}
