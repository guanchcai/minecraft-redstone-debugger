package name.modid;

import java.util.function.Function;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;

/** The mod's items. */
public final class ModItems {
  /** The selection wand: left-click a block for corner 1, right-click for corner 2 (see SelectionWand). */
  public static final Item RDB_WAND = register("rdb_wand", Item::new, new Item.Properties().stacksTo(1));

  private ModItems() { // static-only utility class
  }

  /** Forces this class to load during mod init, and adds the items to the creative inventory. */
  public static void initialize() {
    CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.TOOLS_AND_UTILITIES) // the "Tools & Utilities" tab
        .register(entries -> entries.accept(RDB_WAND));
  }

  private static Item register(String name, Function<Item.Properties, Item> factory, Item.Properties properties) {
    ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, RedstoneDebugger.id(name));
    // ID before construction
    return Registry.register(BuiltInRegistries.ITEM, key, factory.apply(properties.setId(key)));
  }
}
