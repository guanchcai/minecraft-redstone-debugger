package name.modid;

import name.modid.command.RdbCommand;
import name.modid.selection.SelectionManager;
import name.modid.venv.VenvManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RedstoneDebugger implements ModInitializer {
	public static final String MOD_ID = "redstone-debugger";

	// This logger is used to write text to the console and the log file.
	// It is considered best practice to use your mod id as the logger's name.
	// That way, it's clear which mod wrote info, warnings, and errors.
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		// This code runs as soon as Minecraft is in a mod-load-ready state.
		// However, some things (like resources) may still be uninitialized.
		// Proceed with mild caution.
		ModBlocks.initialize();
		VenvManager.initialize();

		CommandRegistrationCallback.EVENT.register(
				(dispatcher, _, _) -> RdbCommand.register(dispatcher)
		);

		ServerLifecycleEvents.SERVER_STOPPED.register(server -> VenvManager.onServerStopped());
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> SelectionManager.onServerStopped());

		LOGGER.info("Hello Fabric world!");
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
