package com.example.sigils;

import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

public final class ModItems {
    private static final List<Item> ALL = new ArrayList<>();

    public static final Item BOLT      = register("bolt_sigil",      Sigils::bolt);
    public static final Item FLOW      = register("flow_sigil",      Sigils::flow);
    public static final Item VEX       = register("vex_sigil",       Sigils::vex);
    public static final Item WAYFINDER = register("wayfinder_sigil", Sigils::wayfinder);
    public static final Item WARD      = register("ward_sigil",      Sigils::ward);
    public static final Item TIDE      = register("tide_sigil",      Sigils::tide);
    public static final Item SILENCE   = register("silence_sigil",   Sigils::silence);
    public static final Item SPIRE     = register("spire_sigil",     Sigils::spire);
    public static final Item RAISER    = register("raiser_sigil",    Sigils::raiser);
    public static final Item DUNE      = register("dune_sigil",       Sigils::dune);

    private ModItems() {}

    /** 1.21.2+: the registry key must be set on the Settings before the item is constructed. */
    private static <T extends Item> T register(String id, Function<Item.Settings, T> factory) {
        RegistryKey<Item> key = RegistryKey.of(RegistryKeys.ITEM, Identifier.of(SigilsMod.MOD_ID, id));
        // Always enchanted-looking, like a trim template that has been awakened.
        Item.Settings settings = new Item.Settings().registryKey(key)
                .component(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, Boolean.TRUE);
        T item = Registry.register(Registries.ITEM, key, factory.apply(settings));
        ALL.add(item);
        return item;
    }

    public static void init() {
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.COMBAT).register(entries -> ALL.forEach(entries::add));
    }
}
