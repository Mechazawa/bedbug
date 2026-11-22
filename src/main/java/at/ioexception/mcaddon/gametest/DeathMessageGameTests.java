package at.ioexception.mcaddon.gametest;

import at.ioexception.mcaddon.entity.AddonEntity;
import at.ioexception.mcaddon.platform.Platform;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * `tp:scorcher` hurts pigs beside it through a `minecraft:area_attack` naming its own death
 * message, which is what the pig's combat tracker words its death with.
 */
public final class DeathMessageGameTests {
    private static final BlockPos STAND = new BlockPos(1, 0, 1);

    private static final float PIG_HEALTH = 40f;

    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 100)
    public void anAreaAttackWordsTheDeathWithItsOwnMessage(GameTestHelper helper) {
        AddonEntity scorcher = BehaviourGameTests.spawn(helper, "tp:scorcher", STAND);
        if (scorcher == null) {
            helper.succeed();
            return;
        }
        scorcher.setNoAi(false);

        Mob pig = (Mob) Platform.createMob(
            BuiltInRegistries.ENTITY_TYPE.getOptional(Platform.id("minecraft", "pig")).orElseThrow(),
            helper.getLevel());
        BlockPos at = helper.absolutePos(STAND.east(1));
        Platform.placeAt(pig, at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0f, 0f);
        pig.setNoAi(true);
        pig.getAttribute(Attributes.MAX_HEALTH).setBaseValue(PIG_HEALTH);
        pig.setHealth(PIG_HEALTH);
        pig.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1);
        helper.getLevel().addFreshEntity(pig);

        helper.succeedWhen(() -> {
            helper.assertTrue(pig.getHealth() < PIG_HEALTH, "tp:scorcher never hurt the pig beside it.");
            Component message = pig.getCombatTracker().getDeathMessage();
            String key = message.getContents() instanceof TranslatableContents words ? words.getKey() : null;
            helper.assertTrue("tp.death.scorched".equals(key),
                "The pig's death would read " + key + " where the area attack names tp.death.scorched.");
        });
    }
}
