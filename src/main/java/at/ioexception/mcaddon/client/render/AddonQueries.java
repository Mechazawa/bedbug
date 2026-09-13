package at.ioexception.mcaddon.client.render;

import at.ioexception.mcaddon.bedrock.animation.BonePose;
import at.ioexception.mcaddon.bedrock.entity.EntityAnimator;
import at.ioexception.mcaddon.bedrock.molang.MolangEnvironment;
import at.ioexception.mcaddon.bedrock.molang.MolangValue;
import at.ioexception.mcaddon.registry.AddonEntities;
import at.ioexception.mcaddon.platform.Platform;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.dimension.DimensionType;
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
            case "is_in_water_or_rain", "is_in_contact_with_water" -> MolangValue.of(entity.isInWaterOrRain());
            case "is_in_lava" -> MolangValue.of(entity.isInLava());
            case "is_on_fire", "is_onfire" -> MolangValue.of(entity.isOnFire());
            case "is_fire_immune" -> MolangValue.of(entity.fireImmune());
            case "is_sneaking" -> MolangValue.of(entity.isShiftKeyDown());
            case "is_crawling" -> MolangValue.of(entity.isVisuallyCrawling());
            case "is_swimming" -> MolangValue.of(entity.isSwimming());
            case "is_sprinting" -> MolangValue.of(entity.isSprinting());
            case "is_riding" -> MolangValue.of(entity.isPassenger());
            case "has_rider" -> MolangValue.of(!entity.getPassengers().isEmpty());
            case "is_invisible" -> MolangValue.of(entity.isInvisible());
            case "is_silent" -> MolangValue.of(entity.isSilent());
            case "is_spectator" -> MolangValue.of(entity.isSpectator());
            case "is_local_player" -> MolangValue.of(entity == Minecraft.getInstance().player);
            case "has_gravity" -> MolangValue.of(!entity.isNoGravity());
            case "is_leashed" -> MolangValue.of(entity instanceof Mob mob && mob.isLeashed());
            case "is_moving" -> MolangValue.of(entity.position()
                .distanceToSqr(entity.xOld, entity.yOld, entity.zOld) > 0);
            case "is_sleeping" -> MolangValue.of(entity instanceof LivingEntity living && living.isSleeping());
            case "is_baby" -> MolangValue.of(entity instanceof LivingEntity living && living.isBaby());
            case "is_levitating" -> MolangValue.of(entity instanceof LivingEntity living
                && living.hasEffect(MobEffects.LEVITATION));
            case "is_using_item" -> MolangValue.of(entity instanceof LivingEntity living && living.isUsingItem());
            case "is_eating" -> MolangValue.of(isEating());
            case "blocking" -> MolangValue.of(entity instanceof LivingEntity living && living.isBlocking());
            case "hurt_time" -> MolangValue.of(entity instanceof LivingEntity living ? living.hurtTime : 0);
            case "swim_amount" -> MolangValue.of(entity instanceof LivingEntity living
                ? living.getSwimAmount(partialTick) : 0);
            // An entity with no scale attribute draws at its model's own size, which is a scale of one.
            case "model_scale" -> MolangValue.of(entity instanceof LivingEntity living ? living.getScale() : 1);
            case "player_level" -> MolangValue.of(entity instanceof Player player ? player.experienceLevel : 0);
            case "health" -> MolangValue.of(entity instanceof LivingEntity living ? living.getHealth() : 0);
            case "max_health" -> MolangValue.of(entity instanceof LivingEntity living ? living.getMaxHealth() : 0);
            case "get_name" -> MolangValue.of(entity.getName().getString());
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
            case "movement_direction" -> MolangValue.of(axis(entity.getDeltaMovement().normalize(), arguments));
            case "walk_distance" -> MolangValue.of(walkedDistance());

            case "cardinal_facing" -> MolangValue.of(cardinalFacing(entity.getYRot(), entity.getXRot()));
            case "cardinal_facing_2d" -> MolangValue.of(compassFacing(entity.getYRot()));
            case "cardinal_player_facing" -> playerFacing();

            case "distance_from_camera" -> MolangValue.of(distanceFromCamera());
            case "camera_distance_range_lerp" -> cameraDistanceLerp(arguments);
            case "rotation_to_camera" -> MolangValue.of(rotationToCamera(arguments));
            case "is_first_person" -> MolangValue.of(Minecraft.getInstance().options
                .getCameraType().isFirstPerson());
            case "is_in_ui" -> MolangValue.ZERO;
            case "graphics_mode_is_any" -> MolangValue.ZERO;

            case "time_stamp" -> MolangValue.of(entity.level().getGameTime());
            case "time_of_day" -> MolangValue.of(timeOfDay());
            case "day" -> MolangValue.of(Math.floorDiv(Platform.dayTime(entity.level()), 24000L));
            case "moon_phase" -> MolangValue.of(moonPhase());
            case "moon_brightness" -> MolangValue.of(DimensionType.MOON_BRIGHTNESS_PER_PHASE[moonPhase()]);

            // Bedrock keeps no history of earlier frames, so its average, maximum and minimum over
            // that history all come out as the time the previous frame took.
            case "average_frame_time", "maximum_frame_time", "minimum_frame_time" ->
                MolangValue.of(environment.deltaTime());

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

            // Each of these names a Bedrock component whose whole effect is a flag on the entity, so
            // the property the server sent under that name is the state the component would carry.
            case "is_charged", "is_chested", "is_ignited", "is_illager_captain", "is_pregnant",
                "is_saddled", "is_shaking", "is_stackable", "is_stunned", "is_tamed",
                "can_climb", "can_fly", "can_power_jump" -> MolangValue.of(flag(properties, name));

            case "block_state" -> MolangValue.of(number(properties.get(text(arguments, 0))));
            case "relative_block_has_any_tag" -> relativeBlockTag(arguments, false);
            case "relative_block_has_all_tags" -> relativeBlockTag(arguments, true);

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
            case "main_hand_item_use_duration" -> MolangValue.of(useTicks());
            case "main_hand_item_max_duration" -> MolangValue.of(useDuration());

            case "has_armor_slot" -> hasArmourSlot(arguments);
            case "equipment_count" -> MolangValue.of(ARMOUR.stream()
                .filter(slot -> !worn(slot).isEmpty()).count());
            case "has_head_gear" -> MolangValue.of(!worn(EquipmentSlot.HEAD).isEmpty());
            case "is_item_equipped" -> MolangValue.of(!handItem(arguments).isEmpty());
            case "is_item_name_any" -> itemNameAny(arguments);
            case "get_equipped_item_name" -> MolangValue.of(itemName(handItem(arguments), false));
            case "max_durability" -> MolangValue.of(worn(EquipmentSlot.MAINHAND).getMaxDamage());
            case "remaining_durability" -> MolangValue.of(remainingDurability());

            // Bedrock reports these three in seconds, where the game counts them in ticks.
            case "item_in_use_duration" -> MolangValue.of(useTicks() / 20d);
            case "item_max_use_duration" -> MolangValue.of(useDuration() / 20d);
            case "item_remaining_use_duration" -> itemRemainingUse(arguments);

            case "is_owner_identifier_any" -> MolangValue.ZERO;
            case "owning_entity" -> MolangValue.ZERO;

            // `q.any` and `q.all` take a subject and a list of conditions the caller already resolved.
            case "any" -> MolangValue.of(arguments.stream().skip(2).anyMatch(MolangValue::truthy));
            case "all" -> MolangValue.of(arguments.stream().skip(2).allMatch(MolangValue::truthy));
            case "approx_eq" -> approximatelyEqual(arguments);
            case "count" -> MolangValue.of(arguments.size());

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
     * Whether a block near the entity carries the named tags.
     *
     * The first three arguments are an offset in blocks and the rest are tag names, which is the
     * shape Bedrock gives this: a pack asks about the ground under a mob by offsetting down one.
     */
    private MolangValue relativeBlockTag(List<MolangValue> arguments, boolean requireAll) {
        if (arguments.size() < 4 || entity.level() == null) return MolangValue.ZERO;

        net.minecraft.core.BlockPos at = entity.blockPosition().offset(
            (int) arguments.get(0).number(), (int) arguments.get(1).number(),
            (int) arguments.get(2).number());
        BlockState state = entity.level().getBlockState(at);
        List<MolangValue> tags = arguments.subList(3, arguments.size());

        return MolangValue.of(requireAll
            ? tags.stream().allMatch(tag -> carriesTag(state, tag))
            : tags.stream().anyMatch(tag -> carriesTag(state, tag)));
    }

    private static boolean carriesTag(BlockState state, MolangValue tag) {
        if (!tag.isText()) return false;

        Identifier parsed = Identifier.tryParse(tag.text());
        return parsed != null && state.is(net.minecraft.tags.TagKey.create(
            net.minecraft.core.registries.Registries.BLOCK, parsed));
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

    /** The use-animation enum carries a different name per version. Its constants do not. */
    private boolean isEating() {
        if (!(entity instanceof LivingEntity living) || !living.isUsingItem()) return false;

        String animation = living.getUseItem().getUseAnimation().name();
        return animation.equals("EAT") || animation.equals("DRINK");
    }

    /** The game counts ticks from dawn and Bedrock counts a day from midnight, a quarter apart. */
    private double timeOfDay() {
        double sinceDawn = Math.floorMod(Platform.dayTime(entity.level()), 24000L);
        return (sinceDawn / 24000 + 0.25) % 1;
    }

    private int moonPhase() {
        return (int) Math.floorMod(Math.floorDiv(Platform.dayTime(entity.level()), 24000L), 8L);
    }

    /** Bedrock's own facing numbers: Down=0, Up=1, North=2, South=3, West=4, East=5, Undefined=6. */
    private static final int[] COMPASS = {3, 4, 2, 5};

    private static double compassFacing(float yaw) {
        return COMPASS[Math.floorMod(Math.round(yaw / 90), 4)];
    }

    private static double cardinalFacing(float yaw, float pitch) {
        if (pitch > 45) return 0;
        if (pitch < -45) return 1;
        return compassFacing(yaw);
    }

    private static MolangValue playerFacing() {
        Player player = Minecraft.getInstance().player;
        return player == null ? MolangValue.of(6)
            : MolangValue.of(cardinalFacing(player.getYRot(), player.getXRot()));
    }

    /**
     * Where the entity sits between two distances from the camera, as a fraction.
     *
     * The near distance may be the larger of the two, which reverses the fraction instead of
     * emptying the range.
     */
    private MolangValue cameraDistanceLerp(List<MolangValue> arguments) {
        if (arguments.size() < 2) return MolangValue.ZERO;

        double near = arguments.get(0).number();
        double far = arguments.get(1).number();
        if (near == far) return MolangValue.ZERO;

        return MolangValue.of(Math.clamp((distanceFromCamera() - near) / (far - near), 0, 1));
    }

    private static MolangValue approximatelyEqual(List<MolangValue> arguments) {
        if (arguments.size() < 2) return MolangValue.ZERO;

        double first = arguments.get(0).number();
        return MolangValue.of(arguments.stream()
            .allMatch(value -> Math.abs(value.number() - first) < 1.0E-6));
    }

    private static final List<EquipmentSlot> ARMOUR =
        List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET);

    private int useTicks() {
        return entity instanceof LivingEntity living ? living.getTicksUsingItem() : 0;
    }

    private ItemStack worn(EquipmentSlot slot) {
        return entity instanceof LivingEntity living ? living.getItemBySlot(slot) : ItemStack.EMPTY;
    }

    /** Bedrock names a hand by its index or in words, and takes the main hand when neither is given. */
    private ItemStack handItem(List<MolangValue> arguments) {
        boolean offHand = !arguments.isEmpty() && (arguments.get(0).isText()
            ? arguments.get(0).text().equals("off_hand") : arguments.get(0).number() == 1);
        return worn(offHand ? EquipmentSlot.OFFHAND : EquipmentSlot.MAINHAND);
    }

    private MolangValue hasArmourSlot(List<MolangValue> arguments) {
        int index = arguments.isEmpty() ? -1 : (int) arguments.get(0).number();
        return index < 0 || index >= ARMOUR.size() ? MolangValue.ZERO
            : MolangValue.of(!worn(ARMOUR.get(index)).isEmpty());
    }

    private int remainingDurability() {
        ItemStack held = worn(EquipmentSlot.MAINHAND);
        return held.getMaxDamage() - held.getDamageValue();
    }

    /** Bedrock leaves the namespace off a name unless the caller asks for the full one. */
    private static String itemName(ItemStack stack, boolean full) {
        if (stack.isEmpty()) return "";

        Identifier key = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return full ? key.toString() : key.getPath();
    }

    /**
     * Whether the item in a named slot carries any of the names after it.
     *
     * Bedrock lets a slot with several places carry an index between the slot and the names, so the
     * names start at whichever of the next two arguments is text. An empty name asks for an empty slot.
     */
    private MolangValue itemNameAny(List<MolangValue> arguments) {
        EquipmentSlot slot = slotOf(text(arguments, 0));
        if (slot == null || arguments.size() < 2) return MolangValue.ZERO;

        String name = itemName(worn(slot), true);
        int firstName = arguments.get(1).isText() ? 1 : 2;
        return MolangValue.of(arguments.stream().skip(firstName)
            .anyMatch(value -> value.isText() && value.text().equals(name)));
    }

    /**
     * How long the item being used has left, in seconds.
     *
     * A second argument turns the answer into the remaining fraction scaled by that value. Only the
     * hand that is using something answers, because no other hand has a duration to count.
     */
    private MolangValue itemRemainingUse(List<MolangValue> arguments) {
        InteractionHand asked = text(arguments, 0).equals("off_hand")
            ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        if (!(entity instanceof LivingEntity living) || !living.isUsingItem()
            || living.getUsedItemHand() != asked) {
            return MolangValue.ZERO;
        }

        int maximum = useDuration();
        double remaining = living.getUseItemRemainingTicks();
        return MolangValue.of(arguments.size() > 1 && !arguments.get(1).isText() && maximum > 0
            ? remaining / maximum * arguments.get(1).number()
            : remaining / 20);
    }

    /** The equipment slot behind one of Bedrock's slot names, spelled as `replaceitem` takes it. */
    private static EquipmentSlot slotOf(String slot) {
        return switch (slot.toLowerCase(java.util.Locale.ROOT)) {
            case "slot.weapon.mainhand", "main_hand" -> EquipmentSlot.MAINHAND;
            case "slot.weapon.offhand", "off_hand" -> EquipmentSlot.OFFHAND;
            case "slot.armor.head", "head" -> EquipmentSlot.HEAD;
            case "slot.armor.chest", "chest" -> EquipmentSlot.CHEST;
            case "slot.armor.legs", "legs" -> EquipmentSlot.LEGS;
            case "slot.armor.feet", "feet" -> EquipmentSlot.FEET;
            default -> null;
        };
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
