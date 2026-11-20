package name.modid.venv;

import name.modid.RedstoneDebugger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;
import net.fabricmc.fabric.api.gamerule.v1.GameRuleBuilder;
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.gamerules.GameRuleCategory;
import java.util.List;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
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

  /** Forces this class to load during mod init, registering gamerule and attachment type. */
  public static void initialize() {
  }

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

  private VenvManager() { // static-only utility class
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

  /**
   * Copies {@code box} from {@code source} into the VENV at the same coordinates
   * and builds a platform to view it from.
   */
  public static ContraptionSnapshot capture(ServerLevel source, BoundingBox box) {
    ServerLevel venv = venv(source.getServer());
    ContraptionSnapshot snapshot = ContraptionSnapshot.capture(source, box); // read first: if this fails, VENV is untouched
    wipe(venv);                                                 // remove the previous contraption
    snapshot.place(venv, new BlockPos(box.minX(), box.minY(), box.minZ())); // same coords as original
    buildViewingPlatform(venv, box);
    venv.setAttached(OCCUPIED_BOX, box);                        // remember it, so the next capture can wipe it
    return snapshot;
  }

  /** Teleports the player onto the viewing platform, facing the contraption. */
  public static void teleportToContraption(ServerPlayer player, BoundingBox box) {
    BlockPos stand = viewingPlatformCenter(box).above();        // air block above the glass
    player.teleport(new TeleportTransition(
        venv(player.level().getServer()),
        Vec3.atBottomCenterOf(stand),                           // block center
        Vec3.ZERO,                                              // no momentum after teleporting
        -90.0F,                                                 // yaw: -90 = facing east (+X)
        0.0F,                                                   // pitch (straight ahead)
        TeleportTransition.DO_NOTHING));                        // no portal sfx
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

  /** The box occupied by the contraption currently in the VENV, or null if the VENV is empty. */
  public static @Nullable BoundingBox currentBox(MinecraftServer server) {
    return venv(server).getAttached(OCCUPIED_BOX);
  }

  /**
   * Empties the VENV, sending any players inside to their respawn point.
   * returns false if the VENV was already empty.
   */
  public static boolean clear(MinecraftServer server) {
    ServerLevel venv = venv(server);
    if (currentBox(server) == null) {
      return false;
    }
    for (ServerPlayer player : List.copyOf(venv.players())) {  // copy: tp removes them from venv.players()
      player.teleport(player.findRespawnPositionAndUseSpawnBlock(false, TeleportTransition.DO_NOTHING));
    }
    wipe(venv);
    return true;
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
}