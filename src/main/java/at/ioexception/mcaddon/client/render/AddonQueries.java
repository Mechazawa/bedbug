package at.ioexception.mcaddon.client.render;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.bedrock.animation.BonePose;
import at.ioexception.mcaddon.bedrock.behaviour.ServerEntityDefinition;
import at.ioexception.mcaddon.bedrock.entity.EntityAnimator;
import at.ioexception.mcaddon.bedrock.molang.MolangEnvironment;
import at.ioexception.mcaddon.bedrock.molang.MolangValue;
import at.ioexception.mcaddon.registry.AddonEntities;
import at.ioexception.mcaddon.platform.Platform;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Heightmap;
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

    /** Set for the animator of something worn or held, which is what Bedrock calls attached. */
    private final boolean attached;

    private float partialTick;

    /** The animator's own clock reading at which the entity caught fire, or -1 while it is not. */
    private float litAt = -1;

    public AddonQueries(Entity entity, EntityAnimator animator, boolean attached) {
        this.entity = entity;
        this.animator = animator;
        this.attached = attached;
    }

    public void setPartialTick(float partialTick) {
        this.partialTick = partialTick;
    }

    @Override
    public MolangValue query(String path, List<MolangValue> arguments) {
        MolangEnvironment environment = animator.environment();
        Map<String, Object> properties = EntityProperties.of(entity.getId());

        // A few queries answer with a struct that a pack reads one member off, and the parser hands
        // the member over as part of the name.
        int dot = path.indexOf('.');
        String name = dot < 0 ? path : path.substring(0, dot);
        String member = dot < 0 ? "" : path.substring(dot + 1);

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
            case "has_player_rider" -> MolangValue.of(entity.getPassengers().stream()
                .anyMatch(passenger -> passenger instanceof Player));
            case "is_riding_any_entity_of_type" -> MolangValue.of(entity.getVehicle() != null
                && isAnyType(entity.getVehicle(), arguments));
            case "is_jumping" -> MolangValue.of(isJumping());
            case "is_breathing" -> MolangValue.of(entity.getAirSupply() > 0);
            case "is_attached", "is_attached_to_entity" -> MolangValue.of(attached);
            case "has_owner" -> MolangValue.of(hasOwner());
            case "has_any_family" -> MolangValue.of(hasAnyFamily(arguments));
            case "has_cape" -> MolangValue.of(hasCape());
            case "on_fire_time" -> MolangValue.of(secondsOnFire());
            case "hurt_direction" -> MolangValue.of(entity instanceof LivingEntity living
                ? living.getHurtDir() : 0);
            case "sleep_rotation" -> MolangValue.of(bedRotation());
            case "leashed_entity_count" -> MolangValue.of(leashing().size());
            case "has_any_leashed_entity_of_type" -> MolangValue.of(leashing().stream()
                .anyMatch(mob -> isAnyType(mob, arguments)));

            // Each of these is a pose in Java, which reaches the client the way every pose does.
            case "is_sitting" -> MolangValue.of(entity.getPose() == Pose.SITTING
                || (entity instanceof TamableAnimal tamed && tamed.isInSittingPose()));
            case "is_croaking" -> MolangValue.of(entity.getPose() == Pose.CROAKING);
            case "is_roaring" -> MolangValue.of(entity.getPose() == Pose.ROARING);
            case "is_sniffing" -> MolangValue.of(entity.getPose() == Pose.SNIFFING);
            case "is_emerging" -> MolangValue.of(entity.getPose() == Pose.EMERGING);
            case "is_digging" -> MolangValue.of(entity.getPose() == Pose.DIGGING);
            case "is_jump_goal_jumping" -> MolangValue.of(entity.getPose() == Pose.LONG_JUMPING);

            // Emotes and persona skins are Bedrock's own, so neither is ever the case here, and the
            // published description of the counter is that it always answers zero.
            case "is_emoting", "is_persona_or_premium_skin", "unhappy_counter" -> MolangValue.ZERO;

            // Bedrock asks these of any entity and answers for the few its own game gives them to.
            case "is_powered" -> MolangValue.of(entity instanceof Creeper creeper && creeper.isPowered());
            case "swell_amount" -> MolangValue.of(entity instanceof Creeper creeper
                ? creeper.getSwelling(partialTick) : 0);
            case "swelling_dir" -> MolangValue.of(entity instanceof Creeper creeper
                ? creeper.getSwellDir() : 0);
            case "is_carrying_block" -> MolangValue.of(entity instanceof EnderMan enderman
                && enderman.getCarriedBlock() != null);
            case "show_bottom" -> MolangValue.of(entity instanceof EndCrystal crystal
                && crystal.showsBottom());
            case "texture_frame_index" -> MolangValue.of(entity instanceof ExperienceOrb orb
                ? orb.getIcon() : 0);

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
            case "body_x_rotation" -> MolangValue.of(bodyPitch(entity));
            case "eye_target_x_rotation" -> MolangValue.of(headPitch(entity));
            case "eye_target_y_rotation" -> MolangValue.of(headYaw(entity));
            case "yaw_speed" -> MolangValue.of(entity.getYRot() - entity.yRotO);

            case "ride_body_x_rotation" -> MolangValue.of(bodyPitch(entity.getVehicle()));
            case "ride_head_x_rotation" -> MolangValue.of(headPitch(entity.getVehicle()));
            case "ride_head_y_rotation" -> MolangValue.of(headYaw(entity.getVehicle()));
            case "rider_body_x_rotation" -> MolangValue.of(bodyPitch(rider(arguments)));
            case "rider_body_y_rotation" -> MolangValue.of(bodyYaw(rider(arguments)));
            case "rider_head_x_rotation" -> MolangValue.of(headPitch(rider(arguments)));
            case "rider_head_y_rotation" -> MolangValue.of(headYaw(rider(arguments)));

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
            case "camera_rotation" -> MolangValue.of(cameraRotation(arguments));
            case "lod_index" -> MolangValue.of(levelOfDetail(arguments));
            case "client_max_render_distance" ->
                MolangValue.of(Minecraft.getInstance().options.renderDistance().get());
            // Java is played with a keyboard and a mouse, and offers no touch controls at all.
            case "last_input_mode_is_any" -> MolangValue.of(arguments.stream()
                .anyMatch(value -> value.isText() && value.text().equals("keyboard_and_mouse")));
            case "touch_only_affects_hotbar" -> MolangValue.ZERO;

            case "time_stamp" -> MolangValue.of(entity.level().getGameTime());
            case "time_of_day" -> MolangValue.of(timeOfDay());
            case "day" -> MolangValue.of(Math.floorDiv(Platform.dayTime(entity.level()), 24000L));
            case "moon_phase" -> MolangValue.of(moonPhase());
            case "moon_brightness" -> MolangValue.of(DimensionType.MOON_BRIGHTNESS_PER_PHASE[moonPhase()]);

            // Bedrock keeps no history of earlier frames, so its average, maximum and minimum over
            // that history all come out as the time the previous frame took.
            case "average_frame_time", "maximum_frame_time", "minimum_frame_time", "last_frame_time" ->
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

            case "block_state", "block_property" -> MolangValue.of(number(properties.get(text(arguments, 0))));
            case "has_block_state", "has_block_property" -> MolangValue.of(!arguments.isEmpty()
                && properties.containsKey(text(arguments, 0)));
            case "relative_block_has_any_tag", "block_neighbor_has_any_tag" -> blockTag(arguments, true, false);
            case "relative_block_has_all_tags", "block_neighbor_has_all_tags" -> blockTag(arguments, true, true);
            case "block_has_any_tag" -> blockTag(arguments, false, false);
            case "block_has_all_tags" -> blockTag(arguments, false, true);

            case "entity_biome_has_any_tags" -> MolangValue.of(biomeTags(arguments, false));
            case "entity_biome_has_all_tags" -> MolangValue.of(biomeTags(arguments, true));
            case "entity_biome_has_any_identifier" -> MolangValue.of(biomeNamed(arguments));
            case "above_top_solid", "heightmap" -> MolangValue.of(surfaceHeight(arguments));
            case "surface_particle_color" -> surfaceColour(member);

            // Bedrock answers these while a block is being placed, and an entity being drawn is
            // never that moment.
            case "block_face", "cardinal_block_face_placed_on" -> MolangValue.of(FACING_UNDEFINED);

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
            case "equipped_item_any_tag" -> equippedTags(arguments, false);
            case "equipped_item_all_tags" -> equippedTags(arguments, true);
            case "equipped_item_is_attachable" -> MolangValue.of(
                AddonClientAssets.attachableFor(itemName(handItem(arguments), true)) != null);
            case "is_enchanted" -> MolangValue.of(worn(EquipmentSlot.MAINHAND).isEnchanted());
            case "is_selected_item" -> MolangValue.of(entity instanceof Player player
                && !player.getMainHandItem().isEmpty());
            case "main_hand_item_use_duration" -> MolangValue.of(useTicks());
            case "main_hand_item_max_duration" -> MolangValue.of(useDuration());

            case "has_armor_slot" -> hasArmourSlot(arguments);
            case "armor_damage_slot" -> armourDamage(arguments);
            case "armor_texture_slot", "armor_material_slot" -> armourNamed(arguments);
            case "armor_color_slot" -> armourColour(arguments, member);

            // How much of the cooldown a use put the held item under is left. Java keeps it as the
            // share still to run rather than as a count of ticks, which is what the swing is drawn
            // against anyway.
            case "cooldown_time_remaining" -> MolangValue.of(cooldownLeft());
            case "is_cooldown_category" -> MolangValue.of(cooldownLeft() > 0);
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
            case "owner_identifier" -> MolangValue.of(
                properties.get("owner") instanceof String owner ? owner : "");

            // Each of these is a behaviour the server is running, published under the name Bedrock
            // gives the query so a pack's own controller can branch on it.
            case "is_admiring", "is_angry", "is_begging", "is_casting", "is_celebrating",
                "is_charging", "is_eating_mob", "is_in_love", "is_laying_egg", "is_playing_dead",
                "is_ram_attacking", "is_resting", "is_rising", "is_rolling", "is_scared",
                "is_searching", "is_sneezing", "is_stalking", "timer_flag_1", "timer_flag_2",
                "timer_flag_3" -> MolangValue.of(flag(properties, name));
            case "anger_level" -> MolangValue.of(number(properties.get("anger_level")));

            // The pose is the game's own, and it reaches the drawing side without this mod sending
            // anything, so these read it rather than a property.
            case "is_standing" -> MolangValue.of(entity.getPose() == Pose.STANDING);
            case "is_laying_down" -> MolangValue.of(entity.getPose() == Pose.SLEEPING);
            case "is_scenting" -> MolangValue.of(entity.getPose() == Pose.SNIFFING);
            case "is_grazing" -> MolangValue.of(entity.getPose() == Pose.DIGGING);

            case "has_collision" -> MolangValue.of(!entity.noPhysics);
            case "invulnerable_ticks" -> MolangValue.of(entity.invulnerableTime);
            case "is_orphaned" -> MolangValue.of(!properties.containsKey("owner"));
            case "life_span" -> MolangValue.of(entity.tickCount / 20d);

            // How flattened something that moves in hops is drawn, which Bedrock reads off a slime.
            // A rising entity is stretched and a landing one is squashed, both within one block.
            case "current_squish_value" -> MolangValue.of(squish());
            case "previous_squish_value" -> MolangValue.of(squish());

            // How far through settling or standing up the entity is, which its pose already says.
            case "sit_amount" -> MolangValue.of(entity.getPose() == Pose.SITTING ? 1 : 0);
            case "lie_amount" -> MolangValue.of(entity.getPose() == Pose.SLEEPING ? 1 : 0);
            case "standing_scale" -> MolangValue.of(entity.getPose() == Pose.STANDING ? 1 : 0);
            case "is_idling" -> MolangValue.of(entity.getDeltaMovement().horizontalDistanceSqr() < 1e-4
                && !flag(properties, "has_target"));
            case "is_wall_climbing" -> MolangValue.of(entity.horizontalCollision
                && flag(properties, "can_climb"));
            case "is_critical" -> MolangValue.of(entity instanceof LivingEntity living
                && living.fallDistance > 0 && !living.onGround());

            // A wing beats faster the faster the entity is going, and its phase runs off the clock.
            case "wing_flap_speed" -> MolangValue.of(
                1 + entity.getDeltaMovement().horizontalDistance() * 10);
            case "wing_flap_position" -> MolangValue.of(
                Math.sin((environment.lifeTime() + partialTick / 20)
                    * (1 + entity.getDeltaMovement().horizontalDistance() * 10) * Math.PI * 2));
            case "cape_flap_amount" -> MolangValue.of(
                Math.min(1, entity.getDeltaMovement().horizontalDistance() * 4));

            // `minecraft:heartbeat` states the interval in seconds; the phase is how far into the
            // current beat the entity is.
            case "heartbeat_interval" -> MolangValue.of(number(properties.get("heartbeat_interval")));
            case "heartbeat_phase" -> MolangValue.of(heartbeatPhase(properties));
            case "fuse_time" -> MolangValue.of(number(properties.get("fuse_time")));

            case "actor_count" -> MolangValue.of(actorCount());
            case "all_tags" -> MolangValue.of(families(properties, arguments, true));
            case "any_tag" -> MolangValue.of(families(properties, arguments, false));
            case "has_biome_tag" -> MolangValue.of(biomeTags(arguments, false));
            case "had_component_group" -> MolangValue.of(!arguments.isEmpty()
                && properties.containsKey("group." + text(arguments, 0)));

            // Bedrock reports what a device can afford to draw. A Java client has no such tiers, and
            // the highest is what a pack written for one should get.
            case "client_memory_tier", "server_memory_tier" -> MolangValue.of(MEMORY_TIER);

            case "noise" -> MolangValue.of(noise(arguments));

            // `q.any` and `q.all` take a subject and a list of conditions the caller already resolved.
            case "any" -> MolangValue.of(arguments.stream().skip(2).anyMatch(MolangValue::truthy));
            case "all" -> MolangValue.of(arguments.stream().skip(2).allMatch(MolangValue::truthy));
            case "approx_eq" -> approximatelyEqual(arguments);
            case "count" -> MolangValue.of(arguments.size());
            case "get_actor_info_id" -> MolangValue.of(actorInfoId(arguments));
            case "log", "debug_output" -> logged(arguments);

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

    /** Whoever the entity belongs to, which the two versions keep under different names. */
    /**
     * Whether somebody has tamed this entity.
     *
     * A client holds no owner for a pack's own entity: taming writes the owner on the server, and
     * what reaches the client is the property the server syncs. The interface is asked first because
     * a vanilla mob does carry the owner across, and a pack drawing one reads it that way.
     */
    private boolean hasOwner() {
        if (EntityProperties.of(entity.getId()).get("owner") != null) return true;

        //? if >=26 {
        return entity instanceof OwnableEntity ownable && ownable.getOwnerReference() != null;
        //?} else {
        /*return entity instanceof OwnableEntity ownable && ownable.getOwnerUUID() != null;
        *///?}
    }

    /** The cape moved into the skin record in 1.21 and became a client asset in 26. */
    private boolean hasCape() {
        if (!(entity instanceof AbstractClientPlayer player)) return false;

        //? if >=26 {
        return player.getSkin().cape() != null;
        //?} elif >=1.21 {
        /*return player.getSkin().capeTexture() != null;
        *///?} else {
        /*return player.getCloakTextureLocation() != null;
        *///?}
    }

    /**
     * The camera's own angle, which a model facing the viewer turns against.
     *
     * Both accessors lost their `get` prefix on the way to 26: the camera's own did first and the
     * renderer's followed in 26.2.
     */
    private static double cameraRotation(List<MolangValue> arguments) {
        boolean vertical = arguments.isEmpty() || arguments.get(0).number() == 0;

        //? if >=26.2 {
        Camera camera = Minecraft.getInstance().gameRenderer.mainCamera();
        return vertical ? camera.xRot() : camera.yRot();
        //?} elif >=26 {
        /*Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        return vertical ? camera.xRot() : camera.yRot();
        *///?} else {
        /*Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        return vertical ? camera.getXRot() : camera.getYRot();
        *///?}
    }

    /**
     * Whether the entity is in the rising half of a jump.
     *
     * The flag Bedrock reads sits on the server, so this reads the arc instead: off the ground and
     * still going up, which a fall and a swim are not.
     */
    private boolean isJumping() {
        return !entity.onGround() && !entity.isInWater() && entity.getDeltaMovement().y > 0;
    }

    /**
     * How long the entity has been burning, in seconds.
     *
     * The tick count behind it stays on the server and only the flag reaches the client, so the
     * time is measured against the clock the animator already runs on.
     */
    private double secondsOnFire() {
        float now = animator.environment().lifeTime();
        if (!entity.isOnFire()) {
            litAt = -1;
            return 0;
        }

        if (litAt < 0) litAt = now;
        return Math.max(0, now - litAt);
    }

    /** Which way the bed faces, which a sleeping model is laid along. */
    private double bedRotation() {
        if (!(entity instanceof LivingEntity living)) return 0;

        Direction facing = living.getBedOrientation();
        return facing == null ? 0 : facing.toYRot();
    }

    /** A leash breaks past ten blocks, so a box grown by that much holds everything still on one. */
    private static final double LEASH_RANGE = 10;

    /** Everything nearby whose leash this entity is holding. */
    private List<Mob> leashing() {
        if (entity.level() == null) return List.of();

        return entity.level().getEntitiesOfClass(Mob.class,
            entity.getBoundingBox().inflate(LEASH_RANGE), mob -> mob.getLeashHolder() == entity);
    }

    /** Whether an entity is one of the types named, each spelled the way a behaviour pack spells it. */
    private static boolean isAnyType(Entity subject, List<MolangValue> arguments) {
        return arguments.stream().anyMatch(value -> value.isText()
            && subject.getType() == AddonEntities.typeOf(value.text()));
    }

    /** The families a behaviour pack gave the entity. Java's own player belongs to two of them. */
    private boolean hasAnyFamily(List<MolangValue> arguments) {
        if (entity instanceof Player) {
            return arguments.stream().anyMatch(value -> value.isText()
                && (value.text().equals("player") || value.text().equals("mob")));
        }

        ServerEntityDefinition definition = AddonEntities.definitionOf(entity.getType());
        return definition != null && arguments.stream().anyMatch(value -> value.isText()
            && definition.families().contains(value.text()));
    }

    /**
     * A number standing for an entity type, which a pack compares against another of these.
     *
     * Bedrock hands out its own running numbers, so what a pack can rely on is that one name always
     * answers the same and two names never answer alike. The registry's index does both.
     */
    private static double actorInfoId(List<MolangValue> arguments) {
        EntityType<?> type = arguments.isEmpty() || !arguments.get(0).isText() ? null
            : AddonEntities.typeOf(arguments.get(0).text());
        return type == null ? 0 : BuiltInRegistries.ENTITY_TYPE.getId(type);
    }

    /**
     * Writes a value out and hands it straight back, which is what Bedrock's own logging does.
     *
     * A render controller asks this again on every frame, so it goes to the debug log rather than
     * the one a player reads.
     */
    private static MolangValue logged(List<MolangValue> arguments) {
        if (arguments.isEmpty()) return MolangValue.ZERO;

        MolangValue value = arguments.get(0);
        McAddon.LOGGER.debug("molang: {}", value.isText() ? value.text() : value.number());
        return value;
    }

    /** Which of the given distances the entity has passed, counting from the camera. */
    private double levelOfDetail(List<MolangValue> arguments) {
        double distance = distanceFromCamera();
        return arguments.stream().filter(value -> distance > value.number()).count();
    }

    /** The passenger in the seat Bedrock names by index, or null when that seat is empty. */
    private Entity rider(List<MolangValue> arguments) {
        int index = arguments.isEmpty() ? 0 : (int) arguments.get(0).number();
        List<Entity> riders = entity.getPassengers();
        return index >= 0 && index < riders.size() ? riders.get(index) : null;
    }

    /** Java pitches the head and leaves the body upright, so only a one-piece entity has a body pitch. */
    private static double bodyPitch(Entity of) {
        return of == null || of instanceof LivingEntity ? 0 : of.getXRot();
    }

    private static double headPitch(Entity of) {
        return of == null ? 0 : of.getXRot();
    }

    /** Bedrock reports a mount's and a rider's yaw in world space rather than against the body. */
    private static double headYaw(Entity of) {
        if (of == null) return 0;
        return of instanceof LivingEntity living ? living.yHeadRot : of.getYRot();
    }

    private static double bodyYaw(Entity of) {
        if (of == null) return 0;
        return of instanceof LivingEntity living ? living.yBodyRot : of.getYRot();
    }

    /** A property the server sent that is only ever on or off. */
    /**
     * How flattened a hopping entity is drawn, between stretched at -1 and squashed at 1.
     *
     * Bedrock keeps a squish on the entity and lets it settle back towards nothing. What it comes
     * to is how fast the entity is moving up or down, which the drawing side already has.
     */
    private double squish() {
        double rising = entity.getDeltaMovement().y;
        return Math.max(-1, Math.min(1, -rising * 2));
    }

    private static boolean flag(Map<String, Object> properties, String name) {
        Object value = properties.get(name);
        return value instanceof Boolean set ? set : number(value) != 0;
    }

    private static String text(List<MolangValue> arguments, int at) {
        return arguments.size() > at && arguments.get(at).isText() ? arguments.get(at).text() : "";
    }

    /**
     * Whether a block carries the named tags.
     *
     * The first three arguments are a position and the rest are tag names. Bedrock offers the
     * position both ways round: measured from the entity, which is how a pack asks about the ground
     * under a mob, and measured from the world origin.
     */
    private MolangValue blockTag(List<MolangValue> arguments, boolean fromEntity, boolean requireAll) {
        if (arguments.size() < 4 || entity.level() == null) return MolangValue.ZERO;

        BlockPos at = new BlockPos((int) arguments.get(0).number(), (int) arguments.get(1).number(),
            (int) arguments.get(2).number());
        BlockState state = entity.level().getBlockState(
            fromEntity ? entity.blockPosition().offset(at) : at);
        List<MolangValue> tags = arguments.subList(3, arguments.size());

        return MolangValue.of(requireAll
            ? tags.stream().allMatch(tag -> carriesTag(state, tag))
            : tags.stream().anyMatch(tag -> carriesTag(state, tag)));
    }

    private static boolean carriesTag(BlockState state, MolangValue tag) {
        TagKey<Block> key = tagKey(Registries.BLOCK, tag);
        return key != null && state.is(key);
    }

    private static boolean carriesTag(ItemStack stack, MolangValue tag) {
        TagKey<Item> key = tagKey(Registries.ITEM, tag);
        return key != null && stack.is(key);
    }

    private static boolean carriesTag(Holder<Biome> biome, MolangValue tag) {
        TagKey<Biome> key = tagKey(Registries.BIOME, tag);
        return key != null && biome.is(key);
    }

    /** Null when the argument is not a name a resource location accepts, which matches nothing. */
    private static <T> TagKey<T> tagKey(ResourceKey<Registry<T>> registry, MolangValue tag) {
        Identifier parsed = tag.isText() ? Identifier.tryParse(tag.text()) : null;
        return parsed == null ? null : TagKey.create(registry, parsed);
    }

    /** The biome tags where the entity is standing, which only a resource pack may ask about. */
    private boolean biomeTags(List<MolangValue> arguments, boolean requireAll) {
        if (arguments.isEmpty() || entity.level() == null) return false;

        Holder<Biome> biome = entity.level().getBiome(entity.blockPosition());
        return requireAll
            ? arguments.stream().allMatch(tag -> carriesTag(biome, tag))
            : arguments.stream().anyMatch(tag -> carriesTag(biome, tag));
    }

    /** A pack may leave the namespace off a biome name, which parsing the argument fills back in. */
    private boolean biomeNamed(List<MolangValue> arguments) {
        if (entity.level() == null) return false;

        Holder<Biome> here = entity.level().getBiome(entity.blockPosition());
        return arguments.stream().anyMatch(value -> {
            Identifier named = value.isText() ? Identifier.tryParse(value.text()) : null;
            return named != null && here.is(named);
        });
    }

    private double surfaceHeight(List<MolangValue> arguments) {
        if (arguments.size() < 2 || entity.level() == null) return 0;

        return entity.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
            (int) arguments.get(0).number(), (int) arguments.get(1).number());
    }

    /** How far down Bedrock looks for the ground beneath an entity before it gives up. */
    private static final int SURFACE_SCAN = 10;

    /**
     * The colour of the ground under the entity, which a surface particle is tinted with.
     *
     * Bedrock answers with a struct, so the whole of it read as a number is a zero and only a named
     * channel carries anything. Ground the scan never reached leaves every channel at zero, the
     * alpha included.
     */
    private MolangValue surfaceColour(String channel) {
        BlockPos ground = groundBelow();
        if (ground == null) return MolangValue.ZERO;

        int colour = entity.level().getBlockState(ground).getMapColor(entity.level(), ground).col;
        return switch (channel) {
            case "r" -> MolangValue.of((colour >> 16 & 255) / 255d);
            case "g" -> MolangValue.of((colour >> 8 & 255) / 255d);
            case "b" -> MolangValue.of((colour & 255) / 255d);
            case "a" -> MolangValue.ONE;
            default -> MolangValue.ZERO;
        };
    }

    private BlockPos groundBelow() {
        if (entity.level() == null) return null;

        for (int down = 0; down <= SURFACE_SCAN; down++) {
            BlockPos at = entity.blockPosition().below(down);
            if (!entity.level().getBlockState(at).isAir()) return at;
        }
        return null;
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
    private static final int FACING_UNDEFINED = 6;

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
        return player == null ? MolangValue.of(FACING_UNDEFINED)
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

    /** The highest tier Bedrock reports for a device, which is what a Java client always is. */
    private static final int MEMORY_TIER = 4;

    /** The name of the item worn in one armour slot, which is what a pack branches its texture on. */
    private MolangValue armourNamed(List<MolangValue> arguments) {
        int index = arguments.isEmpty() ? -1 : (int) arguments.get(0).number();
        return index < 0 || index >= ARMOUR.size() ? MolangValue.of("")
            : MolangValue.of(itemName(worn(ARMOUR.get(index)), false));
    }

    /** The dyed colour of the item worn in one armour slot, by channel. */
    private MolangValue armourColour(List<MolangValue> arguments, String channel) {
        int index = arguments.isEmpty() ? -1 : (int) arguments.get(0).number();
        if (index < 0 || index >= ARMOUR.size()) return MolangValue.ZERO;

        //? if >=1.21 {
        int colour = net.minecraft.world.item.component.DyedItemColor.getOrDefault(
            worn(ARMOUR.get(index)), 0);
        //?} else {
        /*int colour = 0;
        *///?}
        return switch (channel) {
            case "r" -> MolangValue.of((colour >> 16 & 255) / 255d);
            case "g" -> MolangValue.of((colour >> 8 & 255) / 255d);
            case "b" -> MolangValue.of((colour & 255) / 255d);
            case "a" -> MolangValue.ONE;
            default -> MolangValue.ZERO;
        };
    }

    /** How much of the held item's cooldown is still to run, between nothing and one. */
    private double cooldownLeft() {
        return entity instanceof Player player
            ? Platform.cooldownLeft(player, player.getMainHandItem()) : 0;
    }

    /** How far into the current beat the entity is, between nothing and one whole beat. */
    private double heartbeatPhase(Map<String, Object> properties) {
        double interval = number(properties.get("heartbeat_interval"));
        if (interval <= 0) return 0;

        return (animator.environment().lifeTime() % interval) / interval;
    }

    /** How many entities the client is holding, which is what Bedrock counts. */
    private int actorCount() {
        if (!(entity.level() instanceof net.minecraft.client.multiplayer.ClientLevel client)) return 0;

        return (int) java.util.stream.StreamSupport
            .stream(client.entitiesForRendering().spliterator(), false).count();
    }

    /**
     * Whether the entity belongs to the families the arguments name.
     *
     * `minecraft:type_family` is what a Bedrock entity's families come from, and the server sends
     * the set under `family.<name>` so the drawing side can answer without knowing the pack.
     */
    private static boolean families(Map<String, Object> properties, List<MolangValue> arguments,
        boolean all) {
        boolean any = false;
        for (MolangValue argument : arguments) {
            boolean has = properties.containsKey("family." + argument.text());
            if (all && !has) return false;
            any |= has;
        }
        return all ? !arguments.isEmpty() : any;
    }

    /**
     * A number that stays the same for the same inputs and looks random across them.
     *
     * Bedrock answers `query.noise` from a Perlin field the engine seeds itself. Nothing a pack does
     * depends on the exact field, only on it being smooth and repeatable, so this is value noise
     * over the arguments with the same shape.
     */
    private static double noise(List<MolangValue> arguments) {
        double along = 0;
        for (int axis = 0; axis < arguments.size(); axis++) {
            along += arguments.get(axis).number() * NOISE_AXES[axis % NOISE_AXES.length];
        }

        double whole = Math.floor(along);
        double between = along - whole;
        double smooth = between * between * (3 - 2 * between);
        return valueAt(whole) + (valueAt(whole + 1) - valueAt(whole)) * smooth;
    }

    /** Three primes, so moving along one axis is not the same as moving along another. */
    private static final double[] NOISE_AXES = {1.0, 57.0, 113.0};

    /** One lattice point of the noise, between nothing and one. */
    private static double valueAt(double point) {
        long hashed = Double.doubleToLongBits(point) * 6364136223846793005L + 1442695040888963407L;
        return ((hashed >>> 11) & 0xFFFFF) / (double) 0xFFFFF;
    }

    private MolangValue armourDamage(List<MolangValue> arguments) {
        int index = arguments.isEmpty() ? -1 : (int) arguments.get(0).number();
        return index < 0 || index >= ARMOUR.size() ? MolangValue.ZERO
            : MolangValue.of(worn(ARMOUR.get(index)).getDamageValue());
    }

    /**
     * Whether the item in a named slot carries the tags after it.
     *
     * Bedrock spells a tag the way a resource location is spelled, so one naming no namespace is
     * Minecraft's own.
     */
    private MolangValue equippedTags(List<MolangValue> arguments, boolean requireAll) {
        EquipmentSlot slot = slotOf(text(arguments, 0));
        if (slot == null || arguments.size() < 2) return MolangValue.ZERO;

        ItemStack held = worn(slot);
        List<MolangValue> tags = arguments.subList(1, arguments.size());
        return MolangValue.of(requireAll
            ? tags.stream().allMatch(tag -> carriesTag(held, tag))
            : tags.stream().anyMatch(tag -> carriesTag(held, tag)));
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
