package at.ioexception.mcaddon.script.surface;

import at.ioexception.mcaddon.registry.AddonItems;
import at.ioexception.mcaddon.script.ScriptCoverage;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.graalvm.polyglot.HostAccess;

import java.util.ArrayList;
import java.util.List;

/**
 * `Container` and `ContainerSlot`, over whatever holds the items: a player's inventory, an entity
 * that is itself a container, or a block entity that is one.
 *
 * A container reaches the guest as a handle string it hands back on every call, because a Java
 * container object would not survive the tick a script holds it across. The handle names where to
 * look the container up again rather than the container itself, so a chest broken between two calls
 * answers as invalid instead of writing into a copy nobody can see.
 *
 * An item stack crosses as the same JSON the equipment surface uses, widened with the maximum stack
 * size, the lore lines and the dynamic properties a pack stored on the stack.
 */
public final class Containers extends Surface {
    /** Bedrock counts a player's container as the hotbar and the main inventory, without armour. */
    private static final int PLAYER_SLOTS = 36;

    private static final String ENTITY_PREFIX = "e|";
    private static final String BLOCK_PREFIX = "b|";

    /** Where a pack's own per-item values live on the stack, as one JSON object of primitives. */
    private static final String PROPERTIES_KEY = "mcaddon_properties";

    public Containers(MinecraftServer server, ScriptCoverage coverage) {
        super(server, coverage);
    }

    /** A handle for the entity's own container, or null when it has none. */
    @HostAccess.Export
    public String ofEntity(String entityId) {
        return held(ENTITY_PREFIX + entityId) == null ? null : ENTITY_PREFIX + entityId;
    }

    /** A handle for the container a block holds, which is how the block surface reaches one. */
    @HostAccess.Export
    public String ofBlock(String dimension, int x, int y, int z) {
        String handle = BLOCK_PREFIX + dimension + "|" + x + "|" + y + "|" + z;
        return held(handle) == null ? null : handle;
    }

    @HostAccess.Export
    public boolean isValid(String handle) {
        return held(handle) != null;
    }

    @HostAccess.Export
    public int size(String handle) {
        Held held = held(handle);
        return held == null ? 0 : held.size();
    }

    @HostAccess.Export
    public int emptySlots(String handle) {
        Held held = held(handle);
        if (held == null) return 0;

        int empty = 0;
        for (int slot = 0; slot < held.size(); slot++) {
            if (held.container().getItem(slot).isEmpty()) empty++;
        }
        return empty;
    }

    /** The stack in a slot as JSON, or null for an empty slot and for a slot out of bounds. */
    @HostAccess.Export
    public String item(String handle, int slot) {
        Held held = held(handle);
        return held == null || !held.holds(slot) ? null : json(held.container().getItem(slot));
    }

    @HostAccess.Export
    public void setItem(String handle, int slot, String itemJson) {
        Held held = held(handle);
        if (held == null || !held.holds(slot)) return;
        held.container().setItem(slot, stack(itemJson, ItemStack.EMPTY));
        held.changed();
    }

    /**
     * Writes the values back onto the stack the slot already holds.
     *
     * A script changing one member of a slot sends the whole stack back, and the JSON carries only
     * what Bedrock has a name for. Keeping the stack that is there leaves an enchantment, a trim or a
     * shulker box's contents on an item whose count or lore a pack edited.
     */
    @HostAccess.Export
    public void editItem(String handle, int slot, String itemJson) {
        Held held = held(handle);
        if (held == null || !held.holds(slot)) return;
        held.container().setItem(slot, stack(itemJson, held.container().getItem(slot)));
        held.changed();
    }

    /** Whatever would not fit, which is null when every item went in. */
    @HostAccess.Export
    public String addItem(String handle, String itemJson) {
        Held held = held(handle);
        if (held == null) return null;

        String left = json(insert(held, stack(itemJson, ItemStack.EMPTY)));
        held.changed();
        return left;
    }

