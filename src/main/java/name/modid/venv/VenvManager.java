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
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;
import net.fabricmc.fabric.api.gamerule.v1.GameRuleBuilder;
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.gamerules.GameRuleCategory;

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

  /** Forces this class to load during mod init. */
  public static void initialize() {
  }

  /** How far west of the contraption the viewing platform is */
  private static final int PLATFORM_DISTANCE = 5;

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
    ContraptionSnapshot snapshot = ContraptionSnapshot.capture(source, box);
    snapshot.place(venv, new BlockPos(box.minX(), box.minY(), box.minZ())); // same coords as original
    buildViewingPlatform(venv, box);
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
}