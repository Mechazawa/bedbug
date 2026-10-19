package at.ioexception.mcaddon.bedrock.aim;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Everything one player's client needs to pick an aim assist target: the cone, the preset and every
 * category the preset names, sent as one instruction and read back whole.
 */
public record AimAssistTargeting(AimAssistSettings settings, AimAssistPreset preset,
    Map<String, AimAssistCategory> categories) {

    /** The kind an instruction carrying these is sent under; a body of `{}` turns aim assist off. */
    public static final String KIND = "aim_assist";

    public String encode() {
        JsonObject json = new JsonObject();
        json.add("settings", settings.toJson());
        json.add("preset", preset.toJson());
        JsonArray named = new JsonArray();
        categories.values().stream().map(AimAssistCategory::toJson).forEach(named::add);
        json.add("categories", named);
        return json.toString();
    }

    /** Null for the body that turns aim assist off. */
    public static AimAssistTargeting decode(String body) {
        JsonObject json = JsonParser.parseString(body).getAsJsonObject();
        if (!json.has("settings")) return null;

        Map<String, AimAssistCategory> categories = new LinkedHashMap<>();
        json.getAsJsonArray("categories").asList().stream().map(JsonElement::getAsJsonObject)
            .map(AimAssistCategory::tryFrom).filter(Objects::nonNull)
            .forEach(category -> categories.put(category.identifier(), category));
        return new AimAssistTargeting(AimAssistSettings.fromCamera(json.getAsJsonObject("settings")),
            AimAssistPreset.tryFrom(json.getAsJsonObject("preset")), categories);
    }

    private record Candidate(double score, Supplier<HitResult> sighted) {
    }

    /**
     * The block or entity aim assist settles on for this player, or null where nothing in the cone
     * qualifies.
     *
     * The cone points the way the player faces, whatever the camera does. Candidates are tried best
     * first, and the first the player's eyes can see wins: an entity no block stands in front of, or
     * a block a line to its middle reaches before any other. A liquid is a candidate only while the
     * held item is on the preset's liquid list, and then lines of sight stop at liquids as well.
     */
    public HitResult pick(Player player) {
        Level level = player.level();
        Vec3 eye = player.getEyePosition();
        float yRot = player.getYRot();
        float xRot = player.getXRot();
        ItemStack held = player.getMainHandItem();
        AimAssistCategory category = categories.getOrDefault(preset.categoryFor(held), AimAssistCategory.DEFAULT);
        ClipContext.Fluid fluids = preset.targetsLiquidsWith(held) ? ClipContext.Fluid.SOURCE_ONLY
            : ClipContext.Fluid.NONE;
        AABB reach = new AABB(eye, eye).inflate(settings.distance());

        Stream<Candidate> entities = level.getEntities(player, reach,
                entity -> entity.isPickable() && !entity.isSpectator() && !preset.excludes(entity))
            .stream().map(entity -> {
                Vec3 middle = entity.getBoundingBox().getCenter();
                return new Candidate(settings.score(eye, yRot, xRot, middle, category.priorityOf(entity)),
                    () -> level.clip(new ClipContext(eye, middle, ClipContext.Block.OUTLINE, fluids, player))
                        .getType() == HitResult.Type.MISS ? new EntityHitResult(entity, middle) : null);
            });
        Stream<Candidate> blocks = BlockPos.betweenClosedStream(reach).map(BlockPos::immutable).map(position -> {
            BlockState state = level.getBlockState(position);
            boolean liquid = state.getBlock() instanceof LiquidBlock;
            VoxelShape shape = state.getShape(level, position);
            if (state.isAir() || (liquid ? fluids == ClipContext.Fluid.NONE : shape.isEmpty())
                || preset.excludes(state)) {
                return null;
            }
            Vec3 middle = liquid ? Vec3.atCenterOf(position)
                : shape.bounds().getCenter().add(Vec3.atLowerCornerOf(position));
            return new Candidate(settings.score(eye, yRot, xRot, middle, category.priorityOf(state)),
                () -> {
                    BlockHitResult hit = level.clip(
                        new ClipContext(eye, middle, ClipContext.Block.OUTLINE, fluids, player));
                    return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(position) ? hit : null;
                });
        }).filter(Objects::nonNull);

        return Stream.concat(entities, blocks).filter(candidate -> !Double.isNaN(candidate.score()))
            .sorted(Comparator.comparingDouble(Candidate::score))
            .map(candidate -> candidate.sighted().get()).filter(Objects::nonNull)
            .findFirst().orElse(null);
    }
}
