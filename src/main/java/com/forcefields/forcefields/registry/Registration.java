package com.forcefields.forcefields.registry;

import com.forcefields.forcefields.ForceFields;
import com.forcefields.forcefields.block.FieldProjectorBlock;
import com.forcefields.forcefields.block.FieldProjectorBlockEntity;

import com.forcefields.forcefields.item.ForceCoreItem;
import com.forcefields.forcefields.item.UpgradeItem;
import com.forcefields.forcefields.menu.FieldProjectorMenu;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class Registration {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(ForceFields.MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(ForceFields.MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, ForceFields.MODID);
    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(Registries.MENU, ForceFields.MODID);
    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, ForceFields.MODID);

    public static final DeferredBlock<FieldProjectorBlock> FIELD_PROJECTOR = BLOCKS.register("field_projector",
            () -> new FieldProjectorBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_CYAN)
                    .strength(10.0F, 60.0F)
                    .sound(SoundType.NETHERITE_BLOCK)
                    .noOcclusion()
                    .lightLevel(state -> 8)));

    public static final DeferredItem<BlockItem> FIELD_PROJECTOR_ITEM = ITEMS.registerSimpleBlockItem("field_projector", FIELD_PROJECTOR);

    public static final DeferredItem<ForceCoreItem> FORCE_CORE_BASIC =
            ITEMS.registerItem("force_core_basic", properties -> new ForceCoreItem(properties, ForceCoreItem.Tier.BASIC));
    public static final DeferredItem<ForceCoreItem> FORCE_CORE_ADVANCED =
            ITEMS.registerItem("force_core_advanced", properties -> new ForceCoreItem(properties, ForceCoreItem.Tier.ADVANCED));
    public static final DeferredItem<ForceCoreItem> FORCE_CORE_ELITE =
            ITEMS.registerItem("force_core_elite", properties -> new ForceCoreItem(properties, ForceCoreItem.Tier.ELITE));
    public static final DeferredItem<UpgradeItem> RESISTANCE_UPGRADE =
            ITEMS.registerItem("resistance_upgrade", properties -> new UpgradeItem(properties, UpgradeItem.Type.RESISTANCE));
    /** The Aegis upgrade has no recipe: creative players take it from the tab. */
    public static final DeferredItem<UpgradeItem> AEGIS_UPGRADE =
            ITEMS.registerItem("aegis_upgrade", properties -> new UpgradeItem(properties, UpgradeItem.Type.AEGIS));
    public static final DeferredItem<UpgradeItem> VEIL_UPGRADE =
            ITEMS.registerItem("veil_upgrade", properties -> new UpgradeItem(properties, UpgradeItem.Type.VEIL));

    public static final DeferredHolder<MenuType<?>, MenuType<FieldProjectorMenu>> FIELD_PROJECTOR_MENU =
            MENUS.register("field_projector", () -> IMenuTypeExtension.create(FieldProjectorMenu::new));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<FieldProjectorBlockEntity>> FIELD_PROJECTOR_BE =
            BLOCK_ENTITIES.register("field_projector",
                    () -> BlockEntityType.Builder.of(FieldProjectorBlockEntity::new, FIELD_PROJECTOR.get()).build(null));

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TAB = TABS.register("main",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.forcefields"))
                    .icon(() -> FIELD_PROJECTOR_ITEM.get().getDefaultInstance())
                    .displayItems((parameters, output) -> {
                        output.accept(FIELD_PROJECTOR_ITEM.get());
                        output.accept(FORCE_CORE_BASIC.get());
                        output.accept(FORCE_CORE_ADVANCED.get());
                        output.accept(FORCE_CORE_ELITE.get());
                        output.accept(RESISTANCE_UPGRADE.get());
                        output.accept(VEIL_UPGRADE.get());
                        output.accept(AEGIS_UPGRADE.get());
                    })
                    .build());

    public static void register(IEventBus modEventBus) {
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        BLOCK_ENTITIES.register(modEventBus);
        MENUS.register(modEventBus);
        TABS.register(modEventBus);
    }

    private Registration() {
    }
}
