package name.modid;

import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;

import java.util.function.Function;

public class ModBlocks {
    public static final String MOD_ID = "redstone-debugger";

    public static final Block TEST_BLOCK = register(
            "example_block",
            Block::new,
            BlockBehaviour.Properties.of()
                    .strength(1.0F)
                    .sound(SoundType.STONE)
    );

    public static final Block PROBE_BLOCK = register(
            "probe_block",
            Block::new,
            BlockBehaviour.Properties.of()
                    .strength(1.5F)
                    .sound(SoundType.METAL)
    );

    public static final Block INDICATOR_BLOCK = register(
            "indicator_block",
            IndicatorBlock::new,
            BlockBehaviour.Properties.of()
                    .strength(1.0F)
                    .sound(SoundType.STONE)
    );

    private static Block register(String name, Function<BlockBehaviour.Properties, Block> factory, BlockBehaviour.Properties properties) {
        Identifier id = Identifier.fromNamespaceAndPath(MOD_ID, name);

        // Register the block
        ResourceKey<Block> blockKey = ResourceKey.create(Registries.BLOCK, id);
        Block block = factory.apply(properties.setId(blockKey));
        Registry.register(BuiltInRegistries.BLOCK, blockKey, block);

        // Register the item version, so you can hold it in your inventory
        ResourceKey<Item> itemKey = ResourceKey.create(Registries.ITEM, id);
        Item.Properties itemProperties = new Item.Properties().setId(itemKey).useBlockDescriptionPrefix();
        Registry.register(BuiltInRegistries.ITEM, itemKey, new BlockItem(block, itemProperties));

        return block;
    }

    public static void initialize() {
        // Show the block in the Building Blocks tab of the creative inventory
        CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.BUILDING_BLOCKS)
                .register(entries -> {
                    entries.accept(TEST_BLOCK);
                    entries.accept(PROBE_BLOCK);
                    entries.accept(INDICATOR_BLOCK);
                });
    }}
