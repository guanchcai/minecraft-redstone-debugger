package name.modid.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.Dynamic2CommandExceptionType;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import name.modid.ModItems;
import name.modid.debug.RedstoneDebuggerController;
import name.modid.selection.Selection;
import name.modid.selection.SelectionManager;
import name.modid.venv.ContraptionSnapshot;
import name.modid.venv.VenvManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jspecify.annotations.Nullable;

/**
 * The /rdb command tree (ops / cheats only).
 * <pre>
 * Selection
 * /rdb wand                 get the selection wand (left-click: corner 1, right-click: corner 2)
 * /rdb pos1 [&lt;pos&gt;]          set corner 1 (default: where you stand)
 * /rdb pos2 [&lt;pos&gt;]          set corner 2
 * /rdb sel                   show the selection
 * /rdb expand [&lt;dir&gt;] [&lt;n&gt;]  push the &lt;dir&gt; face out by n (default: where you look, 1)
 * /rdb shrink [&lt;dir&gt;] [&lt;n&gt;]  pull the &lt;dir&gt; face in by n
 * /rdb shift [&lt;dir&gt;] [&lt;n&gt;]   move the whole box n blocks toward &lt;dir&gt;
 * /rdb fit                   shrink the box to the blocks inside it
 * /rdb desel                 clear the selection
 *
 * VENV
 * /rdb capture               copy the selection into the VENV and go there
 * /rdb capture &lt;from&gt; &lt;to&gt;   copy the box into the VENV and go there
 * /rdb goto                  go to the contraption in the VENV
 * /rdb return                go back to where you were before entering
 * /rdb reset                 restore the contraption to its captured state
 * /rdb clear                 empty the VENV
 *
 * Redstone debugger
 * /rdb start [&lt;speed&gt;]       turn the debugger on, or change its speed: redstone runs 1 in every
 *                            &lt;speed&gt; game ticks (default 2, 1 = normal speed)
 * /rdb stop                  turn the debugger off
 * /rdb pause                 freeze redstone; players, mobs and fluids keep running
 * /rdb resume                unfreeze redstone
 * /rdb step [&lt;n&gt;]            while paused, run n redstone ticks (2n game ticks, default 1)
 * /rdb checkStatus           show the debugger state
 * </pre>
 */
public final class RdbCommand {
  // Error messages.
  private static final SimpleCommandExceptionType CAPTURE_FROM_VENV = new SimpleCommandExceptionType(
      Component.literal("You are already in the VENV. Capture from the world the contraption is in.")
  );
  private static final Dynamic2CommandExceptionType TOO_LARGE = new Dynamic2CommandExceptionType(
      (volume, max) -> Component.literal(
          "Region too large: " + volume + " blocks (max " + max
              + "). Raise it with /gamerule redstone-debugger:max_capture_volume <blocks>."
      )
  );
  private static final SimpleCommandExceptionType VENV_EMPTY = new SimpleCommandExceptionType(
      Component.literal("The VENV is empty. Capture something with /rdb capture.")
  );
  private static final SimpleCommandExceptionType NOT_IN_VENV = new SimpleCommandExceptionType(
      Component.literal("You are not in the VENV.")
  );
  private static final SimpleCommandExceptionType NOTHING_TO_RESET = new SimpleCommandExceptionType(
      Component.literal("Nothing to reset: the VENV is empty, or the game restarted since the capture. Capture again.")
  );
  private static final SimpleCommandExceptionType SELECTION_ELSEWHERE = new SimpleCommandExceptionType(
      Component.literal("Your selection is in another dimension. Go there, or select again here.")
  );

  private RdbCommand() {
  }

