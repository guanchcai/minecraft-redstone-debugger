package name.modid.selection;

import name.modid.RedstoneDebugger;
import net.minecraft.core.Direction;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client to server: the player scrolled with the wand, to push ({@code outward}) or pull {@code face} by 1. */
public record WandScrollPayload(Direction face, boolean outward) implements CustomPacketPayload {

  /** The packet's ID on the wire: redstone-debugger:wand_scroll */
  public static final Type<WandScrollPayload> TYPE = new Type<>(RedstoneDebugger.id("wand_scroll"));

  /** How to write/read each field, in order. Both sides must use the same codec. */
  public static final StreamCodec<RegistryFriendlyByteBuf, WandScrollPayload> CODEC = StreamCodec.composite(
      Direction.STREAM_CODEC, WandScrollPayload::face,
      ByteBufCodecs.BOOL, WandScrollPayload::outward,
      WandScrollPayload::new
  );

  @Override
  public Type<WandScrollPayload> type() {
    return TYPE;
  }
}
