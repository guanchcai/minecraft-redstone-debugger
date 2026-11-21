package name.modid.selection;

import java.util.Optional;
import name.modid.RedstoneDebugger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * Server to client: the player's current selection, so the client can draw it.
 * An empty dimension means "no selection".
 */
public record SelectionPayload(
    Optional<ResourceKey<Level>> dimension,
    Optional<BlockPos> pos1,
    Optional<BlockPos> pos2
) implements CustomPacketPayload {

  /** The packet's ID on the wire: redstone-debugger:selection */
  public static final Type<SelectionPayload> TYPE = new Type<>(RedstoneDebugger.id("selection"));

  /** How to write/read each field, in order. Both sides must use the same codec. */
  public static final StreamCodec<RegistryFriendlyByteBuf, SelectionPayload> CODEC = StreamCodec.composite(
      ByteBufCodecs.optional(ResourceKey.streamCodec(Registries.DIMENSION)), SelectionPayload::dimension,
      ByteBufCodecs.optional(BlockPos.STREAM_CODEC), SelectionPayload::pos1,
      ByteBufCodecs.optional(BlockPos.STREAM_CODEC), SelectionPayload::pos2,
      SelectionPayload::new                                     // rebuilds the record on the receiving side
  );

  /** "Nothing selected". */
  public static final SelectionPayload EMPTY =
      new SelectionPayload(Optional.empty(), Optional.empty(), Optional.empty());

  public static SelectionPayload of(Selection selection) {
    return new SelectionPayload(
        Optional.of(selection.dimension()),
        Optional.ofNullable(selection.pos1()), // null corner -> Optional.empty()
        Optional.ofNullable(selection.pos2()));
  }

  @Override
  public Type<SelectionPayload> type() {
    return TYPE;
  }
}