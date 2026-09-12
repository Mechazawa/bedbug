package at.ioexception.mcaddon.client.render;

import at.ioexception.mcaddon.bedrock.animation.BonePose;
import at.ioexception.mcaddon.bedrock.entity.EntityAnimator;
import at.ioexception.mcaddon.bedrock.molang.MolangEnvironment;
import at.ioexception.mcaddon.bedrock.molang.MolangValue;
import at.ioexception.mcaddon.registry.AddonEntities;
import at.ioexception.mcaddon.platform.Platform;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;

/**
 * Answers `q.*` for one entity.
 *
 * The values a render controller branches on come from two places: the entity itself, which the
 * client already has, and the properties a behaviour pack set, which the server sent. Anything not
 * answered here reads as zero, which is what Bedrock does with an unknown query.
 */
public final class AddonQueries implements MolangEnvironment.Queries {
    private final Entity entity;
    private final EntityAnimator animator;
    private float partialTick;

    public AddonQueries(Entity entity, EntityAnimator animator) {
        this.entity = entity;
        this.animator = animator;
    }

    public void setPartialTick(float partialTick) {
        this.partialTick = partialTick;
    }

    @Override
    public MolangValue query(String name, List<MolangValue> arguments) {
        MolangEnvironment environment = animator.environment();
        Map<String, Object> properties = EntityProperties.of(entity.getId());

        return switch (name) {
            case "anim_time" -> MolangValue.of(environment.animTime());
            case "life_time" -> MolangValue.of(environment.lifeTime());
            case "delta_time" -> MolangValue.of(environment.deltaTime());
            case "state_time" -> MolangValue.of(environment.stateTime());
            case "frame_alpha" -> MolangValue.of(partialTick);

            case "is_alive" -> MolangValue.of(entity.isAlive());
            case "is_on_ground" -> MolangValue.of(entity.onGround());
            case "is_in_water" -> MolangValue.of(entity.isInWater());
            case "is_in_water_or_rain" -> MolangValue.of(entity.isInWaterOrRain());
            case "is_on_fire" -> MolangValue.of(entity.isOnFire());
            case "is_sneaking" -> MolangValue.of(entity.isShiftKeyDown());
            case "is_swimming" -> MolangValue.of(entity.isSwimming());
            case "is_riding" -> MolangValue.of(entity.isPassenger());
            case "has_rider" -> MolangValue.of(!entity.getPassengers().isEmpty());
            case "is_invisible" -> MolangValue.of(entity.isInvisible());
            case "is_sleeping" -> MolangValue.of(entity instanceof LivingEntity living && living.isSleeping());
            case "is_baby" -> MolangValue.of(entity instanceof LivingEntity living && living.isBaby());
            case "health" -> MolangValue.of(entity instanceof LivingEntity living ? living.getHealth() : 0);
            case "max_health" -> MolangValue.of(entity instanceof LivingEntity living ? living.getMaxHealth() : 0);
            case "is_name_any" -> MolangValue.of(arguments.stream()
                .anyMatch(value -> value.isText() && value.text().equals(nameOf())));

            case "body_y_rotation" -> MolangValue.of(entity instanceof LivingEntity living
                ? living.yBodyRot : entity.getYRot());
            case "target_y_rotation", "head_y_rotation" -> MolangValue.of(entity instanceof LivingEntity living
                ? living.yHeadRot - living.yBodyRot : 0);
            case "target_x_rotation", "head_x_rotation" -> MolangValue.of(entity.getXRot());
            case "yaw_speed" -> MolangValue.of(entity.getYRot() - entity.yRotO);

            case "ground_speed" -> MolangValue.of(horizontalSpeed());
            case "modified_move_speed" -> MolangValue.of(Math.min(1, horizontalSpeed() / 0.22));
            case "vertical_speed" -> MolangValue.of(entity.getDeltaMovement().y * 20);
            case "position" -> MolangValue.of(axis(entity.position(), arguments));
            case "position_delta" -> MolangValue.of(axis(entity.getDeltaMovement(), arguments));

            case "distance_from_camera" -> MolangValue.of(distanceFromCamera());
            case "rotation_to_camera" -> MolangValue.of(rotationToCamera(arguments));
            case "is_first_person" -> MolangValue.of(Minecraft.getInstance().options
                .getCameraType().isFirstPerson());
            case "is_in_ui" -> MolangValue.ZERO;
            case "graphics_mode_is_any" -> MolangValue.ZERO;

            case "any_animation_finished" -> MolangValue.of(animator.player().anyFinished());
            case "all_animations_finished" -> MolangValue.of(animator.player().allFinished());

            case "property" -> property(properties, arguments);
            case "has_property" -> MolangValue.of(!arguments.isEmpty()
                && properties.containsKey(arguments.get(0).text()));
            case "variant" -> MolangValue.of(number(properties.get("variant")));
            case "mark_variant" -> MolangValue.of(number(properties.get("mark_variant")));
            case "skin_id" -> MolangValue.of(number(properties.get("skin_id")));

            case "is_sheared" -> MolangValue.of(flag(properties, "is_sheared"));
            case "is_transforming" -> MolangValue.of(flag(properties, "is_transforming"));
            case "is_gliding" -> MolangValue.of(entity instanceof LivingEntity living && living.isFallFlying());
            case "is_avoiding_mobs" -> MolangValue.of(flag(properties, "is_avoiding_mobs"));
            case "has_target" -> MolangValue.of(flag(properties, "has_target"));
            case "last_hit_by_player" -> MolangValue.of(flag(properties, "last_hit_by_player"));
            case "trade_tier" -> MolangValue.of(number(properties.get("trade_tier")));
            case "death_ticks" -> MolangValue.of(entity instanceof LivingEntity living
                ? living.deathTime : 0);

            case "block_state" -> MolangValue.of(number(properties.get(text(arguments, 0))));
            case "relative_block_has_any_tag" -> relativeBlockTag(arguments);

            case "in_range" -> MolangValue.of(arguments.size() >= 3
                && arguments.get(0).number() >= arguments.get(1).number()
                && arguments.get(0).number() <= arguments.get(2).number());
            case "facing_target_to_range_attack" -> MolangValue.of(flag(properties, "has_target"));
            case "modified_distance_moved" -> MolangValue.of(walkedDistance() * 8);
            case "ride_body_y_rotation" -> MolangValue.of(entity.getVehicle() == null ? 0
                : entity.getVehicle() instanceof LivingEntity riding ? riding.yBodyRot
                : entity.getVehicle().getYRot());

            case "get_animation_frame" -> MolangValue.of(environment.animTime());
            case "bone_orientation_trs", "bone_origin" -> boneOrigin(arguments);

            case "item_slot" -> MolangValue.of(number(properties.get("item_slot")));
            case "item_slot_to_bone_name" -> MolangValue.of(boneForSlot(text(arguments, 0)));
            case "item_is_charged" -> MolangValue.of(flag(properties, "item_is_charged"));
            case "equipped_item_any_tag" -> MolangValue.ZERO;
            case "main_hand_item_use_duration" -> MolangValue.of(entity instanceof LivingEntity living
                ? living.getTicksUsingItem() : 0);
            case "main_hand_item_max_duration" -> MolangValue.of(useDuration());

            case "is_owner_identifier_any" -> MolangValue.ZERO;
            case "owning_entity" -> MolangValue.ZERO;

            // `q.any` and `q.all` take a subject and a list of conditions the caller already resolved.
            case "any" -> MolangValue.of(arguments.stream().skip(2).anyMatch(MolangValue::truthy));
            case "all" -> MolangValue.of(arguments.stream().skip(2).allMatch(MolangValue::truthy));

            default -> unanswered(name);
        };
    }

