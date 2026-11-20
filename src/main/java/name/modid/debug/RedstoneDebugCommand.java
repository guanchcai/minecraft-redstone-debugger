package name.modid.debug;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import name.modid.RedstoneDebugger;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

public class RedstoneDebugCommand {
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, RedstoneDebuggerController controller) {
        dispatcher.register(Commands.literal("rdb")
                        .then(Commands.literal("start")
                                .executes(context -> {
                                    controller.start(2);
                                    context.getSource().sendSuccess(() -> Component.literal("Redstone debugger: " + controller.getState()), true);
                                    return 1;
                                })
                                .then(Commands.argument("speed", IntegerArgumentType.integer(1, 1000))
                                        .executes(context -> {
                                            controller.start(IntegerArgumentType.getInteger(context, "speed"));
                                            context.getSource().sendSuccess(() -> Component.literal("Redstone debugger: " + controller.getState()), true);
                                            return 1;
                                        })
                                ))
                        .then(Commands.literal("stop")
                                .executes(context -> {
                                    controller.stop();
                                    context.getSource().sendSuccess(() -> Component.literal("Redstone debugger: " + controller.getState()), true);
                                    return 1;
                                }))
                        .then(Commands.literal("pause")
                                .executes(context -> {
                                    controller.pause();
                                    context.getSource().sendSuccess(() -> Component.literal("Redstone debugger: " + controller.getState()), true);
                                    return 1;
                                }))
                        .then(Commands.literal("resume")
                                .executes(context -> {
                                    controller.resume();
                                    context.getSource().sendSuccess(() -> Component.literal("Redstone debugger: " + controller.getState()), true);
                                    return 1;
                                }))
                        .then(Commands.literal("checkStatus")
                                .executes(context -> {
                                    context.getSource().sendSuccess(() -> Component.literal(controller.getState()), false);
                                    return 1;
                                }))
                        .then(Commands.literal("step")
                                .executes(context -> {
                                    context.getSource().sendSuccess(() -> Component.literal("[rdb]: stepping 2 ticks (1 redstone tick)"), false);
                                    controller.step(2);
                                    return 1;
                                })
                                .then(Commands.argument("ticks", IntegerArgumentType.integer(1, 1000))
                                        .executes(context -> {
                                            int n = IntegerArgumentType.getInteger(context, "ticks");
                                            context.getSource().sendSuccess(() -> Component.literal(
                                                    "[rdb]: stepping " +
                                                             n * 2 +
                                                            " ticks (" + n + " redstone ticks)"), false);
                                            controller.step(n * 2);
                                            return 1;
                                        })))
        );
    }
}
