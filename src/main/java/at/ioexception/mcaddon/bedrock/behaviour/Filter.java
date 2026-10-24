package at.ioexception.mcaddon.bedrock.behaviour;

import at.ioexception.mcaddon.convert.BedrockItemTags;
import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.registry.AddonBlocks;
import at.ioexception.mcaddon.registry.AddonEntities;
import at.ioexception.mcaddon.registry.AddonItems;
import at.ioexception.mcaddon.state.AddonState;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.raid.Raider;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.item.trading.Merchant;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Bedrock's entity filters, which are a small expression language of their own and nothing to do
 * with Molang.
 *
 * A filter is a tree of `all_of`, `any_of` and `none_of` over leaves that name a test, a subject to
 * run it against, an operator and a value. A test this mod does not implement reads as false and is
 * counted, so a pack whose condition never holds says why.
 */
public sealed interface Filter {
    boolean matches(Context context);

    /**
     * Everything a test can be asked about: who is being tested, who is doing the testing, and the
     * damage a damage sensor is deciding about, with how much of it there is before armour.
     */
    record Context(Entity self, Entity other, Entity target, AddonState state, DamageSource damage,
        float amount) {
        public Context(Entity self, Entity other, Entity target, AddonState state) {
            this(self, other, target, state, null, 0f);
        }

        public Entity subject(String name) {
            return switch (name) {
                case "other", "damager", "player", "parent" -> other;
                case "target" -> target;
                default -> self;
            };
        }
    }

    record All(List<Filter> parts) implements Filter {
        @Override
        public boolean matches(Context context) {
            return parts.stream().allMatch(part -> part.matches(context));
        }
    }

    record Any(List<Filter> parts) implements Filter {
        @Override
        public boolean matches(Context context) {
            return parts.stream().anyMatch(part -> part.matches(context));
        }
    }

    record None(List<Filter> parts) implements Filter {
        @Override
        public boolean matches(Context context) {
            return parts.stream().noneMatch(part -> part.matches(context));
        }
    }