    /**
     * A query this mod has no answer for.
     *
     * Bedrock answers zero for a query it does not know, so answering zero is right. Counting it is
     * what stops a query nobody implemented from looking like one that legitimately returned zero,
     * which is the difference between a reported gap and a model that silently sits still.
     */
    private static MolangValue unanswered(String name) {
        if (UNANSWERED.add(name)) AddonEntities.unsupported("query q." + name);
        return MolangValue.ZERO;
    }

    /** Counted once each, because a query is asked again on every frame it is read. */
    private static final Set<String> UNANSWERED = new HashSet<>();

    /** How long the held item takes to use. Who is using it became part of the question in 1.21. */
    private int useDuration() {
        if (!(entity instanceof LivingEntity living)) return 0;

        //? if >=1.21 {
        return living.getUseItem().getUseDuration(living);
        //?} else {
        /*return living.getUseItem().getUseDuration();
        *///?}
    }

    /** How far the entity has walked, which the field was renamed for in 1.21.2. */
    private float walkedDistance() {
        //? if >=1.21.2 {
        return entity.moveDist;
        //?} else {
        /*return entity.walkDist;
        *///?}
    }

    /** A property the server sent that is only ever on or off. */
    private static boolean flag(Map<String, Object> properties, String name) {
        Object value = properties.get(name);
        return value instanceof Boolean set ? set : number(value) != 0;
    }

