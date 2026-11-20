package name.modid.debug;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

public class RedstoneDebugCommand {
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, RedstoneDebuggerController info) {
        dispatcher.register(Commands.literal("rdb")
                        .then(Commands.literal("start")
                                .executes(context -> {
                                    info.start();
                                    context.getSource().sendSuccess(() -> Component.literal("Redstone debugger: " + info.getState()), true);
                                    return 1;
                                }))
                        .then(Commands.literal("stop")
                                .executes(context -> {
                                    info.stop();
                                    context.getSource().sendSuccess(() -> Component.literal("Redstone debugger: " + info.getState()), true);
                                    return 1;
                                }))
                        .then(Commands.literal("pause")
                                .executes(context -> {
                                    info.pause();
                                    context.getSource().sendSuccess(() -> Component.literal("Redstone debugger: " + info.getState()), true);
                                    return 1;
                                }))
                        .then(Commands.literal("resume")
                                .executes(context -> {
                                    info.resume();
                                    context.getSource().sendSuccess(() -> Component.literal("Redstone debugger: " + info.getState()), true);
                                    return 1;
                                }))
                        .then(Commands.literal("checkStatus")
                                .executes(context -> {
                                    context.getSource().sendSuccess(() -> Component.literal(info.getState()), false);
                                    return 1;
                                }))
        );
    }
}
