package at.ioexception.mcaddon.bedrock.behaviour;

import at.ioexception.mcaddon.platform.Platform;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.damagesource.DamageSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DamageCauseTest {
    private static HolderLookup.Provider registries;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        //? if >=26.3 {
        /*registries = VanillaRegistries.createWorldLookup();
        *///?} else {
        registries = VanillaRegistries.createLookup();
        //?}
    }

    /** `ActorDamageCause` in Mojang's entity schemas. */
    @ParameterizedTest
    @ValueSource(strings = {"none", "override", "contact", "entity_attack", "projectile", "suffocation",
        "fall", "fire", "fire_tick", "lava", "drowning", "block_explosion", "entity_explosion", "void",
        "self_destruct", "magic", "wither", "starve", "anvil", "thorns", "falling_block", "piston",
        "fly_into_wall", "magma", "fireworks", "lightning", "charging", "temperature", "freezing",
        "stalactite", "stalagmite", "ram_attack", "sonic_boom", "campfire", "soul_campfire", "mace_smash",
        "all"})
    void everyCauseAnEntityFileWritesIsKnownInBothSpellings(String name) {
        DamageCause cause = DamageCause.named(name);
        assertEquals(name, cause.entityName());
        assertEquals(cause, DamageCause.named(cause.scriptName()));
    }

    @Test
    void aScriptSpellsACauseInCamelCase() {
        assertEquals("fireTick", DamageCause.FIRE_TICK.scriptName());
        assertEquals("entityAttack", DamageCause.named("entityAttack").scriptName());
        assertEquals(DamageCause.VOID, DamageCause.named("void"));
    }

    /** A damage type Java adds names no Bedrock cause until it is placed here. */
    @Test
    void everyJavaDamageTypeIsABedrockCause() {
        List<String> nameless = registries.lookupOrThrow(Registries.DAMAGE_TYPE).listElements()
            .filter(type -> DamageCause.of(new DamageSource(type)) == DamageCause.NONE)
            .map(type -> Platform.idOf(type.key()).getPath())
            .sorted().toList();
        assertEquals(List.of("generic", "outside_border"), nameless);
    }

    @ParameterizedTest
    @EnumSource(value = DamageCause.class, mode = EnumSource.Mode.EXCLUDE, names = "ENTITY_EXPLOSION")
    void everyCauseHearsTheDamageItDeals(DamageCause cause) {
        DamageSource dealt = cause.source(registries, null);
        assertTrue(cause.matches(dealt), cause + " dealt " + dealt.typeHolder());
        assertEquals(cause.damageType(),
            Platform.idOf(dealt.typeHolder().unwrapKey().orElseThrow()).toString());
    }

    @Test
    void aCauseHearsOnlyItsOwnDamage() {
        DamageSource fall = DamageCause.FALL.source(registries, null);
        assertFalse(DamageCause.FIRE.matches(fall));
        assertFalse(DamageCause.ENTITY_ATTACK.matches(fall));
        assertTrue(DamageCause.ALL.matches(fall));
        assertEquals(DamageCause.CONTACT, DamageCause.of(DamageCause.CONTACT.source(registries, null)));
    }

    /** Java burns a snow golem too warm for its biome with `on_fire`, which is Bedrock's `fire_tick`. */
    @Test
    void aCauseJavaHasNoTypeForHearsItsStandIn() {
        DamageSource burning = DamageCause.FIRE_TICK.source(registries, null);
        assertTrue(DamageCause.TEMPERATURE.matches(burning));
        assertEquals("minecraft:on_fire", DamageCause.TEMPERATURE.damageType());
    }

    /** A campfire is its own Java type from 1.21.2, and an ordinary fire before it. */
    @Test
    void aCampfireIsAFireOnlyWhereJavaCannotTellThemApart() {
        DamageSource fire = DamageCause.FIRE.source(registries, null);
        boolean ownType = !DamageCause.CAMPFIRE.damageType().equals("minecraft:in_fire");
        assertEquals(!ownType, DamageCause.CAMPFIRE.matches(fire));
    }

    @Test
    void anExplosionWithNothingBehindItIsABlockExplosion() {
        DamageSource blast = DamageCause.ENTITY_EXPLOSION.source(registries, null);
        assertEquals(DamageCause.BLOCK_EXPLOSION, DamageCause.of(blast));
    }
}
