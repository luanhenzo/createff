package com.createff.createff.field;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

import org.jetbrains.annotations.Nullable;

import com.createff.createff.Config;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * Everything the owner can configure about a field. The server keeps one per field; clients get a copy
 * so they can refuse what the server would refuse instead of animating it first.
 *
 * <p>Interaction lists hold entries like {@code minecraft:lever}, {@code #minecraft:doors} or, for
 * blocks, with block state properties: {@code minecraft:oak_door[open=true]}, {@code #minecraft:doors[half=lower]}. A lone {@code *} matches everything.
 */
public final class FieldSettings {
    /** What the installed force core gives. Written by the projector when its inventory changes. */
    /** The power switch on the projector screen. Off keeps the field down even with a core and power. */
    private boolean enabled = true;
    private boolean coreInstalled;
    private int resistanceUpgrades;
    private int totalUpgrades;
    private boolean aegis;
    private boolean veil;
    private Access place = Access.OWNER;
    private Access breaking = Access.OWNER;
    private Access entry = Access.OWNER;
    private final Map<UUID, String> admins = new LinkedHashMap<>();
    private final Map<UUID, String> trusted = new LinkedHashMap<>();
    private final List<String> interactBlocks = new ArrayList<>();
    private final List<String> interactItems = new ArrayList<>();
    private final List<Gate> gates = new ArrayList<>();
    /** The id the next gate gets. Ids are never used twice in a field. */
    private int nextGateId = 1;

    /** Bumped on every change so the block entity knows when to resync clients. Not saved. */
    private int version;

    private List<Predicate<BlockState>> blockMatchers;
    private List<Predicate<ItemStack>> itemMatchers;

    public static FieldSettings defaults() {
        FieldSettings settings = new FieldSettings();
        settings.place = Config.DEFAULT_PLACE.get();
        settings.breaking = Config.DEFAULT_BREAK.get();
        settings.entry = Config.DEFAULT_ENTRY.get();
        return settings;
    }

    public int version() {
        return version;
    }

    public int resistanceUpgrades() {
        return resistanceUpgrades;
    }

    public int totalUpgrades() {
        return totalUpgrades;
    }

    /** The Aegis upgrade is installed: the field cannot be overloaded. */
    public boolean aegis() {
        return aegis;
    }

    /** The Veil upgrade is installed: the wall only shows to someone close to it or when it is attacked. */
    public boolean veil() {
        return veil;
    }

    public boolean enabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        if (enabled != this.enabled) {
            this.enabled = enabled;
            version++;
        }
    }

    /** Whether a force core sits in the projector. Without one the field cannot be on. */
    public boolean coreInstalled() {
        return coreInstalled;
    }

    public void setCore(boolean installed, int resistance, int total, boolean aegis, boolean veil) {
        if (installed != coreInstalled || resistance != resistanceUpgrades || total != totalUpgrades
                || aegis != this.aegis || veil != this.veil) {
            coreInstalled = installed;
            resistanceUpgrades = resistance;
            totalUpgrades = total;
            this.aegis = aegis;
            this.veil = veil;
            version++;
        }
    }

    // --- Access -----------------------------------------------------------------------------------------

    public Access access(FieldAction action) {
        return switch (action) {
            case PLACE -> place;
            case BREAK -> breaking;
            case ENTER -> entry;
        };
    }

    public void setAccess(FieldAction action, Access access) {
        switch (action) {
            case PLACE -> place = access;
            case BREAK -> breaking = access;
            case ENTER -> entry = access;
        }
        version++;
    }

    public Role roleOf(@Nullable UUID player, @Nullable UUID owner) {
        if (player == null) {
            return Role.OTHER;
        }
        if (player.equals(owner)) {
            return Role.OWNER;
        }
        if (admins.containsKey(player)) {
            return Role.ADMIN;
        }
        return trusted.containsKey(player) ? Role.TRUSTED : Role.OTHER;
    }

    public boolean allows(FieldAction action, Role role) {
        return access(action).allows(role);
    }

    // --- Admins -------------------------------------------------------------------------------------

    public Map<UUID, String> admins() {
        return admins;
    }

    /** Returns false if the player was already an admin. Promoting a trusted player moves them here. */
    public boolean addAdmin(UUID player, String name) {
        trusted.remove(player);
        boolean added = !admins.containsKey(player);
        admins.put(player, name);
        version++;
        return added;
    }

    public boolean removeAdmin(UUID player) {
        boolean removed = admins.remove(player) != null;
        if (removed) {
            version++;
        }
        return removed;
    }

    // --- Trusted players --------------------------------------------------------------------------------

    public Map<UUID, String> trusted() {
        return trusted;
    }

    /** Returns false if the player was already trusted (their name is still refreshed). */
    public boolean addTrusted(UUID player, String name) {
        boolean added = !trusted.containsKey(player);
        trusted.put(player, name);
        version++;
        return added;
    }

    public boolean removeTrusted(UUID player) {
        boolean removed = trusted.remove(player) != null;
        if (removed) {
            version++;
        }
        return removed;
    }

    // --- Gates ------------------------------------------------------------------------------------------

    /** The openings in the wall, in the order they were made. Change them only through the methods below. */
    public List<Gate> gates() {
        return gates;
    }

    /** Adds a gate, giving it the next id, and returns it with that id. */
    public Gate addGate(Gate gate) {
        Gate added = gate.withId(nextGateId++);
        gates.add(added);
        version++;
        return added;
    }

    /** The gate with this id, or null. */
    @Nullable
    public Gate gateById(int id) {
        for (Gate gate : gates) {
            if (gate.id() == id) {
                return gate;
            }
        }
        return null;
    }

    /** Replaces the gate that has the same id as {@code gate}; false if there is none. */
    public boolean setGateById(Gate gate) {
        for (int i = 0; i < gates.size(); i++) {
            if (gates.get(i).id() == gate.id()) {
                gates.set(i, gate);
                version++;
                return true;
            }
        }
        return false;
    }

    /** Replaces the gate at {@code index}; false if there is none. */
    public boolean setGate(int index, Gate gate) {
        if (index < 0 || index >= gates.size()) {
            return false;
        }
        gates.set(index, gate);
        version++;
        return true;
    }

    public boolean removeGate(int index) {
        if (index < 0 || index >= gates.size()) {
            return false;
        }
        gates.remove(index);
        version++;
        return true;
    }

    // --- Interaction lists ------------------------------------------------------------------------------

    public List<String> interactBlocks() {
        return interactBlocks;
    }

    public List<String> interactItems() {
        return interactItems;
    }

    /** Adds a block entry. Throws {@link IllegalArgumentException} with a readable message if it is not valid. */
    public boolean addInteractBlock(String entry) {
        String text = entry.trim();
        parseBlock(text);
        return addUnique(interactBlocks, text, true);
    }

    public boolean removeInteractBlock(String entry) {
        return removeEntry(interactBlocks, entry.trim(), true);
    }

    public boolean addInteractItem(String entry) {
        String text = entry.trim();
        parseItem(text);
        return addUnique(interactItems, text, false);
    }

    public boolean removeInteractItem(String entry) {
        return removeEntry(interactItems, entry.trim(), false);
    }

    private boolean addUnique(List<String> list, String entry, boolean blocks) {
        if (list.contains(entry)) {
            return false;
        }
        list.add(entry);
        invalidate(blocks);
        return true;
    }

    private boolean removeEntry(List<String> list, String entry, boolean blocks) {
        boolean removed = list.remove(entry);
        if (removed) {
            invalidate(blocks);
        }
        return removed;
    }

    private void invalidate(boolean blocks) {
        if (blocks) {
            blockMatchers = null;
        } else {
            itemMatchers = null;
        }
        version++;
    }

    /** Whether everyone may use this block (open it, press it...) even without being trusted. */
    public boolean canInteract(BlockState state) {
        if (blockMatchers == null) {
            blockMatchers = compile(interactBlocks, FieldSettings::parseBlock);
        }
        for (Predicate<BlockState> matcher : blockMatchers) {
            if (matcher.test(state)) {
                return true;
            }
        }
        return false;
    }

    /** Whether everyone may use this item on protected blocks. */
    public boolean canUseItem(ItemStack stack) {
        if (itemMatchers == null) {
            itemMatchers = compile(interactItems, FieldSettings::parseItem);
        }
        for (Predicate<ItemStack> matcher : itemMatchers) {
            if (matcher.test(stack)) {
                return true;
            }
        }
        return false;
    }

    private static <T> List<Predicate<T>> compile(List<String> entries, java.util.function.Function<String, Predicate<T>> parser) {
        List<Predicate<T>> result = new ArrayList<>(entries.size());
        for (String entry : entries) {
            try {
                result.add(parser.apply(entry));
            } catch (IllegalArgumentException e) {
                // An entry that no longer resolves (a removed mod, say) simply matches nothing.
            }
        }
        return result;
    }

    // --- Entry parsing ----------------------------------------------------------------------------------

    private static Predicate<BlockState> parseBlock(String text) {
        if (text.equals("*")) {
            return state -> true;
        }
        String properties = null;
        int bracket = text.indexOf('[');
        if (bracket >= 0) {
            if (!text.endsWith("]")) {
                throw new IllegalArgumentException("Missing ']' in " + text);
            }
            properties = text.substring(bracket + 1, text.length() - 1);
            text = text.substring(0, bracket);
        }

        Predicate<BlockState> base;
        Block concrete = null;
        if (text.startsWith("#")) {
            TagKey<Block> tag = TagKey.create(Registries.BLOCK, parseId(text.substring(1)));
            if (BuiltInRegistries.BLOCK.getTag(tag).isEmpty()) {
                throw new IllegalArgumentException("Unknown block tag " + text);
            }
            base = state -> state.is(tag);
        } else {
            ResourceLocation id = parseId(text);
            concrete = BuiltInRegistries.BLOCK.getOptional(id)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown block " + id));
            Block block = concrete;
            base = state -> state.is(block);
        }

        if (properties == null || properties.isBlank()) {
            return base;
        }
        List<String[]> wanted = new ArrayList<>();
        for (String pair : properties.split(",")) {
            String[] kv = pair.split("=", 2);
            if (kv.length != 2 || kv[0].isBlank() || kv[1].isBlank()) {
                throw new IllegalArgumentException("Expected name=value in " + pair);
            }
            String name = kv[0].trim();
            String value = kv[1].trim();
            if (concrete != null) {
                Property<?> property = concrete.getStateDefinition().getProperty(name);
                if (property == null) {
                    throw new IllegalArgumentException(concrete.getName().getString() + " has no property " + name);
                }
                if (property.getValue(value).isEmpty()) {
                    throw new IllegalArgumentException(name + " cannot be " + value);
                }
            }
            wanted.add(new String[] { name, value });
        }
        return state -> {
            if (!base.test(state)) {
                return false;
            }
            for (String[] pair : wanted) {
                Property<?> property = state.getBlock().getStateDefinition().getProperty(pair[0]);
                if (property == null || !hasValue(state, property, pair[1])) {
                    return false;
                }
            }
            return true;
        };
    }

    private static <T extends Comparable<T>> boolean hasValue(BlockState state, Property<T> property, String value) {
        return property.getValue(value).map(parsed -> state.getValue(property).equals(parsed)).orElse(false);
    }

    private static Predicate<ItemStack> parseItem(String text) {
        if (text.equals("*")) {
            return stack -> true;
        }
        if (text.startsWith("#")) {
            TagKey<Item> tag = TagKey.create(Registries.ITEM, parseId(text.substring(1)));
            if (BuiltInRegistries.ITEM.getTag(tag).isEmpty()) {
                throw new IllegalArgumentException("Unknown item tag " + text);
            }
            return stack -> stack.is(tag);
        }
        ResourceLocation id = parseId(text);
        Item item = BuiltInRegistries.ITEM.getOptional(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown item " + id));
        return stack -> stack.is(item);
    }

    private static ResourceLocation parseId(String text) {
        ResourceLocation id = ResourceLocation.tryParse(text.trim());
        if (id == null) {
            throw new IllegalArgumentException("Not a valid id: " + text);
        }
        return id;
    }

    // --- Persistence ------------------------------------------------------------------------------------

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Place", place.name());
        tag.putString("Break", breaking.name());
        tag.putString("Entry", entry.name());
        tag.putBoolean("Enabled", enabled);
        tag.putBoolean("CoreInstalled", coreInstalled);
        tag.putInt("ResistanceUpgrades", resistanceUpgrades);
        tag.putInt("TotalUpgrades", totalUpgrades);
        tag.putBoolean("Aegis", aegis);
        tag.putBoolean("Veil", veil);

        tag.put("Admins", players(admins));
        tag.put("Trusted", players(trusted));
        tag.put("InteractBlocks", strings(interactBlocks));
        tag.put("InteractItems", strings(interactItems));
        ListTag gateList = new ListTag();
        for (Gate gate : gates) {
            gateList.add(gate.save());
        }
        tag.put("Gates", gateList);
        tag.putInt("NextGateId", nextGateId);
        return tag;
    }

    public static FieldSettings load(CompoundTag tag) {
        FieldSettings settings = new FieldSettings();
        settings.place = readAccess(tag, "Place");
        settings.breaking = readAccess(tag, "Break");
        settings.entry = readAccess(tag, "Entry");
        settings.enabled = !tag.contains("Enabled") || tag.getBoolean("Enabled");
        settings.coreInstalled = tag.getBoolean("CoreInstalled");
        settings.resistanceUpgrades = tag.getInt("ResistanceUpgrades");
        settings.totalUpgrades = tag.getInt("TotalUpgrades");
        settings.aegis = tag.getBoolean("Aegis");
        settings.veil = tag.getBoolean("Veil");
        readPlayers(tag, "Admins", settings.admins);
        readPlayers(tag, "Trusted", settings.trusted);
        readStrings(tag, "InteractBlocks", settings.interactBlocks);
        readStrings(tag, "InteractItems", settings.interactItems);
        int highest = 0;
        for (Tag entry : tag.getList("Gates", Tag.TAG_COMPOUND)) {
            Gate gate = Gate.load((CompoundTag) entry);
            settings.gates.add(gate);
            highest = Math.max(highest, gate.id());
        }
        settings.nextGateId = Math.max(Math.max(1, tag.getInt("NextGateId")), highest + 1);
        // A gate saved before gates had ids gets one now.
        for (int i = 0; i < settings.gates.size(); i++) {
            if (settings.gates.get(i).id() <= 0) {
                settings.gates.set(i, settings.gates.get(i).withId(settings.nextGateId++));
            }
        }
        return settings;
    }

    private static Access readAccess(CompoundTag tag, String key) {
        try {
            return Access.valueOf(tag.getString(key));
        } catch (IllegalArgumentException e) {
            return Access.OWNER;
        }
    }

    private static ListTag players(Map<UUID, String> players) {
        ListTag list = new ListTag();
        for (Map.Entry<UUID, String> player : players.entrySet()) {
            CompoundTag entryTag = new CompoundTag();
            entryTag.putUUID("Id", player.getKey());
            entryTag.putString("Name", player.getValue());
            list.add(entryTag);
        }
        return list;
    }

    private static void readPlayers(CompoundTag tag, String key, Map<UUID, String> into) {
        for (Tag entry : tag.getList(key, Tag.TAG_COMPOUND)) {
            CompoundTag entryTag = (CompoundTag) entry;
            into.put(entryTag.getUUID("Id"), entryTag.getString("Name"));
        }
    }

    private static ListTag strings(List<String> values) {
        ListTag list = new ListTag();
        for (String value : values) {
            list.add(StringTag.valueOf(value));
        }
        return list;
    }

    private static void readStrings(CompoundTag tag, String key, List<String> into) {
        for (Tag entry : tag.getList(key, Tag.TAG_STRING)) {
            into.add(entry.getAsString());
        }
    }
}
