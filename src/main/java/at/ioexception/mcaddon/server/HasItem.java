package at.ioexception.mcaddon.server;

import at.ioexception.mcaddon.convert.Names;
import at.ioexception.mcaddon.mixin.SelectorOptionsInvoker;
import at.ioexception.mcaddon.platform.Platform;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.IntStream;

/**
 * One item Bedrock's `hasitem` selector argument asks an entity to carry.
 *
 * `@s[hasitem={item=apple,location=slot.weapon.mainhand,quantity=2..}]` keeps an entity carrying
 * what the braces describe, and `hasitem=[{...},{...}]` one carrying all of them. Java's selector
 * has no way to ask about an inventory, so the argument is registered as an option of Java's own
 * selector parser under Bedrock's name, and a line using it reads the same in both editions.
 *
 * `quantity` counts every matching item across the slots asked about, and `slot` narrows those
 * slots by their index within the `location`. With no location, everything the entity carries is
 * asked about.
 */
public record HasItem(Item item, Range quantity, String location, Range slot) {
    private static final Range AT_LEAST_ONE = new Range(1, Integer.MAX_VALUE, false);
    private static final Range EVERY_SLOT = new Range(0, Integer.MAX_VALUE, false);

    private static final DynamicCommandExceptionType UNREADABLE = new DynamicCommandExceptionType(
        what -> Component.literal("hasitem cannot read " + what));

    /** Bedrock's armour slots in the order a `slot.armor` index counts them. */
    private static final List<EquipmentSlot> ARMOUR = List.of(
        EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET);

    /** Puts `hasitem` beside Java's own options; asked once, as the mod starts. */
    public static void register() {
        SelectorOptionsInvoker.mcaddon$register("hasitem", parser -> {
            List<HasItem> wanted = readAll(parser.getReader());
            parser.addPredicate(entity -> wanted.stream().allMatch(one -> one.carriedBy(entity)));
        }, parser -> true, Component.literal("The items an entity carries, as Bedrock asks for them"));
    }

    /** One object in braces, or a list of them in brackets. */
    static List<HasItem> readAll(StringReader reader) throws CommandSyntaxException {
        reader.skipWhitespace();
        if (!reader.canRead() || reader.peek() != '[') return List.of(read(reader));

        reader.skip();
        List<HasItem> all = new ArrayList<>();
        while (true) {
            reader.skipWhitespace();
            all.add(read(reader));
            reader.skipWhitespace();
            if (reader.canRead() && reader.peek() == ',') {
                reader.skip();
                continue;
            }
            reader.expect(']');
            return all;
        }
    }

    private static HasItem read(StringReader reader) throws CommandSyntaxException {
        reader.expect('{');
        Map<String, String> fields = new LinkedHashMap<>();
        while (true) {
            reader.skipWhitespace();
            if (reader.canRead() && reader.peek() == '}') break;
            String key = until(reader, "=").toLowerCase(Locale.ROOT);
            reader.expect('=');
            fields.put(key, until(reader, ",}"));
            if (reader.canRead() && reader.peek() == ',') reader.skip();
        }
        reader.skip();
        return of(fields, reader);
    }

    private static HasItem of(Map<String, String> fields, StringReader reader) throws CommandSyntaxException {
        for (String key : fields.keySet()) {
            if (!List.of("item", "quantity", "location", "slot", "data").contains(key)) {
                throw UNREADABLE.createWithContext(reader, "the field " + key);
            }
        }
        String data = fields.getOrDefault("data", "-1");
        if (!data.equals("-1") && !data.equals("0")) {
            throw UNREADABLE.createWithContext(reader, "a data value, which Java items have none of");
        }

        String named = fields.get("item");
        Names.Identifier id = named == null ? null : Names.split(named, "minecraft");
        Item item = id == null ? null
            : BuiltInRegistries.ITEM.getOptional(Platform.id(id.namespace(), id.path())).orElse(null);
        if (item == null) throw UNREADABLE.createWithContext(reader, "the item " + named);

        String location = fields.get("location");
        if (location != null && !slotsIn(location.toLowerCase(Locale.ROOT))) {
            throw UNREADABLE.createWithContext(reader, "the location " + location);
        }
        try {
            return new HasItem(item, Range.parse(fields.get("quantity"), AT_LEAST_ONE),
                location == null ? null : location.toLowerCase(Locale.ROOT),
                Range.parse(fields.get("slot"), EVERY_SLOT));
        } catch (NumberFormatException unreadable) {
            throw UNREADABLE.createWithContext(reader, "a range " + unreadable.getMessage());
        }
    }

