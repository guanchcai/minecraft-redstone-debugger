package name.modid.client;

import com.mojang.blaze3d.vertex.PoseStack;
import name.modid.selection.SelectionPayload;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Draws the player's selection: a highlighted box, corner 1 in red, corner 2 in blue,
 * and the box size floating above it on a dark background.
 * The box and corners are gizmos, re-added every tick.
 */
public final class SelectionRenderer {
  // Selection box
  private static final int BOX_STROKE = 0xFFFFAA00;             // opaque orange outline
  private static final int BOX_FILL = 0x18FFAA00;               // ~10% orange tint on the faces
  private static final float BOX_STROKE_WIDTH = 2.5F;
  private static final double BOX_INFLATE = 0.01;               // slightly bigger, so block faces don't hide it

  // Corner markers
  private static final int POS1_COLOR = 0xFFFF4040;             // red
  private static final int POS2_COLOR = 0xFF4080FF;             // blue
  private static final float CORNER_STROKE_WIDTH = 3.0F;
  private static final float CORNER_PADDING = 0.03F;            // outline sits just outside the block

  // Size label
  private static final int LABEL_TEXT = BOX_STROKE;             // same orange as the box
  private static final int LABEL_BACKGROUND = 0x80000000;       // 50% black (name tags default to 25%)
  private static final double LABEL_HEIGHT = 1.5;               // blocks above the top of the box
  private static final float LABEL_PIXEL_SIZE = 0.0375F;        // blocks per font pixel (name tags: 0.025)

  /** The latest selection the server sent us. */
  private static SelectionPayload selection = SelectionPayload.EMPTY;

  private SelectionRenderer() {
  }

  public static void initialize() {
    ClientPlayNetworking.registerGlobalReceiver(SelectionPayload.TYPE,
        (payload, context) -> selection = payload); // runs on the client thread: safe to assign
    ClientPlayConnectionEvents.DISCONNECT.register( // leaving a world forgets its selection
        (handler, client) -> selection = SelectionPayload.EMPTY);
    ClientTickEvents.END_CLIENT_TICK.register(SelectionRenderer::draw);
    LevelRenderEvents.COLLECT_SUBMITS.register(SelectionRenderer::drawLabel);
  }

  /** Box and corners, as gizmos. Runs every client tick. */
  private static void draw(Minecraft client) {
    if (!isVisible(client)) {
      return;
    }
    selection.pos1().ifPresent(pos -> drawCorner(pos, POS1_COLOR));
    selection.pos2().ifPresent(pos -> drawCorner(pos, POS2_COLOR));

    @Nullable BoundingBox box = selectedBox();
    if (box != null) {
      Gizmos.cuboid(AABB.of(box).inflate(BOX_INFLATE),
          GizmoStyle.strokeAndFill(BOX_STROKE, BOX_STROKE_WIDTH, BOX_FILL));
    }
  }

  /** The size label, as name-tag style text. Runs every frame. */
  private static void drawLabel(LevelRenderContext context) {
    Minecraft client = Minecraft.getInstance();
    @Nullable BoundingBox box = selectedBox();
    if (!isVisible(client) || box == null) {
      return;
    }
    Vec3 labelPos = new Vec3(
        (box.minX() + box.maxX() + 1) / 2.0,                    // +1: max is the block's low edge
        box.maxY() + LABEL_HEIGHT,
        (box.minZ() + box.maxZ() + 1) / 2.0);
    CameraRenderState camera = context.levelState().cameraRenderState;

    PoseStack poseStack = new PoseStack();                      // starts at the camera
    poseStack.translate(labelPos.x - camera.pos.x, labelPos.y - camera.pos.y, labelPos.z - camera.pos.z);
    poseStack.rotate(camera.orientation);                       // billboard: face the camera
    poseStack.scale(LABEL_PIXEL_SIZE, -LABEL_PIXEL_SIZE, LABEL_PIXEL_SIZE); // -Y: font Y points down

    FormattedCharSequence text = Component.literal(
        box.getXSpan() + " x " + box.getYSpan() + " x " + box.getZSpan()).getVisualOrderText();
    float x = -client.font.width(text) / 2.0F;                  // centre horizontally
    context.submitNodeCollector().submitText(poseStack, x, 0, text,
        false,                                                  // no drop shadow
        Font.DisplayMode.SEE_THROUGH,                           // readable through walls
        LightCoordsUtil.FULL_BRIGHT,                            // not darkened at night or in caves
        LABEL_TEXT, LABEL_BACKGROUND,
        0);                                                     // no outline
  }

  private static void drawCorner(BlockPos pos, int color) {
    Gizmos.cuboid(pos, CORNER_PADDING, GizmoStyle.stroke(color, CORNER_STROKE_WIDTH))
        .setAlwaysOnTop();                                      // corners visible through walls
  }

  /** True if there is a selection in the dimension we're in. */
  private static boolean isVisible(Minecraft client) {
    return client.level != null                                 // not on the title screen / loading
        && selection.dimension().isPresent()                    // something is selected
        && selection.dimension().get() == client.level.dimension(); // and it's in this dimension
  }

  /** The selected box, or null if a corner is missing. */
  private static @Nullable BoundingBox selectedBox() {
    return selection.pos1().isPresent() && selection.pos2().isPresent()
        ? BoundingBox.fromCorners(selection.pos1().get(), selection.pos2().get())
        : null;
  }
}
