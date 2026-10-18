package at.ioexception.mcaddon.gametest;

import at.ioexception.mcaddon.entity.AddonEntity;
import at.ioexception.mcaddon.mixin.MappedRegistryAccessor;
import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.registry.RegistryEdits;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.fabric.api.particle.v1.FabricParticleTypes;
import net.minecraft.core.Registry;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;

import java.util.List;

/**
 * Entries added to the built-in registries after they froze, which is what a client does when it
 * joins a server carrying an add-on it was never started with.
 *
 * A game test server has frozen its registries long before any test runs, so what is added here
 * goes through the same doors a join does. The entries are the test's own, under a namespace no
 * pack uses, and they stay registered for the rest of the run, because nothing is ever removed.
 */
public final class RegistryGameTests {
    private static final String NAMESPACE = "mcaddon_test";

    /**
     * A block with three states, its item, an entity type and its spawn egg, a sound event and a
     * particle type, all added at once after the freeze.
     *
     * Each lands one past the highest raw id its registry held, so no id a client or a save holds
     * moves; the block's states get state ids, its item answers for it, and every registry is frozen
     * again with nothing left half built.
     */
    @GameTest()
    public void entriesAddedAfterTheFreezeLandAtTheEnd(GameTestHelper helper) {
        helper.assertTrue(((MappedRegistryAccessor) BuiltInRegistries.BLOCK).mcaddon$frozen(),
            "The registries were open when the test ran, so it would prove nothing.");
        //? if >=26 {
        // Asked once first, so a component lookup still caching its earlier answer misses the egg.
        net.minecraft.world.item.SpawnEggItem.byId(BuiltInRegistries.ENTITY_TYPE.byId(0));
        //?}

        int blocks = BuiltInRegistries.BLOCK.size();
        int items = BuiltInRegistries.ITEM.size();
        int types = BuiltInRegistries.ENTITY_TYPE.size();
        int sounds = BuiltInRegistries.SOUND_EVENT.size();
        int particles = BuiltInRegistries.PARTICLE_TYPE.size();
        int states = Block.BLOCK_STATE_REGISTRY.size();

        Identifier blockId = Platform.id(NAMESPACE, "staged");
        Identifier typeId = Platform.id(NAMESPACE, "walker");
        Identifier eggId = Platform.id(NAMESPACE, "walker_spawn_egg");
        Identifier soundId = Platform.id(NAMESPACE, "chime");
        Identifier particleId = Platform.id(NAMESPACE, "spark");
        Added added = new Added();
        RegistryEdits.adding(() -> {
            added.block = Registry.register(BuiltInRegistries.BLOCK, blockId, Platform.buildStatefulBlock(
                BlockBehaviour.Properties.of(), blockId, List.of(IntegerProperty.create("stage", 0, 2))));
            added.item = Registry.register(BuiltInRegistries.ITEM, blockId,
                Platform.buildBlockItem(added.block, new Item.Properties(), blockId));
            added.type = Registry.register(BuiltInRegistries.ENTITY_TYPE, typeId,
                Platform.buildEntityType(EntityType.Builder.of(AddonEntity::new, MobCategory.CREATURE), typeId));
            added.egg = Registry.register(BuiltInRegistries.ITEM, eggId,
                Platform.spawnEgg(added.type, eggId, 0, 0));
            added.sound = Registry.register(BuiltInRegistries.SOUND_EVENT, soundId,
                SoundEvent.createVariableRangeEvent(soundId));
            added.particle = Registry.register(BuiltInRegistries.PARTICLE_TYPE, particleId,
                FabricParticleTypes.simple());
        });

        helper.assertTrue(BuiltInRegistries.BLOCK.getId(added.block) == blocks,
            "The block took raw id " + BuiltInRegistries.BLOCK.getId(added.block) + " instead of " + blocks);
        helper.assertTrue(BuiltInRegistries.ITEM.getId(added.item) == items
            && BuiltInRegistries.ITEM.getId(added.egg) == items + 1, "The items did not land after the last item.");
        helper.assertTrue(BuiltInRegistries.ENTITY_TYPE.getId(added.type) == types,
            "The entity type did not land after the last entity type.");
        helper.assertTrue(BuiltInRegistries.SOUND_EVENT.getId(added.sound) == sounds,
            "The sound event did not land after the last sound event.");
        helper.assertTrue(BuiltInRegistries.PARTICLE_TYPE.getId(added.particle) == particles,
            "The particle type did not land after the last particle type.");

        List<BlockState> staged = added.block.getStateDefinition().getPossibleStates();
        helper.assertTrue(staged.size() == 3, "The block has " + staged.size() + " states instead of 3.");
        helper.assertTrue(Block.BLOCK_STATE_REGISTRY.size() == states + 3
            && staged.stream().allMatch(state -> Block.BLOCK_STATE_REGISTRY.getId(state) >= states),
            "The block's states were not given state ids after every other state.");
        helper.assertTrue(Item.BY_BLOCK.get(added.block) == added.item, "The block's item does not answer for it.");

        for (Registry<?> registry : List.of(BuiltInRegistries.BLOCK, BuiltInRegistries.ITEM,
            BuiltInRegistries.ENTITY_TYPE, BuiltInRegistries.SOUND_EVENT, BuiltInRegistries.PARTICLE_TYPE)) {
            MappedRegistryAccessor fields = (MappedRegistryAccessor) registry;
            helper.assertTrue(fields.mcaddon$frozen(), registry.key() + " was left open.");
            helper.assertTrue(fields.mcaddon$unregisteredHolders() == null,
                registry.key() + " still hands out holders.");
        }

        //? if >=26 {
        // A holder added after the freeze would answer every tag question with an exception until
        // the next tag step; closing the registry binds it to the tags the registry has.
        helper.assertFalse(added.block.builtInRegistryHolder().is(net.minecraft.tags.BlockTags.LOGS),
            "The added block belongs to a tag it was never put in.");
        // The client's registry data step binds every item's components; this is the same call.
        BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(helper.getLevel().registryAccess())
            .forEach(net.minecraft.core.component.DataComponentInitializers.PendingComponents::apply);
        helper.assertTrue(net.minecraft.world.item.SpawnEggItem.byId(added.type)
            .filter(egg -> egg.value() == added.egg).isPresent(), "The new spawn egg is not found by its type.");
        //?} else {
        /*helper.assertTrue(net.minecraft.world.item.SpawnEggItem.byId(added.type) == added.egg,
            "The new spawn egg is not found by its type.");
        *///?}
        helper.succeed();
    }

