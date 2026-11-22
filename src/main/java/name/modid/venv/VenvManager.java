package name.modid.venv;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import name.modid.RedstoneDebugger;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.gamerule.v1.GameRuleBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.gamerules.GameRuleCategory;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Copies contraptions into the VENV dimension and moves players to them.
 * A contraption is placed at the same coordinates in the VENV as in its source dimension.
 */
public final class VenvManager {
  /** Key for the dimension defined in data/redstone-debugger/dimension/venv.json */
  public static final ResourceKey<Level> VENV =
      ResourceKey.create(Registries.DIMENSION, RedstoneDebugger.id("venv"));

  /** Largest capturable region, in blocks. */
  public static final GameRule<Integer> MAX_CAPTURE_VOLUME = GameRuleBuilder
      .forInteger(64 * 64 * 64)                                 // default (64-block cube)
      .minValue(1)
      .category(GameRuleCategory.MISC)                          // section in the Edit Game Rules screen
      .buildAndRegister(RedstoneDebugger.id("max_capture_volume"));

  /** How far west of the contraption the viewing platform is */
  private static final int PLATFORM_DISTANCE = 5;

  /**
   * Extra blocks around the box that are wiped along with it.
   * Catches things the contraption pushed or spilled just outside its box.
   */
  private static final int CLEANUP_MARGIN = 2;

  /** The box the current contraption occupies in the VENV, saved with the world. Absent when empty. */
  private static final AttachmentType<BoundingBox> OCCUPIED_BOX = AttachmentRegistry.create(
      RedstoneDebugger.id("venv_contraption_box"),
      builder -> builder.persistent(BoundingBox.CODEC));        // persistent: written to the save file

  /** Where a player was before entering the VENV. */
  private record ReturnPoint(ResourceKey<Level> dimension, Vec3 position, float yRot, float xRot) {
  }

  /** Return points by player. In memory only: after a restart, /rdb return falls back to respawn. */
  private static final Map<UUID, ReturnPoint> RETURN_POINTS = new HashMap<>();

  /** The current contraption as captured, for /rdb reset. In memory only; null after a restart. */
  private static @Nullable ContraptionSnapshot snapshot;

  private VenvManager() { // static-only utility class
  }

  /** Forces this class to load during mod init, registering gamerule and attachment type. */
  public static void initialize() {
  }

  /** The VENV level. Throws if the dimension is missing (e.g. venv.json failed to load). */
  public static ServerLevel venv(MinecraftServer server) {
    ServerLevel level = server.getLevel(VENV);
    if (level == null) {
      throw new IllegalStateException("VENV dimension " + VENV.identifier() + " is missing");
    }
    return level;
  }

  public static boolean isVenv(Level level) {
    return level.dimension() == VENV;
  }

  /** The box occupied by the contraption currently in the VENV, or null if the VENV is empty. */
  public static @Nullable BoundingBox currentBox(MinecraftServer server) {
    return venv(server).getAttached(OCCUPIED_BOX);
  }

  /**
   * Copies {@code box} from {@code source} into the VENV at the same coordinates
   * and builds a platform to view it from. Replaces any present contraption.
   */
  public static ContraptionSnapshot capture(ServerLevel source, BoundingBox box) {
    // read first: if this fails, VENV is untouched
    ContraptionSnapshot captured = ContraptionSnapshot.capture(source, box);
    install(venv(source.getServer()), captured, box);
    return captured;
  }

  /**
   * Puts the VENV contraption back exactly as it was captured.
   *
   * @return false if there is nothing to reset to (VENV empty, or the game restarted since capture)
   */
  public static boolean reset(MinecraftServer server) {
    BoundingBox box = currentBox(server);
    if (snapshot == null || box == null) {
      return false;
    }
    install(venv(server), snapshot, box);  // same path as capture
    return true;
  }

  /**
   * Empties the VENV, sending any players inside to their previous position.
   *
   * @return false if the VENV was already empty
   */
  public static boolean clear(MinecraftServer server) {
    ServerLevel venv = venv(server);
    if (currentBox(server) == null) {
      return false;
    }
    for (ServerPlayer player : List.copyOf(venv.players())) {  // copy: leaving removes them from venv.players()
      leave(player);
    }
    wipe(venv);
    snapshot = null; // nothing left to reset to
    RedstoneDebugger.CONTROLLER.clearSnapshots(); // Reset snapshot history
    return true;
  }

  /**
   * Teleports the player to the contraption, remembering where they came from.
   *
   * @return false if the VENV is empty
   */
  public static boolean enter(ServerPlayer player) {
    BoundingBox box = currentBox(player.level().getServer());
    if (box == null) {
      return false;
    }
    if (!isVenv(player.level())) {                              // already inside: keep the original return point
      RETURN_POINTS.put(player.getUUID(), new ReturnPoint(
          player.level().dimension(), player.position(), player.getYRot(), player.getXRot()));
    }
    teleportToContraption(player, box);
    return true;
  }

