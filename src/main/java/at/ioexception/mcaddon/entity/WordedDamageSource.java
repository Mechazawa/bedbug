package at.ioexception.mcaddon.entity;

import net.minecraft.network.chat.Component;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/**
 * A blow whose death message is a translation key the pack chose rather than its damage type's.
 *
 * Bedrock's death messages take the victim as the first argument and the killer as the second.
 */
public final class WordedDamageSource extends DamageSource {
    private final String deathMessage;

    private WordedDamageSource(DamageSource source, String deathMessage) {
        super(source.typeHolder(), source.getDirectEntity(), source.getEntity());
        this.deathMessage = deathMessage;
    }

    /** The source itself when the pack names no message. */
    public static DamageSource worded(DamageSource source, String deathMessage) {
        return deathMessage == null || deathMessage.isEmpty()
            ? source : new WordedDamageSource(source, deathMessage);
    }

    @Override
    public Component getLocalizedDeathMessage(LivingEntity victim) {
        Entity killer = getEntity();
        return killer == null
            ? Component.translatable(deathMessage, victim.getDisplayName())
            : Component.translatable(deathMessage, victim.getDisplayName(), killer.getDisplayName());
    }
}
