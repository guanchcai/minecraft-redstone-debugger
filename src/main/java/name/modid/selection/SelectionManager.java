package name.modid.selection;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import name.modid.venv.VenvManager;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

/** Server-side store of each player's selection. */
public final class SelectionManager {
  /** Selections by player UUID. */
  private static final Map<UUID, Selection> SELECTIONS = new HashMap<>();

  private static final SimpleCommandExceptionType SELECT_IN_VENV = new SimpleCommandExceptionType(
      Component.literal("You can't select in the VENV. Select in the world the contraption is in.")
  );

  private SelectionManager() { // static-only utility class
  }

  /** Registers the selection packet. Must run during mod init, on both client and server. */
  public static void initialize() {
    PayloadTypeRegistry.clientboundPlay().register(SelectionPayload.TYPE, SelectionPayload.CODEC);
  }

  /** The player's selection, or null if they haven't set any corner. */
  public static @Nullable Selection get(ServerPlayer player) {
    return SELECTIONS.get(player.getUUID());
  }

  /**
   * Sets corner {@code corner} (1 or 2) in the player's current dimension.
   *
   * @return a one-line description of the result, for chat or the action bar
   * @throws CommandSyntaxException if the player is in the VENV
   */
  public static Component setCorner(ServerPlayer player, int corner, BlockPos pos)
      throws CommandSyntaxException {
    Selection selection = corner == 1 ? setPos1(player, pos) : setPos2(player, pos);
    return Component.literal(String.format("Corner %d set to %d, %d, %d. ",
        corner, pos.getX(), pos.getY(), pos.getZ())).append(selection.describe());
  }

  /**
   * Grows the player's selection just enough to contain {@code pos}.
   *
   * @return a one-line description of the result, for chat or the action bar
   * @throws CommandSyntaxException if the player is in the VENV
   */
  public static Component include(ServerPlayer player, BlockPos pos) throws CommandSyntaxException {
    Selection selection = update(player, currentOrEmpty(player).including(pos));
    return Component.literal(String.format("Included %d, %d, %d. ",
        pos.getX(), pos.getY(), pos.getZ())).append(selection.describe());
  }

  /** Forgets all selections, so they can't leak into the next world opened in singleplayer. */
  public static void onServerStopped() {
    SELECTIONS.clear();
  }

  /** Sets corner 1 in the player's current dimension. */
  private static Selection setPos1(ServerPlayer player, BlockPos pos) throws CommandSyntaxException {
    return update(player, currentOrEmpty(player).withPos1(pos));
  }

  /** Sets corner 2 in the player's current dimension. */
  private static Selection setPos2(ServerPlayer player, BlockPos pos) throws CommandSyntaxException {
    return update(player, currentOrEmpty(player).withPos2(pos));
  }

  /**
   * The player's selection, or a fresh one if they have none, or it's in another dimension.
   * Every edit starts here, so this is where selecting in the VENV is refused.
   */
  private static Selection currentOrEmpty(ServerPlayer player) throws CommandSyntaxException {
    if (VenvManager.isVenv(player.level())) {
      throw SELECT_IN_VENV.create();                            // also keeps their real selection intact
    }
    @Nullable Selection current = SELECTIONS.get(player.getUUID());
    if (current == null || current.dimension() != player.level().dimension()) {
      return Selection.empty(player.level().dimension());       // new dimension: start over
    }
    return current;
  }

  /** Every change goes through here, so the client is always told. */
  private static Selection update(ServerPlayer player, Selection selection) {
    SELECTIONS.put(player.getUUID(), selection);
    ServerPlayNetworking.send(player, SelectionPayload.of(selection)); // client draws it (SelectionRenderer)
    return selection;
  }
}
