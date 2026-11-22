package at.ioexception.mcaddon.gametest;

import at.ioexception.mcaddon.entity.AddonEntity;
import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.registry.AddonEntities;
import at.ioexception.mcaddon.server.EntityEvents;
import at.ioexception.mcaddon.server.ServerHooks;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.sensing.GolemSensor;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.npc.CatSpawner;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.stream.IntStream;

/** The `dweller_role` of `tp:townsfolk` read back through what Java counts in a village. */
public final class DwellerRoleGameTests {
    private static final String TOWNSFOLK = "tp:townsfolk";

    /** A villager that sees a `defender` wants no iron golem, as one that sees a golem does. */
    @GameTest(structure = "fabric-gametest-api-v1:empty")
    public void aVillagerSeeingADefenderWantsNoGolem(GameTestHelper helper) {
        AddonEntity townsfolk = BehaviourGameTests.spawn(helper, TOWNSFOLK, new BlockPos(1, 0, 1));
        if (townsfolk == null) {
            helper.succeed();
            return;
        }
        LivingEntity villager = (LivingEntity) mob(helper, "minecraft:villager", new BlockPos(3, 0, 3));
        villager.getBrain().setMemory(MemoryModuleType.NEAREST_LIVING_ENTITIES, List.<LivingEntity>of(townsfolk));

        GolemSensor.checkForNearbyGolem(villager);
        helper.assertFalse(villager.getBrain().hasMemoryValue(MemoryModuleType.GOLEM_DETECTED_RECENTLY),
            TOWNSFOLK + " is no dweller and counted as a golem.");

        EntityEvents.trigger(townsfolk, "tp:townsfolk_defend", null, ServerHooks.state());
        GolemSensor.checkForNearbyGolem(villager);
        helper.assertTrue(villager.getBrain().hasMemoryValue(MemoryModuleType.GOLEM_DETECTED_RECENTLY),
            TOWNSFOLK + " is a defender and the villager beside it still wants a golem.");
        helper.succeed();
    }

    /**
     * Five `passive` dwellers fill a village's quota of cats, so Java's cat spawner adds none to a
     * village of five claimed beds. The same five as no dwellers leave room for one.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty")
    public void passiveDwellersFillAVillagesCats(GameTestHelper helper) {
        List<AddonEntity> townsfolk = IntStream.range(0, 5)
            .mapToObj(column -> BehaviourGameTests.spawn(helper, TOWNSFOLK, new BlockPos(4, 0, column)))
            .toList();
        if (townsfolk.contains(null)) {
            helper.succeed();
            return;
        }
        List<BlockPos> beds = IntStream.range(0, 5).mapToObj(row -> new BlockPos(0, 0, row)).toList();
        beds.forEach(head -> NapperGameTests.bed(helper, head));
        PoiManager manager = helper.getLevel().getPoiManager();
        beds.stream().map(helper::absolutePos).forEach(head ->
            manager.take(type -> type.is(PoiTypes.HOME), (type, at) -> at.equals(head), head, 0));
        BlockPos spawnAt = helper.absolutePos(new BlockPos(2, 0, 2));

        try {
            helper.assertTrue(spawnCat(helper, spawnAt) == 1, "Java's cat spawner made no cat for five claimed beds.");
            townsfolk.forEach(dweller ->
                EntityEvents.trigger(dweller, "tp:townsfolk_settle", null, ServerHooks.state()));
            helper.assertTrue(spawnCat(helper, spawnAt) == 0,
                "Five " + TOWNSFOLK + " are passive dwellers and the village took a cat beside them.");
        } finally {
            beds.stream().map(helper::absolutePos).forEach(manager::release);
        }
        helper.succeed();
    }

    /** Runs the cat spawner's village step at a place and counts the cats it made there, discarding them. */
    private static int spawnCat(GameTestHelper helper, BlockPos at) {
        ServerLevel level = helper.getLevel();
        try {
            java.lang.reflect.Method spawn = CatSpawner.class
                .getDeclaredMethod("spawnInVillage", ServerLevel.class, BlockPos.class);
            spawn.setAccessible(true);
            spawn.invoke(new CatSpawner(), level, at);
        } catch (ReflectiveOperationException unreachable) {
            helper.fail("the cat spawner could not be asked to spawn in a village: " + unreachable);
        }
        EntityType<?> cat = AddonEntities.typeOf("minecraft:cat");
        List<Entity> made = level.getEntitiesOfClass(Entity.class, new AABB(at).inflate(1),
            entity -> entity.getType() == cat);
        made.forEach(Entity::discard);
        return made.size();
    }

    private static Entity mob(GameTestHelper helper, String identifier, BlockPos where) {
        Entity mob = Platform.createMob(AddonEntities.typeOf(identifier), helper.getLevel());
        BlockPos at = helper.absolutePos(where);
        Platform.placeAt(mob, at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0f, 0f);
        ((net.minecraft.world.entity.Mob) mob).setNoAi(true);
        helper.getLevel().addFreshEntity(mob);
        return mob;
    }
}
