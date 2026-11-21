package name.modid.client;

import net.fabricmc.api.ClientModInitializer;

public class RedstoneDebuggerClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		SelectionRenderer.initialize();
		WandInput.initialize();
	}
}