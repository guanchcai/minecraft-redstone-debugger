package name.modid.client;

import name.modid.debug.ExtentsPayload;
import name.modid.venv.VenvManager;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.phys.AABB;

/** Draws the step-back recorder's extents in the VENV as a green outline. */
public final class ExtentsRenderer {
    private static final int STROKE = 0xFF40FF40;          // opaque green (selection box is orange)
    private static final float STROKE_WIDTH = 2.0F;
    private static final double INFLATE = 0.02;            // a bit bigger than the selection box, so both show

    /** The latest extents the server sent us. */
    private static ExtentsPayload extents = ExtentsPayload.EMPTY;

    private ExtentsRenderer() {
    }

    public static void initialize() {
        ClientPlayNetworking.registerGlobalReceiver(ExtentsPayload.TYPE,
                (payload, context) -> extents = payload);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> extents = ExtentsPayload.EMPTY);
        ClientTickEvents.END_CLIENT_TICK.register(ExtentsRenderer::draw);
    }

    /** Gizmos last one tick, so this re-adds the outline every client tick. */
    private static void draw(Minecraft client) {
        if (client.level == null || client.level.dimension() != VenvManager.VENV) {
            return; // only meaningful inside the VENV
        }
        extents.box().ifPresent(box ->
                Gizmos.cuboid(AABB.of(box).inflate(INFLATE), GizmoStyle.stroke(STROKE, STROKE_WIDTH)));
    }
}