    /**
     * A registration that fails, or that builds something it never registers, still leaves every
     * registry frozen, and a value left half built is named.
     */
    @GameTest()
    public void aFailedAdditionFreezesTheRegistriesAgain(GameTestHelper helper) {
        Identifier stray = Platform.id(NAMESPACE, "stray");
        String refused = null;
        try {
            RegistryEdits.adding(() -> Platform.buildEntityType(
                EntityType.Builder.of(AddonEntity::new, MobCategory.CREATURE), stray));
        } catch (IllegalStateException error) {
            refused = error.getMessage();
        }
        helper.assertTrue(refused != null && refused.contains("entity_type"),
            "An entity type built and never registered was not named: " + refused);
        helper.assertTrue(((MappedRegistryAccessor) BuiltInRegistries.ENTITY_TYPE).mcaddon$frozen(),
            "The entity type registry was left open.");

        boolean rethrown = false;
        try {
            RegistryEdits.adding(() -> {
                throw new IllegalArgumentException("a pack that cannot be read");
            });
        } catch (IllegalArgumentException error) {
            rethrown = true;
        }
        helper.assertTrue(rethrown, "The registration's own failure was swallowed.");
        helper.assertTrue(((MappedRegistryAccessor) BuiltInRegistries.BLOCK).mcaddon$frozen(),
            "A failed registration left a registry open.");
        helper.succeed();
    }

    /** What the registration built, read back once the registries are frozen again. */
    private static final class Added {
        private Block block;
        private Item item;
        private EntityType<AddonEntity> type;
        private Item egg;
        private SoundEvent sound;
        private SimpleParticleType particle;
    }
}