    /** One test, with the domain a few of them take (an equipment slot, a property name). */
    record Test(String name, String subject, String operator, String domain, JsonElement value)
        implements Filter {

        /** Java's brightness for each of the eight moon phases, which is Bedrock's intensity. */
        private static final double[] MOON_BRIGHTNESS = {1, 0.75, 0.5, 0.25, 0, 0.25, 0.5, 0.75};

        /** The Bedrock dye palette, in the order its numbers are given. */
        private static final List<String> PALETTE = List.of("white", "orange", "magenta",
            "light_blue", "yellow", "light_green", "pink", "gray", "silver", "cyan", "purple",
            "blue", "brown", "green", "red", "black");

        private static final List<EquipmentSlot> ARMOUR = List.of(
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET);
        private static final List<EquipmentSlot> HANDS = List.of(
            EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND);
        private static final List<EquipmentSlot> EVERY_SLOT = List.of(
            EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND, EquipmentSlot.HEAD,
            EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET);

        /** The blocks that burn whatever stands on them. */
        private static final Set<String> HOT_BLOCKS = Set.of(
            "lava", "magma_block", "fire", "soul_fire", "campfire", "soul_campfire");

        /** Bedrock's biome categories under the word Java names the same ground with. */
        private static final Map<String, String> BIOME_WORDS = Map.of(
            "extreme_hills", "mountain", "mesa", "badlands", "plain", "plains",
            "mushroom_island", "mushroom", "stone_beach", "stony_shore", "the_nether", "nether",
            "the_end", "end", "ice", "frozen");

        /** The rules `Platform.gameRule` maps, in the mixed case Java spells them with. */
        private static final List<String> GAME_RULES = List.of(
            "doDayLightCycle", "doWeatherCycle", "keepInventory", "mobGriefing",
            "naturalRegeneration", "sendCommandFeedback", "showDeathMessages", "doImmediateRespawn",
            "doEntityDrops", "doTileDrops", "commandBlockOutput", "randomTickSpeed");

        @Override
        public boolean matches(Context context) {
            Entity entity = context.subject(subject);
            if (entity == null) return false;

            Boolean outcome = evaluate(entity, context);
            if (outcome == null) {
                AddonEntities.unsupported("filter " + name);
                return false;
            }
            return negated() != outcome;
        }

        /** Null when the test is not implemented, which is different from a test that came out false. */
        private Boolean evaluate(Entity entity, Context context) {
            return switch (name) {
                case "is_family" -> family(entity, text());
                case "has_tag" -> Platform.tagsOf(entity).contains(text());
                case "has_target" -> flag(entity instanceof Mob mob && mob.getTarget() != null);
                case "is_baby" -> flag(entity instanceof LivingEntity living && living.isBaby());
                // Java reads one key for both the sneak input and the pose it puts an entity in.
                case "is_sneaking", "is_sneak_held" -> flag(entity.isShiftKeyDown());
                case "is_sleeping" -> flag(entity instanceof LivingEntity living && living.isSleeping());
                // The three conditions the panic goal itself runs away from. The attacker a mob
                // remembers is cleared for it after a hundred ticks, so this stops on its own.
                case "is_panicking" -> flag(entity.isOnFire() || entity.isFullyFrozen()
                    || (entity instanceof LivingEntity hurt && hurt.getLastHurtByMob() != null));
                case "is_riding" -> flag(entity.isPassenger());
                case "is_leashed" -> flag(entity instanceof Mob mob && mob.isLeashed());
                case "is_moving" -> flag(entity.getDeltaMovement().horizontalDistanceSqr() > 1.0E-4);
                case "on_ground" -> flag(entity.onGround());
                case "in_water" -> flag(entity.isInWater());
                case "in_lava" -> flag(entity.isInLava());
                case "is_underwater" -> flag(entity.isUnderWater());
                // Bedrock counts a splash water bottle as contact as well; Java has water and rain.
                case "in_water_or_rain", "in_contact_with_water" -> flag(entity.isInWaterOrRain());
                case "is_visible" -> flag(!entity.isInvisible());
                case "is_underground" -> flag(entity.getY() < entity.level().getSeaLevel() - 8);
                case "is_daytime" -> flag(Platform.dayTime(entity.level()) % 24000L < 12000L);
                case "is_difficulty" -> entity.level().getDifficulty().name().equalsIgnoreCase(text());
                case "actor_health" -> compare(entity instanceof LivingEntity living ? living.getHealth() : 0);
                case "light_level" -> compare(entity.level().getMaxLocalRawBrightness(entity.blockPosition()));
                case "hourly_clock_time" -> compare(Platform.dayTime(entity.level()) % 24000L);
                case "weather_at_position", "weather", "is_weather" -> weather(entity);
                case "distance_to_nearest_player" -> compare(distanceToNearestPlayer(entity));
                case "has_mob_effect" -> effect(entity);
                case "has_equipment" -> equipment(entity);
                case "bool_property" -> property(entity, context) instanceof Boolean held
                    ? held == (value == null || value.getAsBoolean()) : false;
                case "int_property", "float_property" -> property(entity, context) instanceof Number number
                    && compare(number.doubleValue());
                case "enum_property" -> String.valueOf(property(entity, context)).equals(text());
                case "has_property" -> property(entity, context) != null;
                case "has_component" -> component(entity);

                case "on_fire" -> flag(entity.isOnFire());
                // Java tracks burning but not whether the burn costs health, so the mobs fire
                // cannot hurt are taken out here instead.
                case "taking_fire_damage" -> flag(entity.isOnFire() && !entity.fireImmune());
                case "on_hot_block" -> flag(HOT_BLOCKS.contains(
                    BuiltInRegistries.BLOCK.getKey(entity.getBlockStateOn().getBlock()).getPath()));
                // A Bedrock biome's humidity is its rainfall, which Java states as whether the biome
                // has any precipitation at all.
                case "is_humid" -> flag(entity.level()
                    .getBiome(entity.blockPosition()).value().hasPrecipitation());
                case "is_waterlogged" -> flag(waterlogged(entity));
                case "is_block" -> AddonBlocks.identifierOf(entity.getBlockStateOn())
                    .equals(AddonBlocks.identifierOf(text()));
                case "surface_mob" -> flag(entity.level().canSeeSky(entity.blockPosition()));
                case "is_in_village" -> entity.level() instanceof net.minecraft.server.level.ServerLevel
                    server ? flag(server.isVillage(entity.blockPosition())) : null;

                case "in_nether" -> flag(entity.level().dimension() == Level.NETHER);
                case "in_overworld" -> flag(entity.level().dimension() == Level.OVERWORLD);
                // Java draws its clouds at y 192 and has no cloud block an entity can be inside.
                case "in_clouds" -> flag(entity.getY() >= 192 && entity.getY() < 196);
                case "is_sprinting" -> flag(entity.isSprinting());
                // Java has one reading for being on something climbable, which covers both names.
                case "on_ladder", "is_climbing" ->
                    flag(entity instanceof LivingEntity living && living.onClimbable());
                case "is_immobile" -> flag(entity instanceof Mob mob
                    && (mob.isNoAi() || mob.getHealth() <= 0));
                case "is_navigating" ->
                    flag(entity instanceof Mob mob && mob.getNavigation().isInProgress());
                case "is_persistent" -> flag(entity instanceof Mob mob && mob.isPersistenceRequired());
                case "is_raider" -> flag(entity instanceof Raider);
                // Fleeing and walking in line are both states Java writes nowhere, so the goal doing
                // them keeps the answer where a filter can read it back.
                case "is_avoiding_mobs" -> flag(kept(entity, context, "is_avoiding_mobs"));
                case "in_caravan" -> flag(kept(entity, context, "in_caravan"));
                // Java counts the same unattended time in ticks and despawns a mob on it.
                case "inactivity_timer" -> entity instanceof LivingEntity idle
                    ? reached(idle.getNoActionTime() / 20d) : null;
                case "is_bound_to_creaking_heart" -> creakingHeart(entity);
                case "has_nametag" -> flag(entity.hasCustomName());
                case "is_missing_health" -> flag(entity instanceof LivingEntity living
                    && living.getHealth() < living.getMaxHealth());
                case "has_container_open" -> flag(entity instanceof Player player
                    && player.containerMenu != player.inventoryMenu);
                // An add-on entity is a plain Java mob, so only a mob Java itself tames answers here.
                case "is_tamed" -> entity instanceof TamableAnimal animal ? flag(animal.isTame()) : null;
                case "is_sitting" ->
                    entity instanceof TamableAnimal animal ? flag(animal.isInSittingPose()) : null;

                case "rider_count" -> compare(entity.getPassengers().size());
                case "is_riding_self" -> flag(entity.getVehicle() != null
                    && entity.getVehicle() == context.self());
                case "is_in_same_vehicle" -> flag(entity.getVehicle() != null
                    && context.self() != null && entity.getVehicle() == context.self().getVehicle());
                case "is_vehicle_family" ->
                    entity.getVehicle() != null && family(entity.getVehicle(), text());
                case "is_controlling_passenger_family" -> entity.getControllingPassenger() != null
                    && family(entity.getControllingPassenger(), text());
                case "is_target" -> flag(context.self() instanceof Mob mob && mob.getTarget() == entity);
                case "was_last_hurt_by" -> flag(context.self() instanceof LivingEntity hurt
                    && hurt.getLastHurtByMob() == entity);
                case "is_leashed_to" ->
                    flag(entity instanceof Mob mob && mob.getLeashHolder() == context.self());
                case "is_owner" -> flag(ownerOf(context.self()) == entity);
                case "trusts" -> flag(trusts(entity, context));
                case "owner_distance" -> distanceTo(entity, ownerOf(entity));
                case "target_distance" -> distanceTo(context.self(), targetOf(context.self()));
                case "home_distance" -> homeDistance(entity);

                case "clock_time" -> compare(clockTime(entity.level()));
                case "moon_phase" -> compare(moonPhase(entity.level()));
                case "moon_intensity" -> compare(MOON_BRIGHTNESS[(int) moonPhase(entity.level())]);
                case "is_brightness" -> compare(brightness(entity));
                case "is_altitude" -> altitude(entity);
                case "y_rotation" -> compare(Mth.wrapDegrees(entity.getYRot()));
                case "random_chance" -> randomChance();
                case "redstone_strength_at_position" ->
                    compare(entity.level().getBestNeighborSignal(entity.blockPosition()));
                case "is_game_rule" -> gameRule(entity);

                case "is_biome" -> isBiome(entity, text());
                case "has_biome_tag" -> hasBiomeTag(biomeAt(entity), text());
                case "is_snow_covered" ->
                    flag(biomeAt(entity).value().shouldSnow(entity.level(), entity.blockPosition()));
                case "is_temperature_value" -> compare(biomeAt(entity).value().getBaseTemperature());
                case "is_temperature_type" -> temperatureType(entity);
                case "in_block" -> blockAt(entity);

                case "all_slots_empty" -> slotsEmpty(entity, true);
                case "any_slot_empty" -> slotsEmpty(entity, false);
                case "has_equipment_tag" -> equipmentTag(entity);
                case "has_damaged_equipment" -> equipment(entity)
                    && entity instanceof LivingEntity worn && worn.getItemBySlot(slot()).isDamaged();
                case "has_ranged_weapon" -> flag(rangedWeapon(entity));
                case "has_same_equipment_in_slot_as" -> flag(sameEquipment(entity, context.self()));
                // Bedrock's supply is a count per trade; Java says only whether a trade is spent.
                case "has_trade_supply" -> flag(entity instanceof Merchant merchant
                    && merchant.getOffers().stream().anyMatch(offer -> !offer.isOutOfStock()));
                case "has_silk_touch" -> flag(entity instanceof LivingEntity living
                    && Platform.enchantmentsOf(living.getMainHandItem()).containsKey("silk_touch"));
                case "has_item_with_component" -> carriesComponent(entity);
                case "actor_has_item_with_enchantment_in_slot" -> enchantedSlot(entity);
                case "has_ability" -> ability(entity);
                case "has_damage" -> damageCause(entity, context);

                case "is_variant" -> numberProperty(entity, context, "variant");
                case "is_mark_variant" -> numberProperty(entity, context, "mark_variant");
                case "is_skin_id" -> numberProperty(entity, context, "skin_id");
                case "is_color" -> colour(entity, context);
                default -> null;
            };
        }

        /**
         * `!=` on a Bedrock filter negates the whole test rather than comparing, because most tests
         * have no value to compare against.
         */
        private boolean negated() {
            return "!=".equals(operator) || "not".equals(operator) || "<>".equals(operator);
        }

        private boolean compare(double actual) {
            double expected = expected();
            return switch (operator == null ? "==" : operator) {
                case "<" -> actual < expected;
                case "<=", "=<" -> actual <= expected;
                case ">" -> actual > expected;
                case ">=", "=>" -> actual >= expected;
                // `!=` is handled by negation, so an equality test is what is left.
                default -> actual == expected;
            };
        }

        /**
         * A count running up to the value, where Bedrock reads plain equality as having got there.
         *
         * `compare` reads `==` as landing on the number exactly, which a timer counting past it
         * does for one tick and then never again.
         */
        private boolean reached(double actual) {
            boolean exact = operator == null || "==".equals(operator) || "=".equals(operator)
                || "equals".equals(operator);
            return exact ? actual >= expected() : compare(actual);
        }

        private double expected() {
            return value != null && value.isJsonPrimitive() ? value.getAsDouble() : 0;
        }

        /**
         * A test whose value is a flag asks whether the outcome equals it, so `"value": false` asks
         * for the opposite of the test rather than for nothing. A test with no value asks for true.
         */
        private boolean flag(boolean actual) {
            return actual == (value == null || !value.isJsonPrimitive() || value.getAsBoolean());
        }

        private String text() {
            return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
        }

        /** A flag a goal keeps on the entity for a behaviour Java runs without recording it. */
        private static boolean kept(Entity entity, Context context, String key) {
            return context.state() != null
                && Boolean.TRUE.equals(context.state().property(entity.getUUID(), key));
        }

        private Boolean creakingHeart(Entity entity) {
            Boolean bound = Platform.boundToCreakingHeart(entity);
            return bound == null ? null : flag(bound);
        }

        /**
         * Whether the entity being tested has come to accept the other one.
         *
         * `minecraft:trusting` is fed the same way taming is and stores whoever fed it, so trust and
         * ownership are the one fact under two names.
         */
        private static boolean trusts(Entity entity, Context context) {
            if (context.other() == null || context.state() == null) return false;

            Object stored = context.state().property(entity.getUUID(), "owner");
            return stored instanceof String held && held.equals(context.other().getStringUUID());
        }

        /** Whether any of the slots asked about holds an item a pack gave the named component. */
        private Boolean carriesComponent(Entity entity) {
            List<EquipmentSlot> wanted = slots(domain == null ? "hand" : domain);
            if (wanted.isEmpty()) return null;
            if (!(entity instanceof LivingEntity living)) return false;

            return flag(wanted.stream().anyMatch(slot -> AddonItems
                .componentsOf(living.getItemBySlot(slot).getItem()).contains(text())));
        }

        private Object property(Entity entity, Context context) {
            String property = domain == null ? text() : domain;
            return context.state() == null ? null : context.state().property(entity.getUUID(), property);
        }

        private static boolean family(Entity entity, String family) {
            return Families.of(entity).contains(family);
        }

        /** Whether the block the entity is standing in holds water as well as itself. */
        private static boolean waterlogged(Entity entity) {
            net.minecraft.world.level.block.state.BlockState state =
                entity.level().getBlockState(entity.blockPosition());
            return state.hasProperty(net.minecraft.world.level.block.state.properties
                .BlockStateProperties.WATERLOGGED)
                && state.getValue(net.minecraft.world.level.block.state.properties
                    .BlockStateProperties.WATERLOGGED);
        }

        private boolean weather(Entity entity) {
            return switch (text()) {
                case "rain" -> entity.level().isRaining();
                case "thunder" -> entity.level().isThundering();
                case "clear" -> !entity.level().isRaining();
                default -> false;
            };
        }

        private static double distanceToNearestPlayer(Entity entity) {
            Player nearest = entity.level().getNearestPlayer(entity, 128);
            return nearest == null ? Double.MAX_VALUE : Math.sqrt(nearest.distanceToSqr(entity));
        }

        private boolean effect(Entity entity) {
            Identifier id = Identifier.tryParse(text().contains(":") ? text() : "minecraft:" + text());
            return id != null && entity instanceof LivingEntity living && Platform.hasEffect(living, id);
        }

        private boolean equipment(Entity entity) {
            if (!(entity instanceof LivingEntity living)) return false;
            ItemStack held = living.getItemBySlot(slot());
            if (held.isEmpty()) return text().isEmpty();

            return AddonItems.identifierOf(held.getItem()).equals(AddonItems.identifierOf(text()));
        }

        private EquipmentSlot slot() {
            return slot(domain == null ? "any" : domain);
        }

        private static EquipmentSlot slot(String location) {
            return switch (location.toLowerCase(Locale.ROOT)) {
                case "head", "armor.head" -> EquipmentSlot.HEAD;
                case "chest", "torso", "armor.chest" -> EquipmentSlot.CHEST;
                case "legs", "leg", "armor.legs" -> EquipmentSlot.LEGS;
                case "feet", "armor.feet" -> EquipmentSlot.FEET;
                case "offhand" -> EquipmentSlot.OFFHAND;
                default -> EquipmentSlot.MAINHAND;
            };
        }

        /** Empty for the carried inventory, which an ordinary Java mob has nothing to stand for. */
        private static List<EquipmentSlot> slots(String location) {
            return switch (location.toLowerCase(Locale.ROOT)) {
                case "armor" -> ARMOUR;
                case "hand" -> HANDS;
                case "inventory" -> List.of();
                case "", "any" -> EVERY_SLOT;
                default -> List.of(slot(location));
            };
        }

        /** The equipment location rides in `value` for these two rather than in `domain`. */
        private Boolean slotsEmpty(Entity entity, boolean all) {
            List<EquipmentSlot> wanted = slots(text());
            if (wanted.isEmpty()) return null;
            if (!(entity instanceof LivingEntity living)) return false;

            return all
                ? wanted.stream().allMatch(slot -> living.getItemBySlot(slot).isEmpty())
                : wanted.stream().anyMatch(slot -> living.getItemBySlot(slot).isEmpty());
        }

        private boolean equipmentTag(Entity entity) {
            if (!(entity instanceof LivingEntity living)) return false;
            ItemStack held = living.getItemBySlot(slot());
            return BedrockItemTags.has(held, text());
        }

        private boolean sameEquipment(Entity entity, Entity caller) {
            if (!(entity instanceof LivingEntity worn) || !(caller instanceof LivingEntity other)) {
                return false;
            }
            return worn.getItemBySlot(slot()).getItem() == other.getItemBySlot(slot()).getItem();
        }

        private static boolean rangedWeapon(Entity entity) {
            return entity instanceof LivingEntity living
                && (living.getMainHandItem().getItem() instanceof ProjectileWeaponItem
                || living.getOffhandItem().getItem() instanceof ProjectileWeaponItem);
        }

        private Boolean enchantedSlot(Entity entity) {
            List<EquipmentSlot> wanted = slots(domain == null ? "any" : domain);
            if (wanted.isEmpty()) return null;
            if (!(entity instanceof LivingEntity living)) return false;

            return wanted.stream().anyMatch(
                slot -> Platform.enchantmentsOf(living.getItemBySlot(slot)).containsKey(text()));
        }

        /** The player abilities Java carries; the ones only Bedrock has stay unanswered. */
        private Boolean ability(Entity entity) {
            if (!(entity instanceof Player player)) return false;

            Abilities abilities = player.getAbilities();
            return switch (text()) {
                case "flying" -> abilities.flying;
                case "mayfly" -> abilities.mayfly;
                case "instabuild" -> abilities.instabuild;
                case "invulnerable" -> abilities.invulnerable;
                case "flySpeed" -> abilities.getFlyingSpeed() > 0;
                case "walkSpeed" -> abilities.getWalkingSpeed() > 0;
                default -> null;
            };
        }

        /**
         * Inside a damage sensor this asks about the damage on its way in. Anywhere else Java has
         * only the source of the last damage taken, so it answers for damage already dealt.
         *
         * `fatal` asks whether the damage would kill, which only the damage on its way in can say.
         */
        private Boolean damageCause(Entity entity, Context context) {
            if (text().equals("fatal")) {
                return context.damage() == null ? null
                    : entity instanceof LivingEntity living && context.amount() >= living.getHealth();
            }
            DamageCause cause = DamageCause.named(text());
            if (cause == null) return false;

            DamageSource source = context.damage() != null ? context.damage()
                : entity instanceof LivingEntity living ? living.getLastDamageSource() : null;
            return source != null && cause.matches(source);
        }

        /** Bedrock writes a rule's name in lower case, Java spells the same rule mixed. */
        private Boolean gameRule(Entity entity) {
            if (domain == null || entity.level().getServer() == null) return null;

            String rule = GAME_RULES.stream()
                .filter(known -> known.equalsIgnoreCase(domain)).findFirst().orElse(null);
            String state = rule == null ? null : Platform.gameRule(entity.level().getServer(), rule);
            return state == null ? null : flag(Boolean.parseBoolean(state));
        }

        /** Bedrock counts the hours from noon, Java counts them from dawn. */
        private static double clockTime(Level level) {
            return Math.floorMod(Platform.dayTime(level) - 6000L, 24000L) / 24000d;
        }

        private static long moonPhase(Level level) {
            return Platform.dayTime(level) / 24000L % 8L;
        }

        private static double brightness(Entity entity) {
            return entity.level().getMaxLocalRawBrightness(entity.blockPosition()) / 15d;
        }

        /** Bedrock measures altitude from the bottom of the world rather than from sea level. */
        private Boolean altitude(Entity entity) {
            if (!(entity.level() instanceof ServerLevel server)) return null;
            return compare(entity.getY() - Platform.heightRange(server)[0]);
        }

        /** The value names the range to roll in rather than something to compare against. */
        private boolean randomChance() {
            int range = value != null && value.isJsonPrimitive() ? value.getAsInt() : 0;
            return range > 0 && Math.random() * range < 1;
        }

        private static Entity targetOf(Entity entity) {
            return entity instanceof Mob mob ? mob.getTarget() : null;
        }

        private static Entity ownerOf(Entity entity) {
            if (!(entity instanceof TamableAnimal animal)) return null;

            UUID owner = Platform.ownerOf(animal);
            if (owner == null || !(entity.level() instanceof ServerLevel server)) return null;
            return Platform.entityByUuid(server, owner);
        }

        /** Bedrock reads a distance to something that is not there as false, not as far away. */
        private boolean distanceTo(Entity from, Entity to) {
            return from != null && to != null && compare(Math.sqrt(from.distanceToSqr(to)));
        }

        private boolean homeDistance(Entity entity) {
            BlockPos home = entity instanceof Mob mob ? Platform.homeOf(mob) : null;
            if (home == null) return false;

            return compare(Math.sqrt(
                entity.distanceToSqr(home.getX() + 0.5, home.getY() + 0.5, home.getZ() + 0.5)));
        }

        private static Holder<Biome> biomeAt(Entity entity) {
            return entity.level().getBiome(entity.blockPosition());
        }

        /** Java prefixes the tags Bedrock names bare, so both spellings are tried. */
        private static boolean hasBiomeTag(Holder<Biome> biome, String tag) {
            return biome.tags().anyMatch(named -> named.location().getPath().equals(tag)
                || named.location().getPath().equals("is_" + tag));
        }

        /** Java carries a Bedrock biome category as a tag where it has one, and in the name where not. */
        private static boolean isBiome(Entity entity, String category) {
            String word = BIOME_WORDS.getOrDefault(category, category);
            Holder<Biome> biome = biomeAt(entity);
            return hasBiomeTag(biome, word)
                || biome.unwrapKey().map(key -> Platform.idOf(key).getPath().contains(word))
                    .orElse(false);
        }

        /** Bedrock's four categories read off the biome temperature, which both editions scale alike. */
        private boolean temperatureType(Entity entity) {
            Holder<Biome> biome = biomeAt(entity);
            float temperature = biome.value().getBaseTemperature();
            return switch (text()) {
                case "ocean" -> hasBiomeTag(biome, "ocean");
                case "cold" -> temperature < 0.3f;
                case "mild" -> temperature >= 0.3f && temperature < 0.95f;
                case "warm" -> temperature >= 0.95f;
                default -> false;
            };
        }

        private boolean blockAt(Entity entity) {
            return AddonBlocks.identifierOf(entity.level().getBlockState(entity.blockPosition()))
                .equals(AddonBlocks.identifierOf(text()));
        }

        /** The number a pack's own component put on the entity, which the server holds for it. */
        private Boolean numberProperty(Entity entity, Context context, String propertyName) {
            if (context.state() == null) return null;

            Object stored = context.state().property(entity.getUUID(), propertyName);
            return compare(stored instanceof Number number ? number.doubleValue() : 0);
        }

        private Boolean colour(Entity entity, Context context) {
            if (context.state() == null) return null;

            Object stored = context.state().property(entity.getUUID(), "color");
            return PALETTE.indexOf(text()) == (stored instanceof Number number ? number.intValue() : 0);
        }

        /**
         * Whether the entity carries a component at this moment, in its own list or in a live group.
         *
         * Bedrock asks this of the entity rather than of its definition, and a pack writes nearly
         * every switchable component into a component group: of the hundred and eight uses in
         * Mojang's own behaviour pack, none names a component declared at the top level. Reading the
         * top level alone therefore answers no to every question a published pack asks.
         */
        private boolean component(Entity entity) {
            return entity instanceof at.ioexception.mcaddon.entity.AddonEntity addon
                && addon.declaresNow(text());
        }
    }

