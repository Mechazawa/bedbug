package at.ioexception.mcaddon.item;

import at.ioexception.mcaddon.bedrock.behaviour.Descriptor;
import at.ioexception.mcaddon.bedrock.behaviour.ItemDefinition;
import at.ioexception.mcaddon.bedrock.molang.MolangEnvironment;
import at.ioexception.mcaddon.bedrock.molang.MolangExpression;
import at.ioexception.mcaddon.bedrock.molang.MolangParser;
import at.ioexception.mcaddon.bedrock.molang.MolangValue;
import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.OptionalInt;

/**
 * What `minecraft:repairable` accepts and how much each entry gives back, which Bedrock states as
 * Molang. An entry's items are descriptors, so a name or a tag test.
 *
 * Mojang's vanilla items write `query.max_durability * 0.25` for a material and
 * `context.other->query.remaining_durability` for a second copy of the item. The expression is
 * answered for the item being repaired, and `context.other` refers to the item repairing it.
 */
public final class Repairable {
    /** An entry's amount is null where it states none, which leaves Java's own rule. */
    private record Entry(List<Descriptor> items, MolangExpression amount) {
        boolean names(ItemStack other) {
            return items.stream().anyMatch(descriptor -> descriptor.matches(other));
        }
    }

    /** One item stack as Molang reads it: its two durability queries and nothing more. */
    private record Stack(ItemStack stack) implements MolangValue.Reference {
        @Override
        public MolangEnvironment scope() {
            MolangEnvironment scope = new MolangEnvironment();
            scope.setQueries((query, arguments) -> switch (query) {
                case "max_durability" -> MolangValue.of(stack.getMaxDamage());
                case "remaining_durability" -> MolangValue.of(stack.getMaxDamage() - stack.getDamageValue());
                default -> MolangValue.ZERO;
            });
            return scope;
        }
    }

    private final List<Entry> entries;

    private Repairable(List<Entry> entries) {
        this.entries = entries;
    }

    /** The entries a pack stated on this item, or null where it states no repairable entry. */
    public static Repairable tryFrom(ItemDefinition definition) {
        JsonObject repairable = definition.components().get("minecraft:repairable");
        if (repairable == null) return null;

        List<Entry> entries = Json.list(repairable, "repair_items").stream()
            .map(Repairable::entry)
            .filter(entry -> !entry.items().isEmpty())
            .toList();
        return entries.isEmpty() ? null : new Repairable(entries);
    }

    /** An entry, or a bare descriptor written in place of one, which stands for a list of one. */
    private static Entry entry(JsonElement written) {
        if (!written.isJsonObject() || !written.getAsJsonObject().has("items")) {
            Descriptor named = Descriptor.tryFrom(written);
            return new Entry(named == null ? List.of() : List.of(named), null);
        }

        JsonObject entry = written.getAsJsonObject();
        JsonElement amount = entry.get("repair_amount");
        return new Entry(Descriptor.listed(entry, "items"),
            amount != null && amount.isJsonPrimitive() ? MolangParser.parse(amount.getAsString()) : null);
    }

    /** Whether an anvil takes `other` to repair this item. */
    public boolean accepts(ItemStack other) {
        return entries.stream().anyMatch(entry -> entry.names(other));
    }

    /**
     * The durability `other` gives back to `repaired`, or none where the first entry naming it
     * states no amount or no entry names it.
     */
    public OptionalInt amount(ItemStack repaired, ItemStack other) {
        return entries.stream()
            .filter(entry -> entry.names(other))
            .findFirst()
            .filter(entry -> entry.amount() != null)
            .map(entry -> {
                MolangEnvironment scope = new Stack(repaired).scope();
                scope.setContext("other", MolangValue.of(new Stack(other)));
                return OptionalInt.of(Math.max(0, (int) entry.amount().evaluate(scope).asFloat()));
            })
            .orElse(OptionalInt.empty());
    }

    /**
     * The damage `repaired` keeps once combined with `other`, or none where no entry names `other`.
     *
     * Combining two of one item adds a share of the whole durability on top in both editions (12 in
     * a hundred at an anvil, 5 in the crafting grid), so the stated amount stands where Java puts
     * the other item's own durability and the share is kept.
     */
    public OptionalInt combined(ItemStack repaired, ItemStack other, int bonusPercent) {
        OptionalInt amount = amount(repaired, other);
        if (amount.isEmpty()) return amount;

        int max = repaired.getMaxDamage();
        int remaining = max - repaired.getDamageValue() + amount.getAsInt() + max * bonusPercent / 100;
        return OptionalInt.of(Math.max(0, max - remaining));
    }
}
