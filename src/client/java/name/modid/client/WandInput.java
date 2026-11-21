package name.modid.client;

import com.mojang.blaze3d.platform.InputConstants;
import name.modid.RedstoneDebugger;
import name.modid.selection.SelectionWand;
import name.modid.selection.WandClickPayload;
import name.modid.selection.WandScrollPayload;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.event.client.player.ClientHotbarScrollEvents;
import net.fabricmc.fabric.api.event.client.player.ClientPreAttackCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/** Client side of the wand: aims at the block under the crosshair, up to SelectionWand.RANGE away. */
public final class WandInput {
  /** Hold while scrolling with the wand to move a face instead of changing hotbar slot. Rebindable. */
  private static final KeyMapping SELECTION_MODIFIER = new KeyMapping(
      "key.redstone-debugger.selection_modifier",
      InputConstants.KEY_LALT,
      KeyMapping.Category.register(RedstoneDebugger.id("rdb")));

  private WandInput() { // static-only utility class
  }

  /** Registers the click and scroll handlers, and the selection modifier key. */
  public static void initialize() {
    ClientPreAttackCallback.EVENT.register((client, player, clickCount) -> {
      if (!SelectionWand.isHoldingWand(player)) {
        return false;                                           // not the wand: attack as normal
      }
      if (clickCount > 0) {                                     // a fresh press, not a held button
        click(player, false);
      }
      return true;                                              // never break or punch with the wand
    });
    UseBlockCallback.EVENT.register((player, level, hand, hitResult) -> use(player, level, hand)); // in reach
    UseItemCallback.EVENT.register((player, level, hand) -> use(player, level, hand)); // anything further

    KeyMappingHelper.registerKeyMapping(SELECTION_MODIFIER);
    ClientHotbarScrollEvents.ALLOW.register((inventory, currentSlot, newSlot, scrollX, scrollY) -> {
      Player player = inventory.player;
      if (!SELECTION_MODIFIER.isDown() || scrollY == 0 || !SelectionWand.isHoldingWand(player)) {
        return true;                                            // normal hotbar scrolling
      }
      ClientPlayNetworking.send(new WandScrollPayload(player.getNearestViewDirection(), scrollY > 0));
      return false;                                             // keep the wand selected
    });
  }

  private static InteractionResult use(Player player, Level level, InteractionHand hand) {
    if (!level.isClientSide()                                   // singleplayer: the server fires these too
        || hand != InteractionHand.MAIN_HAND || !SelectionWand.isHoldingWand(player)) {
      return InteractionResult.PASS;
    }
    click(player, true);
    return InteractionResult.FAIL;                              // client FAIL: cancel, send no vanilla packet
  }

  /** Sends the block under the crosshair to the server, or says nothing is in range. */
  private static void click(Player player, boolean rightClick) {
    HitResult hit = player.pick(SelectionWand.RANGE, 1.0F, false); // false: look through water
    if (hit.getType() == HitResult.Type.BLOCK && hit instanceof BlockHitResult blockHit) {
      ClientPlayNetworking.send(new WandClickPayload(blockHit.getBlockPos(), rightClick));
    } else {
      player.sendOverlayMessage(Component.literal(
          "No block within " + (int) SelectionWand.RANGE + " blocks."));
    }
  }
}
