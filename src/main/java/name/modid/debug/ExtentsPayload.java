package name.modid.debug;

import io.netty.buffer.ByteBuf;
import java.util.Optional;
import name.modid.RedstoneDebugger;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * Server to client: the area the step-back recorder watches (the controller's extents),
 * so the client can draw it. Empty means "nothing recorded".
 */
public record ExtentsPayload(Optional<BoundingBox> box) implements CustomPacketPayload {

    /** The packet's ID on the wire: redstone-debugger:extents */
    public static final Type<ExtentsPayload> TYPE = new Type<>(RedstoneDebugger.id("extents"));

    public static final StreamCodec<ByteBuf, ExtentsPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.optional(BoundingBox.STREAM_CODEC), ExtentsPayload::box,
            ExtentsPayload::new
    );

    public static final ExtentsPayload EMPTY = new ExtentsPayload(Optional.empty());

    @Override
    public Type<ExtentsPayload> type() {
        return TYPE;
    }
}
