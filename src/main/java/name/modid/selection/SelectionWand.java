package name.modid.selection;

import name.modid.ModItems;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;

/**
 * Selecting with the RDB wand: left-click a block for corner 1, right-click for corner 2.
 *
 * <p>Uses Fabric's click events rather than Item methods, because the events run <em>before</em> the
 * clicked block reacts: with Item.useOn, right-clicking a repeater would cycle its delay.
 *
 * <p>Each event fires on the client first, then on the server. Returning SUCCESS on the client cancels
 * the vanilla action (breaking / using the block) and forwards the click to the server, which sets the corner.
 */
public final class SelectionWand {
  private SelectionWand() { // static-only utility class
  }

  /** Registers the click handlers. */
  public static void initialize() {
    AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) ->
        click(player, hand, pos, 1));                           // left-click: corner 1
    UseBlockCallback.EVENT.register((player, level, hand, hitResult) ->
        click(player, hand, hitResult.getBlockPos(), 2));       // right-click: corner 2
  }

  private static InteractionResult click(Player player, InteractionHand hand, BlockPos pos, int corner) {
    if (player.isSpectator() || player.getItemInHand(hand).getItem() != ModItems.RDB_WAND) {
      return InteractionResult.PASS;
    }
    if (player instanceof ServerPlayer serverPlayer) {          // only the server owns selections
      serverPlayer.sendOverlayMessage(SelectionManager.setCorner(serverPlayer, corner, pos)); // action bar
    }
    return InteractionResult.SUCCESS;                           // cancel breaking / using the block
  }
}
