package name.modid.selection;

import name.modid.RedstoneDebugger;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client to server: the player clicked {@code pos} with the wand, possibly from far away. */
public record WandClickPayload(BlockPos pos, boolean rightClick) implements CustomPacketPayload {

  /** The packet's ID on the wire: redstone-debugger:wand_click */
  public static final Type<WandClickPayload> TYPE = new Type<>(RedstoneDebugger.id("wand_click"));

  /** How to write/read each field, in order. Both sides must use the same codec. */
  public static final StreamCodec<RegistryFriendlyByteBuf, WandClickPayload> CODEC = StreamCodec.composite(
      BlockPos.STREAM_CODEC, WandClickPayload::pos,
      ByteBufCodecs.BOOL, WandClickPayload::rightClick,
      WandClickPayload::new                                     // rebuilds the record on the receiving side
  );

  @Override
  public Type<WandClickPayload> type() {
    return TYPE;
  }
}
