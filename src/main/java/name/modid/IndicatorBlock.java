package name.modid;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.redstone.Orientation;

/**
 * A block with two independent inputs, giving 4 block states:
 * <ul>
 *   <li>{@code redstone_powered}: true while any normal redstone signal reaches the block</li>
 *   <li>{@code example_powered}: true while an Example Block touches any of its 6 sides</li>
 * </ul>
 */
public class IndicatorBlock extends Block {
    public static final BooleanProperty REDSTONE_POWERED = BooleanProperty.create("redstone_powered");
    public static final BooleanProperty EXAMPLE_POWERED = BooleanProperty.create("example_powered");

    public IndicatorBlock(BlockBehaviour.Properties properties) {
        super(properties);
        // The state the block starts in before anything else decides otherwise.
        registerDefaultState(defaultBlockState()
                .setValue(REDSTONE_POWERED, false)
                .setValue(EXAMPLE_POWERED, false));
    }

    // Declares which properties this block has. Minecraft creates one BlockState
    // for every combination: 2 booleans = 2 x 2 = 4 states.
    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(REDSTONE_POWERED, EXAMPLE_POWERED);
    }

    // Called when a player places the block, so it starts in the correct state.
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return withCurrentInputs(defaultBlockState(), context.getLevel(), context.getClickedPos());
    }

    // Called whenever an adjacent block changes: redstone turning on/off,
    // or a block (e.g. an Example Block) being placed or broken next to it.
    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighborBlock,
                                   Orientation orientation, boolean movedByPiston) {
        if (level.isClientSide()) {
            return; // The server decides the state; clients receive it automatically.
        }
        BlockState updated = withCurrentInputs(state, level, pos);
        if (updated != state) {
            level.setBlock(pos, updated, Block.UPDATE_CLIENTS);
        }
    }

    private static BlockState withCurrentInputs(BlockState state, Level level, BlockPos pos) {
        return state
                .setValue(REDSTONE_POWERED, level.hasNeighborSignal(pos))
                .setValue(EXAMPLE_POWERED, isTouchingExampleBlock(level, pos));
    }

    private static boolean isTouchingExampleBlock(Level level, BlockPos pos) {
        for (Direction direction : Direction.values()) {
            if (level.getBlockState(pos.relative(direction)).is(ModBlocks.TEST_BLOCK)) {
                return true;
            }
        }
        return false;
    }
}
