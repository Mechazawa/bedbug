package at.ioexception.mcaddon.server;

import at.ioexception.mcaddon.bedrock.behaviour.DamageCause;
import at.ioexception.mcaddon.script.ScriptHost;
import net.minecraft.world.damagesource.DamageSource;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What hurt something, told the way a pack's script asks about it.
 *
 * Bedrock hands a script an object naming the cause, whatever dealt the damage and whatever was
 * thrown. Java says the same things through a damage source.
 */
public final class DamageSources {
    private DamageSources() {
    }

    public static Map<String, Object> describe(DamageSource source) {
        Map<String, Object> described = new LinkedHashMap<>();
        described.put("cause", DamageCause.of(source).scriptName());
        if (source.getEntity() != null) {
            described.put("damagingEntity", ScriptHost.EntityHandle.of(source.getEntity()));
        }
        if (source.getDirectEntity() != null && source.getDirectEntity() != source.getEntity()) {
            described.put("damagingProjectile",
                ScriptHost.EntityHandle.of(source.getDirectEntity()));
        }
        return described;
    }
}
