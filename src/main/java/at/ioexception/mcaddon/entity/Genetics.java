package at.ioexception.mcaddon.entity;

import at.ioexception.mcaddon.bedrock.behaviour.ServerEntityDefinition;
import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.util.RandomSource;

import java.util.ArrayList;
import java.util.List;

/**
 * What `minecraft:genetics` decides about an entity at the moment it is born.
 *
 * A gene holds two alleles, one taken from each parent, and the pair they land on picks the variant
 * the entity is. The variant names the event that announces it, which is how a pack makes a rare
 * animal out of an ordinary herd without a second entity type. An entity whose parents carry no
 * allele of their own rolls both ends, which is what the first of a kind gets.
 */
public final class Genetics {
    /** One outcome of a gene, and the event fired for an entity born into it. */
    private record Variant(int low, int high, boolean both, String birthEvent) {
        boolean holds(int main, int hidden) {
            return main >= low && main <= high && (!both || (hidden >= low && hidden <= high));
        }
    }

    private record Gene(String name, int low, int high, double mutationRate, List<Variant> variants) {
    }

    private final List<Gene> genes;

    private Genetics(List<Gene> genes) {
        this.genes = genes;
    }

    /** Null for an entity whose pack described no genes. */
    public static Genetics tryFrom(ServerEntityDefinition definition) {
        JsonObject component = definition.components().get("minecraft:genetics");
        if (component == null) return null;

        double mutation = Goals.number(component, "mutation_rate", 0.03);
        List<Gene> genes = new ArrayList<>();
        for (JsonElement element : Json.list(component, "genes")) {
            if (!element.isJsonObject()) continue;

            JsonObject gene = element.getAsJsonObject();
            String name = Json.text(gene, "name", null);
            if (name == null) continue;

            genes.add(new Gene(name,
                end(gene, "allele_range", "range_min", 0),
                end(gene, "allele_range", "range_max", 1),
                Goals.number(gene, "mutation_rate", mutation),
                variants(gene)));
        }
        return genes.isEmpty() ? null : new Genetics(genes);
    }

    /**
     * The outcomes a gene lists, in the order the pack wrote them.
     *
     * Bedrock lets a variant ask about the visible allele alone or about both at once, and writes
     * either as a number or as a range, so both spellings of both questions end up as one span.
     */
    private static List<Variant> variants(JsonObject gene) {
        List<Variant> variants = new ArrayList<>();
        for (JsonElement element : Json.list(gene, "genetic_variants")) {
            if (!element.isJsonObject()) continue;

            JsonObject variant = element.getAsJsonObject();
            boolean both = variant.has("both_allele");
            String key = both ? "both_allele" : "main_allele";
            if (!variant.has(key)) continue;

            variants.add(new Variant(end(variant, key, "range_min", 0), end(variant, key, "range_max", 0),
                both, Goals.event(variant, "birth_event")));
        }
        return variants;
    }

    /**
     * One end of a span, which a pack writes as a bare allele or as a pair of bounds.
     *
     * Bedrock spells the two bounds `range_min` and `range_max` here, where the rest of its
     * components use `min` and `max`, so the genes read their own ranges rather than the shared ones.
     */
    private static int end(JsonObject body, String key, String bound, int fallback) {
        JsonElement value = body.get(key);
        if (value == null) return fallback;
        if (value.isJsonPrimitive()) return value.getAsInt();
        if (!value.isJsonObject()) return fallback;

        JsonObject range = value.getAsJsonObject();
        return range.has(bound) ? range.get(bound).getAsInt() : fallback;
    }

    /**
     * Rolls the child's genes off the pair that bred it, and answers the events the outcome names.
     *
     * Each allele is the one the parent carries, or a fresh roll when that parent carries none, and
     * a mutation replaces it with another from the same range. Both alleles are left on the child as
     * the numbers a pack's own render controllers and filters read back.
     */
    public List<String> rollInto(AddonEntity child, AddonEntity first, AddonEntity second,
        RandomSource random) {
        List<String> events = new ArrayList<>();
        for (Gene gene : genes) {
            int main = allele(gene, first, random);
            int hidden = allele(gene, second, random);
            child.setValue(gene.name(), (double) main);
            child.setValue(gene.name() + "_hidden", (double) hidden);

            gene.variants().stream()
                .filter(variant -> variant.holds(main, hidden))
                .map(Variant::birthEvent)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .ifPresent(events::add);
        }
        return events;
    }

    /** What one parent passes on, which a mutation replaces with another allele from the range. */
    private static int allele(Gene gene, AddonEntity parent, RandomSource random) {
        if (random.nextDouble() < gene.mutationRate()) return roll(gene, random);

        Object carried = parent == null ? null : parent.value(gene.name());
        return carried instanceof Number number ? number.intValue() : roll(gene, random);
    }

    private static int roll(Gene gene, RandomSource random) {
        int span = gene.high() - gene.low();
        return span <= 0 ? gene.low() : gene.low() + random.nextInt(span + 1);
    }
}