    @HostAccess.Export
    public void clearAll(String handle) {
        Held held = held(handle);
        if (held == null) return;
        for (int slot = 0; slot < held.size(); slot++) held.container().setItem(slot, ItemStack.EMPTY);
        held.changed();
    }

    /**
     * Moves one slot's stack into another, in this container or in a second one.
     *
     * A destination holding something else is swapped rather than written over, so no item is
     * destroyed by a move; a destination holding the same kind takes what fits and the rest stays
     * where it was. An empty source slot moves nothing, so the destination keeps what it holds.
     */
    @HostAccess.Export
    public boolean moveItem(String handle, int fromSlot, int toSlot, String toHandle) {
        Held from = held(handle);
        Held to = held(toHandle);
        if (from == null || to == null || !from.holds(fromSlot) || !to.holds(toSlot)) return false;

        ItemStack moving = from.container().getItem(fromSlot);
        if (moving.isEmpty()) return false;

        ItemStack target = to.container().getItem(toSlot);
        if (!target.isEmpty() && !sameKind(moving, target)) return swap(from, fromSlot, to, toSlot);

        int room = Math.min(moving.getMaxStackSize(), to.container().getMaxStackSize()) - target.getCount();
        int moved = Math.min(Math.max(room, 0), moving.getCount());
        ItemStack placed = moving.split(moved);
        placed.setCount(placed.getCount() + target.getCount());

        from.container().setItem(fromSlot, moving);
        to.container().setItem(toSlot, placed);
        from.changed();
        to.changed();
        return true;
    }

    @HostAccess.Export
    public boolean swapItems(String handle, int slot, int otherSlot, String otherHandle) {
        Held held = held(handle);
        Held other = held(otherHandle);
        return held != null && other != null && held.holds(slot) && other.holds(otherSlot)
            && swap(held, slot, other, otherSlot);
    }

    /** Moves a slot's stack into the first slots of another container that will take it. */
    @HostAccess.Export
    public String transferItem(String handle, int fromSlot, String toHandle) {
        Held from = held(handle);
        Held to = held(toHandle);
        if (from == null || to == null || !from.holds(fromSlot)) return null;

        ItemStack left = insert(to, from.container().getItem(fromSlot));
        from.container().setItem(fromSlot, left);
        from.changed();
        to.changed();
        return json(left);
    }

    // Where the items are

    /** A container, the number of slots a script sees, and the player whose screen has to be told. */
    private record Held(Container container, int size, ServerPlayer viewer) {
        boolean holds(int slot) {
            return slot >= 0 && slot < size;
        }

        void changed() {
            container.setChanged();
            if (viewer != null) viewer.containerMenu.broadcastChanges();
        }
    }

    private Held held(String handle) {
        if (handle == null) return null;
        if (handle.startsWith(ENTITY_PREFIX)) return ofEntityHandle(handle.substring(ENTITY_PREFIX.length()));
        if (handle.startsWith(BLOCK_PREFIX)) return ofBlockHandle(handle.substring(BLOCK_PREFIX.length()));
        return null;
    }

    private Held ofEntityHandle(String entityId) {
        Entity found = entity(entityId);
        if (found == null || !found.isAlive()) return null;
        if (found instanceof ServerPlayer player) {
            return new Held(player.getInventory(), PLAYER_SLOTS, player);
        }
        return found instanceof Container container
            ? new Held(container, container.getContainerSize(), null) : null;
    }