    /** A filter that is always true, which is what an empty or unreadable block becomes. */
    record Always() implements Filter {
        @Override
        public boolean matches(Context context) {
            return true;
        }
    }

    /**
     * The groups a filter object may hold, under every key Mojang writes them with. The upper case
     * keys come with the numbered subjects and operators of older entity files.
     */
    Map<String, Function<List<Filter>, Filter>> GROUPS = Map.of(
        "all_of", All::new, "all", All::new, "AND", All::new,
        "any_of", Any::new, "any", Any::new, "OR", Any::new,
        "none_of", None::new, "NOT", None::new);

    /** The subjects and operators older entity files write by number, in the order they number them. */
    List<String> NUMBERED_SUBJECTS = List.of("self", "other");
    List<String> NUMBERED_OPERATORS = List.of("==", "!=");

    static Filter parse(JsonElement json) {
        if (json == null || json.isJsonNull()) return new Always();
        if (json.isJsonArray()) return new All(parseAll(json));

        if (!json.isJsonObject()) return new Always();
        JsonObject body = json.getAsJsonObject();

        // An entry of a list such as `entity_types` holds its filter under `filters` beside fields of its own.
        if (body.has("filters") && !body.has("test")) return parse(body.get("filters"));

        List<Filter> groups = GROUPS.entrySet().stream()
            .filter(group -> body.has(group.getKey()))
            .map(group -> group.getValue().apply(parseAll(body.get(group.getKey()))))
            .toList();
        if (groups.size() == 1) return groups.get(0);
        if (!groups.isEmpty()) return new All(groups);
        if (!body.has("test")) return new Always();

        return new Test(
            body.get("test").getAsString(),
            word(body.get("subject"), NUMBERED_SUBJECTS, "self"),
            word(body.get("operator"), NUMBERED_OPERATORS, null),
            body.has("domain") ? body.get("domain").getAsString() : null,
            body.get("value"));
    }

