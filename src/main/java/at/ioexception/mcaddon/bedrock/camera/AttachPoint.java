package at.ioexception.mcaddon.bedrock.camera;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;

/**
 * The places on an entity Bedrock's `EntityAttachPoint` names, which a camera hangs from.
 *
 * Java keeps an entity's feet, its eyes and its box and no other point on it, so each place is the
 * nearest of those three: the feet, the eyes for everything on the head, and the middle of the box
 * for everything on the body.
 */
public enum AttachPoint {
    BODY, BREATHING_POINT, DROP_ATTACH_POINT, EXPLOSION_POINT, EYES, FEET, HEAD, MOUTH, WEAPON_ATTACH_POINT;

    /** Null for a name Bedrock has not got. Scripts write `WeaponAttachPoint`, which is read as written. */
    public static AttachPoint tryFrom(String written) {
        String name = written.replaceAll("([a-z])([A-Z])", "$1_$2").toUpperCase(Locale.ROOT);
        for (AttachPoint point : values()) {
            if (point.name().equals(name)) return point;
        }
        return null;
    }

    public Vec3 on(Entity entity, float partialTick) {
        Vec3 feet = entity.getPosition(partialTick);
        return switch (this) {
            case FEET -> feet;
            case EYES, HEAD, MOUTH, BREATHING_POINT -> entity.getEyePosition(partialTick);
            case BODY, DROP_ATTACH_POINT, EXPLOSION_POINT, WEAPON_ATTACH_POINT ->
                feet.add(0, entity.getBbHeight() / 2, 0);
        };
    }
}
