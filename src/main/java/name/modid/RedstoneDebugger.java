package name.modid;

import name.modid.command.RdbCommand;
import name.modid.debug.RedstoneDebuggerController;
import name.modid.debug.RedstoneDebugCommand;
import name.modid.selection.SelectionManager;
import name.modid.selection.SelectionWand;
import name.modid.venv.VenvManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RedstoneDebugger implements ModInitializer {
	public static final String MOD_ID = "redstone-debugger";

	// This logger is used to write text to the console and the log file.
	// It is considered best practice to use your mod id as the logger's name.
	// That way, it's clear which mod wrote info, warnings, and errors.
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
	public static final RedstoneDebuggerController CONTROLLER = new RedstoneDebuggerController();

	@Override
	public void onInitialize() {
		// This code runs as soon as Minecraft is in a mod-load-ready state.
		// However, some things (like resources) may still be uninitialized.
		// Proceed with mild caution.
		ModBlocks.initialize();
		VenvManager.initialize();
		SelectionManager.initialize();
		ModItems.initialize();
		SelectionWand.initialize();

		// Redstone pause / slow-down / step
		ServerTickEvents.START_SERVER_TICK.register((server) -> CONTROLLER.onServerTickStart());
		ServerLifecycleEvents.SERVER_STARTING.register((server) -> CONTROLLER.reset());
		ServerLifecycleEvents.SERVER_STOPPED.register((server) -> CONTROLLER.onServerStopped());

		// Both register under /rdb and Brigadier merges them into one command. The FIRST registration's
		// .requires(...) wins for the shared /rdb node, so RdbCommand (op-only) must stay first.
		CommandRegistrationCallback.EVENT.register(
			(dispatcher, buildContext, selection) -> {
				RdbCommand.register(dispatcher);
				RedstoneDebugCommand.register(dispatcher, CONTROLLER);
			}
		);

		ServerLifecycleEvents.SERVER_STOPPED.register(server -> VenvManager.onServerStopped());
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> SelectionManager.onServerStopped());

		LOGGER.info("Hello Fabric world!");
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
