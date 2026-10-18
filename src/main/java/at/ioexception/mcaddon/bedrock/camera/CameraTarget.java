package at.ioexception.mcaddon.bedrock.camera;

import com.google.gson.JsonObject;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * The entity `/camera target_entity` and a script's `targetEntity` turn a free camera after, by the
 * id the client knows it by, and how far from the middle of its box the camera looks.
 *
 * A target with no entity is `remove_target`, which turns the camera back to its own rotation.
 */
public record CameraTarget(Integer entity, Vec3 offset) implements CameraInstruction {
    static final String KIND = "target";

    public static final CameraTarget REMOVED = new CameraTarget(null, Vec3.ZERO);

    public boolean removed() {
        return entity == null;
    }

    /** The point the camera looks at this frame. */
    public Vec3 pointOn(Entity target, float partialTick) {
        return AttachPoint.BODY.on(target, partialTick).add(offset);
    }

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public JsonObject json() {
        JsonObject json = new JsonObject();
        if (entity != null) json.addProperty("entity", entity);
        json.add("offset", Vectors.write(offset));
        return json;
    }

    static CameraTarget read(JsonObject json) {
        Vec3 offset = Vectors.read(json.get("offset"));
        return new CameraTarget(json.has("entity") ? json.get("entity").getAsInt() : null,
            offset == null ? Vec3.ZERO : offset);
    }
}
