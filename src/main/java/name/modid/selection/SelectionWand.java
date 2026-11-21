package name.modid.selection;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import name.modid.ModItems;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * Selecting with the RDB wand, server side: left-click a block for corner 1, right-click for corner 2,
 * sneak + either click to grow the box to include the block.
 *
 * <p>The client aims (up to {@link #RANGE} blocks) and sends a {@link WandClickPayload};
 * see WandInput in the client source set. Vanilla never sees wand clicks, so blocks aren't broken or used.
 */
public final class SelectionWand {
  /** How far the wand reaches, in blocks. */
  public static final double RANGE = 64.0;

  /** Extra distance the server allows, since the player may have moved a little since clicking. */
  private static final double RANGE_TOLERANCE = 4.0;

  private SelectionWand() { // static-only utility class
  }

  /** Registers the click packet and its server handler. Must run during mod init, on both sides. */
  public static void initialize() {
    PayloadTypeRegistry.serverboundPlay().register(WandClickPayload.TYPE, WandClickPayload.CODEC);
    ServerPlayNetworking.registerGlobalReceiver(WandClickPayload.TYPE,
        (payload, context) -> click(context.player(), payload)); // runs on the server thread
  }

  /** Whether {@code player} can select with the wand right now. Used by both sides. */
  public static boolean isHoldingWand(Player player) {
    return !player.isSpectator() && player.getMainHandItem().getItem() == ModItems.RDB_WAND;
  }

  private static void click(ServerPlayer player, WandClickPayload payload) {
    double maxDistance = RANGE + RANGE_TOLERANCE;
    if (!isHoldingWand(player)                                  // never trust the client:
        || player.getEyePosition().distanceToSqr(Vec3.atCenterOf(payload.pos())) > maxDistance * maxDistance) {
      return;                                                   // anyone could send any position
    }
    try {
      Component message = player.isShiftKeyDown()
          ? SelectionManager.include(player, payload.pos())     // sneak-click: grow to include this block
          : SelectionManager.setCorner(player, payload.rightClick() ? 2 : 1, payload.pos());
      player.sendOverlayMessage(message);                       // action bar
    } catch (CommandSyntaxException e) {                        // e.g. clicked in the VENV
      player.sendOverlayMessage(ComponentUtils.fromMessage(e.getRawMessage()).copy()
          .withStyle(ChatFormatting.RED));
    }
  }
}
