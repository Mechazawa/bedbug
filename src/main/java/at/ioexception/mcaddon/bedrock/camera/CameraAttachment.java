package at.ioexception.mcaddon.bedrock.camera;

import com.google.gson.JsonObject;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * The entity `/camera attach_to_entity` and `Camera.attachToEntity` hang a player's camera off, by
 * the id the client knows it by, and the place on it a script named.
 *
 * An orbit camera circles the entity in place of the player, about the middle of its box unless a
 * place was named. Any other camera looks out of the entity's eyes, or from the place named. An
 * attachment with no entity is `detach_from_entity`.
 */
public record CameraAttachment(Integer entity, AttachPoint locator) implements CameraInstruction {
    static final String KIND = "attach";

    public static final CameraAttachment DETACHED = new CameraAttachment(null, null);

    public boolean detached() {
        return entity == null;
    }

    /** The point an orbit camera circles. */
    public Vec3 pivotOn(Entity attached, float partialTick) {
        return (locator == null ? AttachPoint.BODY : locator).on(attached, partialTick);
    }

    /** The point any other camera looks out from, turned the way the entity looks. */
    public CameraPlacement viewFrom(Entity attached, float partialTick) {
        return new CameraPlacement((locator == null ? AttachPoint.EYES : locator).on(attached, partialTick),
            attached.getViewXRot(partialTick), attached.getViewYRot(partialTick), true);
    }

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public JsonObject json() {
        JsonObject json = new JsonObject();
        if (entity != null) json.addProperty("entity", entity);
        if (locator != null) json.addProperty("locator", locator.name());
        return json;
    }

    static CameraAttachment read(JsonObject json) {
        return new CameraAttachment(json.has("entity") ? json.get("entity").getAsInt() : null,
            json.has("locator") ? AttachPoint.tryFrom(json.get("locator").getAsString()) : null);
    }
}
