package at.ioexception.mcaddon.bedrock.behaviour;

import at.ioexception.mcaddon.registry.AddonEntities;
import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.List;

/**
 * How often and where inside a chunk Bedrock spreads one feature.
 *
 * A feature rule's `distribution` and a `minecraft:scatter_feature`'s are the same object, so both
 * are read here. Java says the same three things as placement modifiers on a placed feature: how
 * many attempts a chunk gets, how rare a chunk that gets any is, and which heights they land at.
 *
 * Bedrock states each as a Molang expression it evaluates per chunk, and Java states each as a
 * number decided when the pack is read. An expression is reported and the plain default is used, so
 * a rule that counts itself out of the world is not what a pack gets instead.
 */
public record Scatter(JsonObject body) {

    /** A rule that states nothing, which places its feature once per chunk at the surface. */
    public static final Scatter ONCE = new Scatter(new JsonObject());

    public static Scatter of(JsonObject distribution) {
        return distribution == null ? ONCE : new Scatter(distribution);
    }

    /** How many times the feature is tried in one chunk. */
    public int iterations() {
        JsonElement declared = body.get("iterations");
        if (declared == null) return 1;
        if (declared.isJsonPrimitive() && declared.getAsJsonPrimitive().isNumber()) {
            return Math.max(1, declared.getAsInt());
        }

        AddonEntities.unsupported("scatter iterations as an expression");
        return 1;
    }

    /**
     * One in how many chunks the feature is placed at all, as Java's rarity filter counts it.
     *
     * Bedrock writes the chance either as a percentage or as a fraction of its own, and Java writes
     * the denominator of a fraction whose numerator is one.
     */
    public int rarity() {
        JsonElement declared = body.get("scatter_chance");
        if (declared == null) return 1;

        if (declared.isJsonObject()) {
            JsonObject fraction = declared.getAsJsonObject();
            double numerator = Json.number(fraction, "numerator", 1);
            double denominator = Json.number(fraction, "denominator", 1);
            return numerator <= 0 ? 1 : Math.max(1, (int) Math.round(denominator / numerator));
        }
        if (declared.isJsonPrimitive() && declared.getAsJsonPrimitive().isNumber()) {
            double percent = declared.getAsDouble();
            return percent <= 0 || percent >= 100 ? 1 : Math.max(1, (int) Math.round(100 / percent));
        }

        AddonEntities.unsupported("scatter scatter_chance as an expression");
        return 1;
    }

    /** The two heights the feature is scattered between, or null where it states none. */
    public int[] heights() {
        JsonElement declared = body.get("y");
        if (declared == null || !declared.isJsonObject()) return null;

        JsonObject axis = declared.getAsJsonObject();
        JsonElement extent = axis.get("extent");
        if (extent == null || !extent.isJsonArray() || extent.getAsJsonArray().size() != 2) return null;

        JsonElement low = extent.getAsJsonArray().get(0);
        JsonElement high = extent.getAsJsonArray().get(1);
        if (!low.isJsonPrimitive() || !low.getAsJsonPrimitive().isNumber()
            || !high.isJsonPrimitive() || !high.getAsJsonPrimitive().isNumber()) {
            AddonEntities.unsupported("scatter height as an expression");
            return null;
        }

        // Java spreads a feature either evenly between two heights or towards the middle of them.
        // The two grids and the two gaussians decide the shape of the spread rather than its bounds,
        // so the bounds are kept and the shape is named.
        String shape = Json.text(axis, "distribution", "uniform");
        if (!shape.equals("uniform") && !shape.equals("triangle")) {
            AddonEntities.unsupported("scatter y distribution " + shape);
        }
        return new int[] {Math.min(low.getAsInt(), high.getAsInt()),
            Math.max(low.getAsInt(), high.getAsInt())};
    }

    /** Whether the heights are spread towards their middle rather than evenly. */
    public boolean trapezoid() {
        JsonElement declared = body.get("y");
        return declared != null && declared.isJsonObject()
            && "triangle".equals(Json.text(declared.getAsJsonObject(), "distribution", "uniform"));
    }

    /** Names the parts of the scatter a Java placement has nothing to state. */
    public void report() {
        AddonEntities.unsupportedFields("scatter", body, List.of("coordinate_eval_order"));

        for (String axis : List.of("x", "z")) {
            JsonElement declared = body.get(axis);
            // Java spreads a feature over the whole of the chunk it is placed in, with no way to
            // narrow that to part of one, so an x or z extent is read as the whole chunk.
            if (declared != null && declared.isJsonObject()) {
                AddonEntities.unsupported("scatter " + axis + " extent");
            }
        }
    }
}