    private static String text(List<MolangValue> arguments, int at) {
        return arguments.size() > at && arguments.get(at).isText() ? arguments.get(at).text() : "";
    }

    /**
     * Whether a block near the entity carries any of the named tags.
     *
     * The first three arguments are an offset in blocks and the rest are tag names, which is the
     * shape Bedrock gives this: a pack asks about the ground under a mob by offsetting down one.
     */
    private MolangValue relativeBlockTag(List<MolangValue> arguments) {
        if (arguments.size() < 4 || entity.level() == null) return MolangValue.ZERO;

        net.minecraft.core.BlockPos at = entity.blockPosition().offset(
            (int) arguments.get(0).number(), (int) arguments.get(1).number(),
            (int) arguments.get(2).number());
        net.minecraft.world.level.block.state.BlockState state = entity.level().getBlockState(at);

        for (int i = 3; i < arguments.size(); i++) {
            if (!arguments.get(i).isText()) continue;

            Identifier parsed = Identifier.tryParse(arguments.get(i).text());
            if (parsed != null && state.is(net.minecraft.tags.TagKey.create(
                net.minecraft.core.registries.Registries.BLOCK, parsed))) {
                return MolangValue.ONE;
            }
        }
        return MolangValue.ZERO;
    }

    /**
     * Where one of the model's bones currently sits, in the space the model is built in.
     *
     * A pack asks this to hang one thing off another, and the axis it wants is the second argument.
     */
    private MolangValue boneOrigin(List<MolangValue> arguments) {
        BonePose pose = animator.poseOf(text(arguments, 0));
        if (pose == null) return MolangValue.ZERO;

        return MolangValue.of(axis(new Vec3(pose.position.x(), pose.position.y(), pose.position.z()),
            arguments.subList(Math.min(1, arguments.size()), arguments.size())));
    }

    /** The bone an attachable hangs from for a given equipment slot, under Bedrock's own names. */
    private static String boneForSlot(String slot) {
        return switch (slot.toLowerCase(java.util.Locale.ROOT)) {
            case "slot.weapon.mainhand", "main_hand" -> "rightitem";
            case "slot.weapon.offhand", "off_hand" -> "leftitem";
            case "slot.armor.head", "head" -> "helmet";
            case "slot.armor.chest", "chest" -> "chestplate";
            case "slot.armor.legs", "legs" -> "leggings";
            case "slot.armor.feet", "feet" -> "boots";
            default -> "";
        };
    }

    private String nameOf() {
        return entity.getCustomName() == null ? "" : entity.getCustomName().getString();
    }

    private double horizontalSpeed() {
        Vec3 movement = entity.getDeltaMovement();
        return Math.sqrt(movement.x * movement.x + movement.z * movement.z);
    }

    private double distanceFromCamera() {
        return Platform.cameraPosition().distanceTo(entity.position());
    }

    /** The angle from the entity to the camera, which billboarded bones turn by. */
    private double rotationToCamera(List<MolangValue> arguments) {
        Vec3 camera = Platform.cameraPosition();
        Vec3 toCamera = camera.subtract(entity.position());
        boolean vertical = !arguments.isEmpty() && arguments.get(0).number() == 0;
        return vertical
            ? -Math.toDegrees(Math.atan2(toCamera.y, Math.sqrt(toCamera.x * toCamera.x + toCamera.z * toCamera.z)))
            : Math.toDegrees(Math.atan2(toCamera.z, toCamera.x)) - 90 - entity.getYRot();
    }

    private static double axis(Vec3 vector, List<MolangValue> arguments) {
        int index = arguments.isEmpty() ? 0 : (int) arguments.get(0).number();
        return switch (index) {
            case 1 -> vector.y;
            case 2 -> vector.z;
            default -> vector.x;
        };
    }

    private static MolangValue property(Map<String, Object> properties, List<MolangValue> arguments) {
        if (arguments.isEmpty()) return MolangValue.ZERO;
        Object value = properties.get(arguments.get(0).text());
        if (value instanceof Boolean flag) return MolangValue.of(flag);
        if (value instanceof Number number) return MolangValue.of(number.doubleValue());
        return value == null ? MolangValue.ZERO : MolangValue.of(String.valueOf(value));
    }

    private static double number(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0;
    }
}