    /** What the reader holds up to the first of these characters, which it leaves unread. */
    private static String until(StringReader reader, String stops) {
        int start = reader.getCursor();
        while (reader.canRead() && stops.indexOf(reader.peek()) < 0) reader.skip();
        return reader.getString().substring(start, reader.getCursor()).strip();
    }

    public boolean carriedBy(Entity entity) {
        List<ItemStack> stacks = stacks(entity);
        int count = IntStream.range(0, stacks.size())
            .filter(slot::contains)
            .mapToObj(stacks::get)
            .filter(stack -> !stack.isEmpty() && stack.is(item))
            .mapToInt(ItemStack::getCount)
            .sum();
        return quantity.contains(count);
    }

    private static boolean slotsIn(String location) {
        return switch (location) {
            case "slot.weapon.mainhand", "slot.weapon.offhand", "slot.armor", "slot.armor.head",
                "slot.armor.chest", "slot.armor.legs", "slot.armor.feet", "slot.hotbar",
                "slot.inventory", "slot.enderchest", "slot.container" -> true;
            default -> false;
        };
    }

    /** The slots the location names, in the order a `slot` index counts them. */
    private List<ItemStack> stacks(Entity entity) {
        LivingEntity living = entity instanceof LivingEntity alive ? alive : null;
        Player player = entity instanceof Player person ? person : null;
        if (location == null) return everything(entity, living, player);

        return switch (location) {
            case "slot.weapon.mainhand" -> worn(living, List.of(EquipmentSlot.MAINHAND));
            case "slot.weapon.offhand" -> worn(living, List.of(EquipmentSlot.OFFHAND));
            case "slot.armor" -> worn(living, ARMOUR);
            case "slot.armor.head" -> worn(living, List.of(EquipmentSlot.HEAD));
            case "slot.armor.chest" -> worn(living, List.of(EquipmentSlot.CHEST));
            case "slot.armor.legs" -> worn(living, List.of(EquipmentSlot.LEGS));
            case "slot.armor.feet" -> worn(living, List.of(EquipmentSlot.FEET));
            case "slot.hotbar" -> player == null ? List.of() : held(player.getInventory(), 0, 9);
            case "slot.inventory" -> player != null ? held(player.getInventory(), 9, 36)
                : entity instanceof Container container ? held(container, 0, container.getContainerSize())
                : List.of();
            case "slot.enderchest" -> player == null ? List.of()
                : held(player.getEnderChestInventory(), 0, player.getEnderChestInventory().getContainerSize());
            default -> entity instanceof Container container
                ? held(container, 0, container.getContainerSize()) : List.of();
        };
    }

    /**
     * Every item the entity carries.
     *
     * A player's inventory holds the armour and the off hand as well on every supported version, so
     * it is read whole; asking the equipment as well would count the held item twice.
     */
    private static List<ItemStack> everything(Entity entity, LivingEntity living, Player player) {
        if (player != null) return held(player.getInventory(), 0, player.getInventory().getContainerSize());

        List<ItemStack> all = new ArrayList<>(worn(living, List.of(EquipmentSlot.values())));
        if (entity instanceof Container container) all.addAll(held(container, 0, container.getContainerSize()));
        return all;
    }

    private static List<ItemStack> worn(LivingEntity living, List<EquipmentSlot> slots) {
        return living == null ? List.of() : slots.stream().map(living::getItemBySlot).toList();
    }

    private static List<ItemStack> held(Container container, int from, int to) {
        return IntStream.range(from, Math.min(to, container.getContainerSize()))
            .mapToObj(container::getItem).toList();
    }

    /** Bedrock's integer range: `3`, `1..`, `..5`, `2..4`, each of which `!` turns inside out. */
    record Range(int min, int max, boolean negated) {
        static Range parse(String text, Range absent) {
            if (text == null) return absent;

            boolean negated = text.startsWith("!");
            String bounds = negated ? text.substring(1).strip() : text;
            int dots = bounds.indexOf("..");
            if (dots < 0) {
                int exactly = Integer.parseInt(bounds);
                return new Range(exactly, exactly, negated);
            }
            String low = bounds.substring(0, dots).strip();
            String high = bounds.substring(dots + 2).strip();
            return new Range(low.isEmpty() ? Integer.MIN_VALUE : Integer.parseInt(low),
                high.isEmpty() ? Integer.MAX_VALUE : Integer.parseInt(high), negated);
        }

        boolean contains(int value) {
            return (value >= min && value <= max) != negated;
        }
    }
}
