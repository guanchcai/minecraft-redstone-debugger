package name.modid.command;

import java.util.Locale;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;

/** A direction typed in /rdb commands: relative to where the player looks, or absolute. */
enum DirectionWord {
  FORWARD, BACK, LEFT, RIGHT,                                   // relative to the player
  UP, DOWN, NORTH, SOUTH, EAST, WEST;                           // absolute

  /** As typed in commands, e.g. "forward". */
  String word() {
    return name().toLowerCase(Locale.ROOT);                     // ROOT: "UP" -> "up" in every locale
  }

  /** The actual direction this word means for {@code entity}. */
  Direction resolve(Entity entity) {
    return switch (this) {
      case FORWARD -> entity.getNearestViewDirection();         // includes up/down when looking steeply
      case BACK -> entity.getNearestViewDirection().getOpposite();
      case LEFT -> entity.getDirection().getCounterClockWise(); // getDirection: horizontal only
      case RIGHT -> entity.getDirection().getClockWise();
      case UP -> Direction.UP;
      case DOWN -> Direction.DOWN;
      case NORTH -> Direction.NORTH;
      case SOUTH -> Direction.SOUTH;
      case EAST -> Direction.EAST;
      case WEST -> Direction.WEST;
    };
  }
}