    private Held ofBlockHandle(String place) {
        String[] parts = place.split("\\|");
        if (parts.length != 4) return null;

        ServerLevel level = level(parts[0]);
        if (level == null) return null;

        BlockPos position;
        try {
            position = new BlockPos(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]),
                Integer.parseInt(parts[3]));
        } catch (NumberFormatException ignored) {
            return null;
        }

        if (!level.hasChunkAt(position)) return null;
        return level.getBlockEntity(position) instanceof Container container
            ? new Held(container, container.getContainerSize(), null) : null;
    }

    // Moving stacks about

    /** Puts a stack in, stacking onto its own kind first, and hands back what would not fit. */
    private static ItemStack insert(Held into, ItemStack incoming) {
        ItemStack left = incoming.copy();
        int ceiling = into.container().getMaxStackSize();

        for (int slot = 0; slot < into.size() && !left.isEmpty(); slot++) {
            ItemStack present = into.container().getItem(slot);
            if (present.isEmpty() || !sameKind(present, left)) continue;

            int room = Math.min(present.getMaxStackSize(), ceiling) - present.getCount();
            if (room <= 0) continue;

            int moved = Math.min(room, left.getCount());
            present.grow(moved);
            left.shrink(moved);
            into.container().setItem(slot, present);
        }

        for (int slot = 0; slot < into.size() && !left.isEmpty(); slot++) {
            if (!into.container().getItem(slot).isEmpty()) continue;
            into.container().setItem(slot, left.split(Math.min(left.getMaxStackSize(), ceiling)));
        }
        return left;
    }

    private static boolean swap(Held held, int slot, Held other, int otherSlot) {
        ItemStack mine = held.container().getItem(slot);
        held.container().setItem(slot, other.container().getItem(otherSlot));
        other.container().setItem(otherSlot, mine);
        held.changed();
        other.changed();
        return true;
    }

    // The stack, as the guest sees it

    private static String json(ItemStack stack) {
        if (stack.isEmpty()) return null;

        JsonObject item = new JsonObject();
        item.addProperty("typeId", AddonItems.identifierOf(stack.getItem()));
        item.addProperty("amount", stack.getCount());
        item.addProperty("damage", stack.getDamageValue());
        item.addProperty("maxDurability", stack.getMaxDamage());
        item.addProperty("maxAmount", stack.getMaxStackSize());
        item.addProperty("nameTag", customName(stack));

        JsonArray lore = new JsonArray();
        lore(stack).forEach(lore::add);
        item.add("lore", lore);
        item.add("properties", properties(stack));

        JsonObject book = book(stack);
        if (book != null) item.add("book", book);
        return item.toString();
    }

    /** The stack the JSON describes, built on `present` when that already holds the same item. */
    private static ItemStack stack(String itemJson, ItemStack present) {
        if (itemJson == null || itemJson.isBlank()) return ItemStack.EMPTY;

        JsonObject item = JsonParser.parseString(itemJson).getAsJsonObject();
        Item type = item.has("typeId") ? AddonItems.named(item.get("typeId").getAsString()) : null;
        if (type == null) return ItemStack.EMPTY;

        int amount = Math.max(1, item.has("amount") ? item.get("amount").getAsInt() : 1);
        ItemStack built = present.is(type) ? present.copy() : new ItemStack(type, amount);
        built.setCount(amount);
        if (item.has("damage")) built.setDamageValue(item.get("damage").getAsInt());

        String name = item.has("nameTag") && item.get("nameTag").isJsonPrimitive()
            ? item.get("nameTag").getAsString() : "";
        setCustomName(built, name);

        List<String> lore = new ArrayList<>();
        if (item.has("lore") && item.get("lore").isJsonArray()) {
            item.getAsJsonArray("lore").forEach(line -> lore.add(line.getAsString()));
        }
        setLore(built, lore);

        setProperties(built, item.has("properties") && item.get("properties").isJsonObject()
            ? item.getAsJsonObject("properties") : new JsonObject());

        if (item.has("book") && item.get("book").isJsonObject()) {
            setBook(built, item.getAsJsonObject("book"));
        }
        return built;
    }

    private static JsonObject properties(ItemStack stack) {
        String stored = storedProperties(stack);
        if (stored.isBlank()) return new JsonObject();

        JsonElement parsed = JsonParser.parseString(stored);
        return parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
    }

    private static void setProperties(ItemStack stack, JsonObject values) {
        storeProperties(stack, values.size() == 0 ? "" : values.toString());
    }

    // Everything below moved between the supported versions and belongs in Platform once the rest of
    // the surface needs it too.

    /** Whether two stacks are the same item carrying the same extra data, ignoring how many. */
    private static boolean sameKind(ItemStack one, ItemStack other) {
        //? if >=1.21 {
        return ItemStack.isSameItemSameComponents(one, other);
        //?} else {
        /*return ItemStack.isSameItemSameTags(one, other);
        *///?}
    }

    /** The name a pack gave the stack, or an empty string when it carries only its own name. */
    private static String customName(ItemStack stack) {
        //? if >=1.21 {
        Component name = stack.get(net.minecraft.core.component.DataComponents.CUSTOM_NAME);
        return name == null ? "" : name.getString();
        //?} else {
        /*return stack.hasCustomHoverName() ? stack.getHoverName().getString() : "";
        *///?}
    }

    private static void setCustomName(ItemStack stack, String name) {
        //? if >=1.21 {
        if (name.isEmpty()) {
            stack.remove(net.minecraft.core.component.DataComponents.CUSTOM_NAME);
        } else {
            stack.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, Component.literal(name));
        }
        //?} else {
        /*if (name.isEmpty()) {
            stack.resetHoverName();
        } else {
            stack.setHoverName(Component.literal(name));
        }
        *///?}
    }

    private static List<String> lore(ItemStack stack) {
        List<String> lines = new ArrayList<>();
        //? if >=1.21 {
        stack.getOrDefault(net.minecraft.core.component.DataComponents.LORE,
                net.minecraft.world.item.component.ItemLore.EMPTY)
            .lines()
            .forEach(line -> lines.add(line.getString()));
        //?} else {
        /*net.minecraft.nbt.CompoundTag display = stack.getTagElement("display");
        net.minecraft.nbt.ListTag stored = display == null ? null : display.getList("Lore", 8);
        for (int line = 0; stored != null && line < stored.size(); line++) {
            Component text = Component.Serializer.fromJson(stored.getString(line));
            lines.add(text == null ? "" : text.getString());
        }
        *///?}
        return lines;
    }

    private static void setLore(ItemStack stack, List<String> lines) {
        //? if >=1.21 {
        stack.set(net.minecraft.core.component.DataComponents.LORE,
            new net.minecraft.world.item.component.ItemLore(
                lines.stream().map(line -> (Component) Component.literal(line)).toList()));
        //?} else {
        /*if (lines.isEmpty()) {
            net.minecraft.nbt.CompoundTag display = stack.getTagElement("display");
            if (display != null) display.remove("Lore");
            return;
        }
        net.minecraft.nbt.ListTag stored = new net.minecraft.nbt.ListTag();
        lines.forEach(line -> stored.add(net.minecraft.nbt.StringTag.valueOf(
            Component.Serializer.toJson(Component.literal(line)))));
        stack.getOrCreateTagElement("display").put("Lore", stored);
        *///?}
    }

    /**
     * The book a stack is, or null for an item that is no kind of book.
     *
     * A signed book keeps its pages as text components and an unsigned one keeps them as plain
     * text, so both come back as plain text and `signed` says which the stack was.
     */
    private static JsonObject book(ItemStack stack) {
        JsonObject book = new JsonObject();
        JsonArray pages = new JsonArray();
        book.add("pages", pages);
        //? if >=1.21 {
        net.minecraft.world.item.component.WrittenBookContent written =
            stack.get(net.minecraft.core.component.DataComponents.WRITTEN_BOOK_CONTENT);
        if (written != null) {
            book.addProperty("signed", true);
            book.addProperty("title", written.title().raw());
            book.addProperty("author", written.author());
            written.pages().forEach(page -> pages.add(page.raw().getString()));
            return book;
        }

        net.minecraft.world.item.component.WritableBookContent writable =
            stack.get(net.minecraft.core.component.DataComponents.WRITABLE_BOOK_CONTENT);
        if (writable == null) return null;

        book.addProperty("signed", false);
        writable.pages().forEach(page -> pages.add(page.raw()));
        return book;
        //?} else {
        /*net.minecraft.nbt.CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains("pages", 9)) return null;

        boolean signed = tag.contains("title", 8);
        book.addProperty("signed", signed);
        if (signed) {
            book.addProperty("title", tag.getString("title"));
            book.addProperty("author", tag.getString("author"));
        }

        net.minecraft.nbt.ListTag stored = tag.getList("pages", 8);
        for (int page = 0; page < stored.size(); page++) {
            Component text = signed ? Component.Serializer.fromJson(stored.getString(page)) : null;
            pages.add(signed ? text == null ? "" : text.getString() : stored.getString(page));
        }
        return book;
        *///?}
    }

    /** Writes a stack's pages, and its title and author once the pack has signed it. */
    private static void setBook(ItemStack stack, JsonObject book) {
        List<String> pages = new ArrayList<>();
        if (book.has("pages") && book.get("pages").isJsonArray()) {
            book.getAsJsonArray("pages").forEach(page -> pages.add(page.getAsString()));
        }
        boolean signed = book.has("signed") && book.get("signed").getAsBoolean();
        String title = book.has("title") && book.get("title").isJsonPrimitive()
            ? book.get("title").getAsString() : "";
        String author = book.has("author") && book.get("author").isJsonPrimitive()
            ? book.get("author").getAsString() : "";

        //? if >=1.21 {
        if (!signed) {
            stack.set(net.minecraft.core.component.DataComponents.WRITABLE_BOOK_CONTENT,
                new net.minecraft.world.item.component.WritableBookContent(
                    pages.stream().map(net.minecraft.server.network.Filterable::passThrough).toList()));
            return;
        }

        stack.remove(net.minecraft.core.component.DataComponents.WRITABLE_BOOK_CONTENT);
        stack.set(net.minecraft.core.component.DataComponents.WRITTEN_BOOK_CONTENT,
            new net.minecraft.world.item.component.WrittenBookContent(
                net.minecraft.server.network.Filterable.passThrough(title), author, 0,
                pages.stream()
                    .map(page -> net.minecraft.server.network.Filterable.passThrough(
                        (Component) Component.literal(page)))
                    .toList(),
                false));
        //?} else {
        /*net.minecraft.nbt.ListTag stored = new net.minecraft.nbt.ListTag();
        pages.forEach(page -> stored.add(net.minecraft.nbt.StringTag.valueOf(
            signed ? Component.Serializer.toJson(Component.literal(page)) : page)));

        net.minecraft.nbt.CompoundTag tag = stack.getOrCreateTag();
        tag.put("pages", stored);
        if (signed) {
            tag.putString("title", title);
            tag.putString("author", author);
            tag.putInt("generation", 0);
        }
        *///?}
    }

    private static String storedProperties(ItemStack stack) {
        //? if >=26 {
        return stack.getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
            net.minecraft.world.item.component.CustomData.EMPTY).copyTag().getStringOr(PROPERTIES_KEY, "");
        //?} else if >=1.21 {
        /*return stack.getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
            net.minecraft.world.item.component.CustomData.EMPTY).copyTag().getString(PROPERTIES_KEY);
        *///?} else {
        /*net.minecraft.nbt.CompoundTag tag = stack.getTag();
        return tag == null ? "" : tag.getString(PROPERTIES_KEY);
        *///?}
    }

    private static void storeProperties(ItemStack stack, String values) {
        //? if >=1.21 {
        net.minecraft.world.item.component.CustomData.update(
            net.minecraft.core.component.DataComponents.CUSTOM_DATA, stack, tag -> {
                if (values.isEmpty()) {
                    tag.remove(PROPERTIES_KEY);
                } else {
                    tag.putString(PROPERTIES_KEY, values);
                }
            });
        //?} else {
        /*if (values.isEmpty()) {
            if (stack.getTag() != null) stack.getTag().remove(PROPERTIES_KEY);
            return;
        }
        stack.getOrCreateTag().putString(PROPERTIES_KEY, values);
        *///?}
    }
}
