package name.modid.selection;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import name.modid.ModItems;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * Selecting with the RDB wand, server side: left-click a block for corner 1, right-click for corner 2,
 * sneak + either click to grow the box to include the block.
 * Hold the selection modifier (Left Alt) and scroll to push or pull the face you're looking toward.
 *
 * <p>The client aims (up to {@link #RANGE} blocks) and sends a {@link WandClickPayload} or
 * {@link WandScrollPayload}; see WandInput in the client source set. Vanilla never sees wand clicks,
 * so blocks aren't broken or used.
 */
public final class SelectionWand {
  /** How far the wand reaches, in blocks. */
  public static final double RANGE = 64.0;

  /** Extra distance the server allows, since the player may have moved a little since clicking. */
  private static final double RANGE_TOLERANCE = 4.0;

  private SelectionWand() { // static-only utility class
  }

  /** Registers the click and scroll packets and their server handlers. Must run during mod init, on both sides. */
  public static void initialize() {
    PayloadTypeRegistry.serverboundPlay().register(WandClickPayload.TYPE, WandClickPayload.CODEC);
    ServerPlayNetworking.registerGlobalReceiver(WandClickPayload.TYPE,
        (payload, context) -> click(context.player(), payload)); // runs on the server thread
    PayloadTypeRegistry.serverboundPlay().register(WandScrollPayload.TYPE, WandScrollPayload.CODEC);
    ServerPlayNetworking.registerGlobalReceiver(WandScrollPayload.TYPE,
        (payload, context) -> scroll(context.player(), payload));
  }

  /** Whether {@code player} can select with the wand right now. Used by both sides. */
  public static boolean isHoldingWand(Player player) {
    return !player.isSpectator() && player.getMainHandItem().getItem() == ModItems.RDB_WAND;
  }

  private static void click(ServerPlayer player, WandClickPayload payload) {
    double maxDistance = RANGE + RANGE_TOLERANCE;
    if (!isHoldingWand(player)
        || player.getEyePosition().distanceToSqr(Vec3.atCenterOf(payload.pos())) > maxDistance * maxDistance) {
      return;
    }
    respond(player, () -> player.isShiftKeyDown()
        ? SelectionManager.include(player, payload.pos())
        : SelectionManager.setCorner(player, payload.rightClick() ? 2 : 1, payload.pos()));
  }

  private static void scroll(ServerPlayer player, WandScrollPayload payload) {
    if (!isHoldingWand(player)) {
      return;
    }
    Direction face = payload.face();
    respond(player, () -> Component.literal(String.format("%s the %s face %s. ",
            payload.outward() ? "Pushed" : "Pulled", face.getName(), payload.outward() ? "out" : "in"))
        .append(SelectionManager.edit(player, selection -> payload.outward()
            ? selection.expand(face, 1)
            : selection.shrink(face, 1))));
  }

  /** Something the wand does: returns a message for the action bar, or throws to refuse. */
  @FunctionalInterface
  private interface WandAction {
    Component run() throws CommandSyntaxException;
  }

  /** Runs {@code action}, showing its message, or its error in red, on the action bar. */
  private static void respond(ServerPlayer player, WandAction action) {
    try {
      player.sendOverlayMessage(action.run());
    } catch (CommandSyntaxException e) {                        // e.g. in the VENV, or no selection
      player.sendOverlayMessage(ComponentUtils.fromMessage(e.getRawMessage()).copy()
          .withStyle(ChatFormatting.RED));
    }
  }
}
