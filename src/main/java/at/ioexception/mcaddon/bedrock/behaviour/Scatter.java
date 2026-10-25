package at.ioexception.mcaddon.bedrock.behaviour;

import at.ioexception.mcaddon.bedrock.entity.EntityQueries;
import at.ioexception.mcaddon.bedrock.molang.MolangEnvironment;
import at.ioexception.mcaddon.bedrock.molang.MolangExpression;
import at.ioexception.mcaddon.bedrock.molang.MolangParser;
import at.ioexception.mcaddon.bedrock.molang.MolangValue;
import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * How often and where Bedrock spreads one feature around the position it is given.
 *
 * A feature rule's `distribution` and a `minecraft:scatter_feature`'s are the same object, so both
 * are read here and both place through `positions`. The chance is rolled once, then each iteration
 * works its three coordinates out in the order `coordinate_eval_order` names, each an offset from the
 * position given and each able to read the ones before it through `variable.worldx`, `worldy` and
 * `worldz`.
 */
public record Scatter(JsonObject body, MolangExpression iterations, MolangExpression chance,
    List<Axis> order) {

    /** A scatter that states nothing, which places its feature once where it is given. */
    public static final Scatter ONCE = of(new JsonObject());

    /** The Bedrock object as it stands in a pack, which is what a converted feature carries. */
    public static final Codec<Scatter> CODEC = Codec.PASSTHROUGH.xmap(
        dynamic -> of(dynamic.convert(JsonOps.INSTANCE).getValue().getAsJsonObject()),
        scatter -> new Dynamic<>(JsonOps.INSTANCE, scatter.body()));

    public static Scatter of(JsonObject distribution) {
        JsonObject body = distribution == null ? new JsonObject() : distribution;
        String written = Json.text(body, "coordinate_eval_order", "xzy").toLowerCase(Locale.ROOT);
        String order = written.length() == 3 && written.chars().distinct().count() == 3
            && written.chars().allMatch(axis -> "xyz".indexOf(axis) >= 0) ? written : "xzy";

        List<Axis> axes = new ArrayList<>();
        for (char letter : order.toCharArray()) axes.add(Axis.of(letter, body.get(String.valueOf(letter))));
        return new Scatter(body, expression(body.get("iterations"), 1), chance(body.get("scatter_chance")),
            List.copyOf(axes));
    }

    /** The positions one placement of this scatter reaches from `origin`, or none when the chance fails. */
    public List<BlockPos> positions(LevelReader level, RandomSource random, BlockPos origin) {
        MolangEnvironment environment = new MolangEnvironment();
        environment.setQueries((name, arguments) -> query(level, name, arguments));
        int[] at = {origin.getX(), origin.getY(), origin.getZ()};
        moveTo(environment, at);

        if (random.nextDouble() * 100 >= chance.evaluate(environment).number()) return List.of();

        int count = (int) iterations.evaluate(environment).number();
        List<BlockPos> positions = new ArrayList<>();
        for (int iteration = 0; iteration < count; iteration++) {
            at = new int[] {origin.getX(), origin.getY(), origin.getZ()};
            moveTo(environment, at);

            // The first grid axis evaluated steps every iteration, and each later one steps once the
            // grid axes before it have come round.
            long stride = 1;
            for (Axis axis : order) {
                int offset;
                if (axis.grid()) {
                    int cells = axis.cells(environment);
                    offset = axis.cell(environment, random, (int) (iteration / stride % cells));
                    stride *= cells;
                } else {
                    offset = axis.offset(environment, random);
                }
                at[axis.index()] += offset;
                moveTo(environment, at);
            }
            positions.add(new BlockPos(at[0], at[1], at[2]));
        }
        return positions;
    }

    private static void moveTo(MolangEnvironment environment, int[] at) {
        environment.setVariable("worldx", MolangValue.of(at[0]));
        environment.setVariable("worldy", MolangValue.of(at[1]));
        environment.setVariable("worldz", MolangValue.of(at[2]));
    }

    /** The three queries Bedrock answers while it generates a world. */
    private static MolangValue query(LevelReader level, String name, List<MolangValue> arguments) {
        return switch (name) {
            case "heightmap", "above_top_solid" -> {
                if (arguments.size() < 2) yield MolangValue.ZERO;
                Heightmap.Types surface = name.equals("heightmap")
                    ? Heightmap.Types.MOTION_BLOCKING_NO_LEAVES : Heightmap.Types.OCEAN_FLOOR;
                yield MolangValue.of(level.getHeight(surface, (int) Math.floor(arguments.get(0).number()),
                    (int) Math.floor(arguments.get(1).number())));
            }
            case "noise" -> MolangValue.of(EntityQueries.noise(arguments));
            default -> MolangValue.ZERO;
        };
    }

    /** The chance as a percentage: a number or an expression stated as one, or a fraction of its own. */
    private static MolangExpression chance(JsonElement declared) {
        if (declared != null && declared.isJsonObject()) {
            JsonObject fraction = declared.getAsJsonObject();
            double numerator = Json.number(fraction, "numerator", 1);
            double denominator = Json.number(fraction, "denominator", 1);
            return MolangExpression.constant(denominator <= 0 ? 0 : 100 * numerator / denominator);
        }
        return expression(declared, 100);
    }

    private static MolangExpression expression(JsonElement declared, double fallback) {
        if (declared == null || !declared.isJsonPrimitive()) return MolangExpression.constant(fallback);
        return declared.getAsJsonPrimitive().isNumber()
            ? MolangExpression.constant(declared.getAsDouble()) : MolangParser.parse(declared.getAsString());
    }

    /**
     * One coordinate: a value or an expression, or a distribution over an extent.
     *
     * `uniform`, `gaussian`, `inverse_gaussian` and `triangle` take the lower bound and leave the
     * upper one out. The two grids take both, so `fixed_grid` over 0 to 15 visits sixteen cells,
     * `step_size` apart and starting `grid_offset` past the lower bound.
     */
    public record Axis(int index, MolangExpression value, String distribution, MolangExpression low,
        MolangExpression high, int step, int gridOffset) {

        static Axis of(char letter, JsonElement declared) {
            int index = "xyz".indexOf(letter);
            if (declared == null || !declared.isJsonObject()) {
                return new Axis(index, expression(declared, 0), null, null, null, 1, 0);
            }

            JsonObject range = declared.getAsJsonObject();
            JsonElement extent = range.get("extent");
            List<JsonElement> bounds = extent != null && extent.isJsonArray() ? extent.getAsJsonArray().asList()
                : List.of();
            return new Axis(index, null, Json.text(range, "distribution", "uniform").toLowerCase(Locale.ROOT),
                expression(bounds.isEmpty() ? null : bounds.get(0), 0),
                expression(bounds.size() < 2 ? null : bounds.get(1), 0),
                Math.max(1, (int) Json.number(range, "step_size", 1)),
                Math.max(0, (int) Json.number(range, "grid_offset", 0)));
        }

        boolean grid() {
            return "fixed_grid".equals(distribution) || "jittered_grid".equals(distribution);
        }

        int cells(MolangEnvironment environment) {
            int from = bound(low, environment) + gridOffset;
            int to = bound(high, environment);
            return to < from ? 1 : (to - from) / step + 1;
        }

        int cell(MolangEnvironment environment, RandomSource random, int index) {
            int start = bound(low, environment) + gridOffset + index * step;
            return "jittered_grid".equals(distribution) ? start + random.nextInt(step) : start;
        }

        int offset(MolangEnvironment environment, RandomSource random) {
            if (value != null) return (int) Math.floor(value.evaluate(environment).number());

            double from = low.evaluate(environment).number();
            double span = high.evaluate(environment).number() - from;
            if (span <= 0) return (int) Math.floor(from);

            double fraction = switch (distribution) {
                case "gaussian" -> centred(random);
                case "inverse_gaussian" -> {
                    double fromMiddle = centred(random) - 0.5;
                    yield fromMiddle >= 0 ? Math.nextDown(1.0) - fromMiddle : -fromMiddle;
                }
                case "triangle" -> (random.nextDouble() + random.nextDouble()) / 2;
                default -> random.nextDouble();
            };
            return (int) Math.floor(from + fraction * span);
        }

        /** A normal spread about the middle of the range, a sixth of it wide and kept inside it. */
        private static double centred(RandomSource random) {
            return Math.clamp(0.5 + random.nextGaussian() / 6, 0, Math.nextDown(1.0));
        }

        private static int bound(MolangExpression bound, MolangEnvironment environment) {
            return (int) Math.floor(bound.evaluate(environment).number());
        }
    }
}