  /** Builds the /rdb tree. Called by Fabric every time commands are (re)loaded. */
  public static void register(CommandDispatcher<CommandSourceStack> dispatcher, RedstoneDebuggerController controller) {
    dispatcher.register(Commands.literal("rdb")
        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)) // ops / cheats only
        // Selection
        .then(Commands.literal("wand")
            .executes(RdbCommand::giveWand))                    // /rdb wand
        .then(cornerCommand("pos1", 1))                         // /rdb pos1 [<pos>]
        .then(cornerCommand("pos2", 2))                         // /rdb pos2 [<pos>]
        .then(Commands.literal("sel")
            .executes(RdbCommand::showSelection))
        .then(editCommand("expand", Selection::expand))         // /rdb expand [<direction>] [<amount>]
        .then(editCommand("shrink", Selection::shrink))
        .then(editCommand("shift", Selection::shift))
        .then(Commands.literal("fit")
            .executes(RdbCommand::fit))
        .then(Commands.literal("desel")
            .executes(RdbCommand::deselect))
        // VENV
        .then(Commands.literal("capture")
            .executes(RdbCommand::captureSelection)             // /rdb capture: use the selection
            .then(Commands.argument("from", BlockPosArgument.blockPos())
                .then(Commands.argument("to", BlockPosArgument.blockPos())
                    .executes(RdbCommand::captureBox))))        // /rdb capture <from> <to>
        .then(Commands.literal("goto")
            .executes(RdbCommand::goTo))
        .then(Commands.literal("return")
            .executes(RdbCommand::returnFromVenv))
        .then(Commands.literal("reset")
            .executes(RdbCommand::reset))
        .then(Commands.literal("clear")
            .executes(RdbCommand::clear))
        // Redstone debugger
        .then(Commands.literal("start")
            .executes(context -> start(context, controller, 2))         // /rdb start: default speed 2
            .then(Commands.argument("speed", IntegerArgumentType.integer(1, 1000))
                .executes(context -> start(context, controller,
                    IntegerArgumentType.getInteger(context, "speed"))))) // /rdb start <speed>
        .then(Commands.literal("stop")
            .executes(context -> stop(context, controller)))
        .then(Commands.literal("pause")
            .executes(context -> pause(context, controller)))
        .then(Commands.literal("resume")
            .executes(context -> resume(context, controller)))
        .then(Commands.literal("step")
            .executes(context -> step(context, controller, 1))          // /rdb step: 1 redstone tick
            .then(Commands.argument("ticks", IntegerArgumentType.integer(1, 1000))
                .executes(context -> step(context, controller,
                    IntegerArgumentType.getInteger(context, "ticks"))))) // /rdb step <n>
        .then(Commands.literal("checkStatus")
            .executes(context -> checkStatus(context, controller))));
  }

  /** Builds {@code /rdb <name> [<pos>]}, which sets corner {@code corner} of the selection. */
  private static LiteralArgumentBuilder<CommandSourceStack> cornerCommand(String name, int corner) {
    return Commands.literal(name)
        .executes(context -> setCorner(context, corner,
            BlockPos.containing(context.getSource().getPosition())))
        .then(Commands.argument("pos", BlockPosArgument.blockPos())
            .executes(context -> setCorner(context, corner,
                BlockPosArgument.getBlockPos(context, "pos"))));
  }

  /** An edit like {@code Selection::expand}: changes a selection in a direction by an amount. */
  @FunctionalInterface
  private interface SelectionEdit {
    Selection apply(Selection selection, Direction direction, int amount);
  }

  /**
   * Builds {@code /rdb <name> [<direction>] [<amount>]}.
   * Direction defaults to forward (where the player is looking) and amount to 1.
   */
  private static LiteralArgumentBuilder<CommandSourceStack> editCommand(String name, SelectionEdit edit) {
    LiteralArgumentBuilder<CommandSourceStack> command = Commands.literal(name)
        .executes(context -> applyEdit(context, name, edit, 1, DirectionWord.FORWARD)) // /rdb expand
        .then(amountArgument(name, edit, DirectionWord.FORWARD)); // /rdb expand 3
    for (DirectionWord word : DirectionWord.values()) {
      command.then(Commands.literal(word.word())
          .executes(context -> applyEdit(context, name, edit, 1, word)) // /rdb expand north
          .then(amountArgument(name, edit, word)));             // /rdb expand north 3
    }
    return command;
  }

  /** The optional {@code <amount>} at the end of an edit command, applied toward {@code word}. */
  private static RequiredArgumentBuilder<CommandSourceStack, Integer> amountArgument(
      String name, SelectionEdit edit, DirectionWord word) {
    return Commands.argument("amount", IntegerArgumentType.integer(1))
        .executes(context -> applyEdit(context, name, edit,
            IntegerArgumentType.getInteger(context, "amount"), word));
  }

  // ---- Selection ----

  private static int giveWand(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
    ServerPlayer player = context.getSource().getPlayerOrException();
    player.getInventory().placeItemBackInInventory(
        new ItemStack(ModItems.RDB_WAND), Prediction.SERVER_ONLY); // full inventory: drops at their feet
    context.getSource().sendSuccess(() -> Component.literal(
        "Left-click: corner 1. Right-click: corner 2. " +
            "Sneak-click: grow the box to include a block."), false);
    return 1;
  }

  private static int setCorner(CommandContext<CommandSourceStack> context, int corner, BlockPos pos)
      throws CommandSyntaxException {
    Component message = SelectionManager.setCorner(context.getSource().getPlayerOrException(), corner, pos);
    context.getSource().sendSuccess(() -> message, false);  // only tell this player
    return 1;
  }

  private static int showSelection(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
    @Nullable Selection selection = SelectionManager.get(context.getSource().getPlayerOrException());
    if (selection == null) {
      throw SelectionManager.NO_SELECTION.create();
    }
    context.getSource().sendSuccess(selection::describe, false);
    return 1;
  }

  private static int applyEdit(CommandContext<CommandSourceStack> context, String name, SelectionEdit edit,
      int amount, DirectionWord word) throws CommandSyntaxException {
    ServerPlayer player = context.getSource().getPlayerOrException();
    Direction direction = word.resolve(player);                 // e.g. "left" -> WEST
    Component message = SelectionManager.edit(player, selection -> edit.apply(selection, direction, amount));
    context.getSource().sendSuccess(() -> Component.literal(String.format(
        "Ran /rdb %s %s %d. ", name, direction.getName(), amount)).append(message), false);
    return 1;
  }

  private static int fit(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
    ServerPlayer player = context.getSource().getPlayerOrException();
    requireCapturable(context, player.level(), requireSelectedBox(player)); // fit reads every block, like capture
    Component message = SelectionManager.fit(player);
    context.getSource().sendSuccess(() -> Component.literal("Fitted to the blocks inside. ").append(message), false);
    return 1;
  }

  private static int deselect(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
    SelectionManager.deselect(context.getSource().getPlayerOrException());
    context.getSource().sendSuccess(() -> Component.literal("Selection cleared."), false);
    return 1;
  }

  // ---- VENV ----

  private static int captureSelection(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
    ServerPlayer player = context.getSource().getPlayerOrException();
    return capture(context, player, requireSelectedBox(player));
  }

  private static int captureBox(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
    ServerPlayer player = context.getSource().getPlayerOrException();
    BlockPos from = BlockPosArgument.getBlockPos(context, "from");
    BlockPos to = BlockPosArgument.getBlockPos(context, "to");
    return capture(context, player, BoundingBox.fromCorners(from, to));
  }

  /** Shared by both forms of /rdb capture: checks the box, copies it into the VENV, takes the player there. */
  private static int capture(CommandContext<CommandSourceStack> context, ServerPlayer player, BoundingBox box)
      throws CommandSyntaxException {
    if (VenvManager.isVenv(player.level())) {
      throw CAPTURE_FROM_VENV.create();
    }
    requireCapturable(context, player.level(), box);

    ContraptionSnapshot snapshot = VenvManager.capture(player.level(), box);
    VenvManager.enter(player);

    context.getSource().sendSuccess(() -> Component.literal(String.format(
        "Captured %d blocks and %d pending ticks (%dx%dx%d) into the VENV.",
        snapshot.blockCount(), snapshot.pendingTickCount(),
        box.getXSpan(), box.getYSpan(), box.getZSpan())), true); // true: also tell other ops + log
    return snapshot.blockCount(); // result for /execute store
  }

  private static int goTo(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
    if (!VenvManager.enter(context.getSource().getPlayerOrException())) {
      throw VENV_EMPTY.create();
    }
    return 1;
  }

  private static int returnFromVenv(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
    if (!VenvManager.leave(context.getSource().getPlayerOrException())) {
      throw NOT_IN_VENV.create();
    }
    return 1;
  }

  private static int reset(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
    if (!VenvManager.reset(context.getSource().getServer())) {
      throw NOTHING_TO_RESET.create();
    }
    context.getSource().sendSuccess(() -> Component.literal(
        "Reset the VENV contraption to its captured state."
    ), true);
    return 1;
  }

  private static int clear(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
    if (!VenvManager.clear(context.getSource().getServer())) {
      throw VENV_EMPTY.create();
    }
    context.getSource().sendSuccess(() -> Component.literal("Cleared the VENV."), true);
    return 1;                                                         // 1 = success, by convention
  }

  // ---- Redstone debugger ----

  private static int start(CommandContext<CommandSourceStack> context, RedstoneDebuggerController controller,
      int speed) {
    controller.start(speed);
    sendDebuggerState(context, controller);
    return 1;
  }

  private static int stop(CommandContext<CommandSourceStack> context, RedstoneDebuggerController controller) {
    controller.stop();
    sendDebuggerState(context, controller);
    return 1;
  }

  private static int pause(CommandContext<CommandSourceStack> context, RedstoneDebuggerController controller) {
    controller.pause();
    sendDebuggerState(context, controller);
    return 1;
  }

  private static int resume(CommandContext<CommandSourceStack> context, RedstoneDebuggerController controller) {
    controller.resume();
    sendDebuggerState(context, controller);
    return 1;
  }

  /** Steps {@code redstoneTicks} redstone ticks; the controller counts in game ticks (2 per redstone tick). */
  private static int step(CommandContext<CommandSourceStack> context, RedstoneDebuggerController controller,
      int redstoneTicks) {
    int gameTicks = redstoneTicks * 2;
    String redstone = redstoneTicks == 1 ? "1 redstone tick" : redstoneTicks + " redstone ticks";
    context.getSource().sendSuccess(() -> Component.literal(
        "[rdb]: stepping " + gameTicks + " ticks (" + redstone + ")"), false);
    controller.step(gameTicks);
    return 1;
  }

  private static int checkStatus(CommandContext<CommandSourceStack> context, RedstoneDebuggerController controller) {
    context.getSource().sendSuccess(() -> Component.literal(controller.getState()), false);
    return 1;
  }

  /** Tells this player, other ops and the log the debugger's state after a change. */
  private static void sendDebuggerState(CommandContext<CommandSourceStack> context,
      RedstoneDebuggerController controller) {
    context.getSource().sendSuccess(() -> Component.literal("Redstone debugger: " + controller.getState()), true);
  }

  // ---- Checks ----

  /** The player's selected box. Throws unless both corners are set, in the player's current dimension. */
  private static BoundingBox requireSelectedBox(ServerPlayer player) throws CommandSyntaxException {
    @Nullable Selection selection = SelectionManager.get(player);
    @Nullable BoundingBox box = selection == null ? null : selection.box();
    if (selection == null || box == null) {
      throw SelectionManager.NO_SELECTION.create();
    }
    if (selection.dimension() != player.level().dimension()) {
      throw SELECTION_ELSEWHERE.create();
    }
    return box;
  }

  /** Throws unless {@code box} is within the max capture volume and fully loaded. */
  private static void requireCapturable(CommandContext<CommandSourceStack> context, ServerLevel level,
                                        BoundingBox box) throws CommandSyntaxException {
    long volume = (long) box.getXSpan() * box.getYSpan() * box.getZSpan();
    int maxVolume = context.getSource().getServer().getGameRules()
        .get(VenvManager.MAX_CAPTURE_VOLUME);
    if (volume > maxVolume) { // before requireLoaded since this bounds that loop
      throw TOO_LARGE.create(volume, maxVolume);
    }
    requireLoaded(level, box);
  }

  /** Throws unless all of {@code box} is inside the world and in loaded chunks. */
  private static void requireLoaded(ServerLevel level, BoundingBox box) throws CommandSyntaxException {
    if (!level.isInWorldBounds(new BlockPos(box.minX(), box.minY(), box.minZ()))
        || !level.isInWorldBounds(new BlockPos(box.maxX(), box.maxY(), box.maxZ()))) {
      throw BlockPosArgument.ERROR_OUT_OF_WORLD.create();             // a box is inside if both corners are
    }
    // One check per chunk column (16x16 blocks), not per block.
    for (int chunkX = SectionPos.blockToSectionCoord(box.minX()); chunkX <= SectionPos.blockToSectionCoord(box.maxX()); chunkX++) {
      for (int chunkZ = SectionPos.blockToSectionCoord(box.minZ()); chunkZ <= SectionPos.blockToSectionCoord(box.maxZ()); chunkZ++) {
        BlockPos corner = new BlockPos(
            SectionPos.sectionToBlockCoord(chunkX), box.minY(), SectionPos.sectionToBlockCoord(chunkZ));
        if (!level.isLoaded(corner)) {
          throw BlockPosArgument.ERROR_NOT_LOADED.create();           // vanilla's "That position is not loaded"
        }
      }
    }
  }
}