    /**
     * An `entity_types` list, which admits an entity when any one of its entries does. No list
     * admits everybody.
     */
    static Filter entityTypes(JsonElement entries) {
        if (entries == null || !entries.isJsonArray() || entries.getAsJsonArray().isEmpty()) return parse(entries);

        List<Filter> parts = new ArrayList<>();
        entries.getAsJsonArray().forEach(entry -> parts.add(parse(entry)));
        return new Any(parts);
    }

    private static String word(JsonElement value, List<String> numbered, String absent) {
        if (value == null || !value.isJsonPrimitive()) return absent;
        if (!value.getAsJsonPrimitive().isNumber()) return value.getAsString();

        int number = value.getAsInt();
        return number >= 0 && number < numbered.size() ? numbered.get(number) : value.getAsString();
    }

    private static List<Filter> parseAll(JsonElement json) {
        List<Filter> parts = new ArrayList<>();
        if (json.isJsonArray()) {
            json.getAsJsonArray().forEach(part -> parts.add(parse(part)));
        } else {
            parts.add(parse(json));
        }
        return parts;
    }

    /** Convenience for the common case of testing one entity against itself on a server. */
    static boolean matches(Filter filter, Entity entity, ServerLevel level, AddonState state) {
        return filter.matches(new Context(entity, entity, null, state));
    }
}
