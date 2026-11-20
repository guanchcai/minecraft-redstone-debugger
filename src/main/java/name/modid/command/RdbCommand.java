package name.modid.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.Dynamic2CommandExceptionType;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import name.modid.venv.ContraptionSnapshot;
import name.modid.venv.VenvManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * The /rdb command tree.
 * <pre>
 * /rdb capture &lt;from&gt; &lt;to&gt;   copy the box into the VENV and go there
 * /rdb goto                  go to the contraption in the VENV
 * /rdb return                go back to where you were before entering
 * /rdb reset                 restore the contraption to its captured state
 * /rdb clear                 empty the VENV
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
      Component.literal("The VENV is empty. Capture something with /rdb capture <from> <to>.")
  );
  private static final SimpleCommandExceptionType NOT_IN_VENV = new SimpleCommandExceptionType(
      Component.literal("You are not in the VENV.")
  );
  private static final SimpleCommandExceptionType NOTHING_TO_RESET = new SimpleCommandExceptionType(
      Component.literal("Nothing to reset: the VENV is empty, or the game restarted since the capture. Capture again.")
  );

  private RdbCommand() {
  }

  /** Builds the /rdb tree. Called by Fabric every time commands are (re)loaded. */
  public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
    dispatcher.register(Commands.literal("rdb")
        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))   // ops / cheats only
        .then(Commands.literal("capture")
            .then(Commands.argument("from", BlockPosArgument.blockPos())
                .then(Commands.argument("to", BlockPosArgument.blockPos())
                    .executes(RdbCommand::capture))))
        .then(Commands.literal("clear")
            .executes(RdbCommand::clear))
        .then(Commands.literal("goto")                                  // /rdb goto
            .executes(RdbCommand::goTo))
        .then(Commands.literal("return")                                // /rdb return
            .executes(RdbCommand::returnFromVenv))
        .then(Commands.literal("reset")                                 // /rdb reset
            .executes(RdbCommand::reset)));
  }

  private static int capture(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
    ServerPlayer player = context.getSource().getPlayerOrException(); // fails if run from the console
    if (VenvManager.isVenv(player.level())) {
      throw CAPTURE_FROM_VENV.create();
    }

    BlockPos from = BlockPosArgument.getLoadedBlockPos(context, "from"); // errors if not loaded
    BlockPos to = BlockPosArgument.getLoadedBlockPos(context, "to");
    BoundingBox box = BoundingBox.fromCorners(from, to);              // corners can be in any order

    long volume = (long) box.getXSpan() * box.getYSpan() * box.getZSpan(); // avoid int overflow
    int maxVolume = context.getSource().getServer().getGameRules()
        .get(VenvManager.MAX_CAPTURE_VOLUME);
    if (volume > maxVolume) {
      throw TOO_LARGE.create(volume, maxVolume);
    }

    ContraptionSnapshot snapshot = VenvManager.capture(player.level(), box);
    VenvManager.enter(player);

    context.getSource().sendSuccess(() -> Component.literal(String.format(
        "Captured %d blocks and %d pending ticks (%dx%dx%d) into the VENV.",
        snapshot.blockCount(), snapshot.pendingTickCount(),
        box.getXSpan(), box.getYSpan(), box.getZSpan())), true); // true: also tell other ops + log
    return snapshot.blockCount();                                // result for /execute store
  }

  private static int clear(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
    if (!VenvManager.clear(context.getSource().getServer())) {
      throw VENV_EMPTY.create();
    }
    context.getSource().sendSuccess(() -> Component.literal("Cleared the VENV."), true);
    return 1;                                                         // 1 = success, by convention
  }

  private static int goTo(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
    if (!VenvManager.enter(context.getSource().getPlayerOrException())) {
      throw VENV_EMPTY.create();
    }
    return 1;                                                         // the teleport is the feedback
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
    context.getSource().sendSuccess(() -> Component.literal("Reset the VENV contraption to its captured state."), true);
    return 1;
  }
}