  /**
   * Sends the player back to where they entered the VENV from,
   * or to their respawn point if that's unknown (e.g. after a restart).
   *
   * @return false if the player isn't in the VENV
   */
  public static boolean leave(ServerPlayer player) {
    @Nullable ReturnPoint point = RETURN_POINTS.remove(player.getUUID()); // remove: each return point is used once
    if (!isVenv(player.level())) {
      return false;                                             // e.g. they already /tp'd out: drop the stale point
    }
    @Nullable ServerLevel level = point == null ? null : player.level().getServer().getLevel(point.dimension());
    if (level == null) {                                        // no record, or that dimension no longer exists
      player.teleport(player.findRespawnPositionAndUseSpawnBlock(false, TeleportTransition.DO_NOTHING));
    } else {
      player.teleport(new TeleportTransition(
          level, point.position(), Vec3.ZERO, point.yRot(), point.xRot(), TeleportTransition.DO_NOTHING));
    }
    return true;
  }

  /** Forgets in-memory state, so it can't leak into the next world opened in singleplayer. */
  public static void onServerStopped() {
    snapshot = null;
    RETURN_POINTS.clear();
  }

  /** Replaces the VENV's contraption with {@code contraption}, placed at {@code box}. */
  private static void install(ServerLevel venv, ContraptionSnapshot contraption, BoundingBox box) {
    wipe(venv);                             // also removes spilled items, pushed blocks etc.
    contraption.place(venv, new BlockPos(box.minX(), box.minY(), box.minZ())); // og coords
    buildViewingPlatform(venv, box);
    venv.setAttached(OCCUPIED_BOX, box);    // saved: lets the next capture/clear find it
    RedstoneDebugger.CONTROLLER.startDebug(); // initialise debug controller
    snapshot = contraption;                 // in memory: lets /rdb reset find it
  }

  /** Teleports the player onto the viewing platform, facing the contraption. */
  private static void teleportToContraption(ServerPlayer player, BoundingBox box) {
    BlockPos stand = viewingPlatformCenter(box).above();        // air block above the glass
    player.teleport(new TeleportTransition(
        venv(player.level().getServer()),
        Vec3.atBottomCenterOf(stand),                           // block center
        Vec3.ZERO,                                              // no momentum after teleporting
        -90.0F,                                                 // yaw: -90 = facing east (+X)
        0.0F,                                                   // pitch (straight ahead)
        TeleportTransition.DO_NOTHING));                        // no portal sfx
  }

  /** Silently empties the area used by the current contraption: blocks, pending ticks and entities. */
  private static void wipe(ServerLevel venv) {
    BoundingBox box = venv.getAttached(OCCUPIED_BOX);
    if (box == null) {
      return;                                                   // nothing recorded, nothing to wipe
    }
    BoundingBox area = usedArea(box);

    BlockState air = Blocks.AIR.defaultBlockState();
    for (BlockPos pos : BlockPos.betweenClosed(
        area.minX(), area.minY(), area.minZ(), area.maxX(), area.maxY(), area.maxZ())) {
      if (!venv.getBlockState(pos).isAir()) {                   // skip air: most of the area is empty
        venv.setBlock(pos, air, ContraptionSnapshot.SILENT_PLACE); // no drops, no spilled hoppers, no reactions
      }
    }
    venv.getBlockTicks().clearArea(area);                       // pending repeater/torch updates
    venv.getFluidTicks().clearArea(area);                       // pending water/lava flow

    for (Entity entity : venv.getEntities((Entity) null, AABB.of(area),
        entity -> !(entity instanceof Player))) {               // items, minecarts, falling blocks; not players
      entity.discard();                                         // remove without killing (no death drops)
    }

    venv.removeAttached(OCCUPIED_BOX);
  }

  /** Contraption box + cleanup margin + viewing platform. */
  private static BoundingBox usedArea(BoundingBox box) {
    BoundingBox area = box.inflatedBy(CLEANUP_MARGIN);
    BlockPos platform = viewingPlatformCenter(box);
    return new BoundingBox(
        Math.min(area.minX(), platform.getX() - 1),
        Math.min(area.minY(), platform.getY()),
        Math.min(area.minZ(), platform.getZ() - 1),
        Math.max(area.maxX(), platform.getX() + 1),
        Math.max(area.maxY(), platform.getY()),
        Math.max(area.maxZ(), platform.getZ() + 1)
    );
  }

  /** 3x3 glass pad west of the contraption, so players don't fall into the void. */
  private static void buildViewingPlatform(ServerLevel venv, BoundingBox box) {
    BlockPos center = viewingPlatformCenter(box);
    for (BlockPos pos : BlockPos.betweenClosed(center.offset(-1, 0, -1), center.offset(1, 0, 1))) {
      if (venv.getBlockState(pos).isAir()) {                    // never overwrite existing blocks
        venv.setBlockAndUpdate(pos, Blocks.GLASS.defaultBlockState());
      }
    }
  }

  private static BlockPos viewingPlatformCenter(BoundingBox box) {
    return new BlockPos(
        box.minX() - PLATFORM_DISTANCE,                         // west of box
        box.minY() - 1,                                         // level with box floor
        box.getCenter().getZ());                                // centred along box
  }
}
