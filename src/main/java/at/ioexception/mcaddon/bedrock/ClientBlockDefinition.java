package at.ioexception.mcaddon.bedrock;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.pack.BedrockPack;
import at.ioexception.mcaddon.registry.AddonEntities;
import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One block's entry in the resource pack's `blocks.json`: the faces it wears and the sound it makes.
 *
 * A behaviour pack states a block's textures on the block itself through
 * `minecraft:material_instances`, and every pack written before that component existed states them
 * here instead. Both name a key in `terrain_texture.json` rather than a path, so a block found here
 * resolves exactly as one found on the behaviour side and is used where the behaviour side names
 * nothing.
 */
public record ClientBlockDefinition(Map<String, String> faceTextures, String soundGroup) {
    /** What an entry can say that nothing here reads, and what each of them decides in Bedrock. */
    private static final Map<String, String> NOT_READ = Map.of(
        "isotropic", "a face's texture turned at random per block",
        "carried_textures", "a second set of faces for the block held in a hand",
        "ambient_occlusion_exponent", "how sharply the engine darkens an inside corner",
        "sounds", "a sound event named on the block rather than through its group");

    /** Bedrock's `side` covers the four faces around a block, which is what `sides` means here. */
    private static final String ALL_SIDES = "sides";

    /**
     * Every block the file describes, by the identifier it is keyed under.
     *
     * A pack keys its own blocks by their full identifier and the vanilla file keys each block by
     * its bare name, so the key is taken as written and matched against a block's identifier with
     * the namespace filled in by the caller.
     */
    public static Map<String, ClientBlockDefinition> readAll(BedrockPack pack) {
        Map<String, ClientBlockDefinition> found = new LinkedHashMap<>();
        Path file = pack.file("blocks.json");
        if (!Files.isRegularFile(file)) return found;

        try {
            for (Map.Entry<String, JsonElement> entry : Json.parseObject(file).entrySet()) {
                if (!entry.getValue().isJsonObject() || "format_version".equals(entry.getKey())) continue;
                found.put(entry.getKey(), read(entry.getValue().getAsJsonObject()));
            }
        } catch (IOException | RuntimeException error) {
            McAddon.LOGGER.warn("Could not read blocks.json: {}", error.toString());
        }
        return found;
    }

    /**
     * The entry for one block, under either spelling of its name.
     *
     * A pack keys a block of its own by the identifier its behaviour half declares, namespace and
     * all, and one overriding a vanilla block keys it by the bare name Bedrock uses there.
     */
    public static ClientBlockDefinition of(Map<String, ClientBlockDefinition> blocks, String identifier) {
        ClientBlockDefinition named = blocks.get(identifier);
        if (named != null) return named;

        int colon = identifier.indexOf(':');
        return colon < 0 ? null : blocks.get(identifier.substring(colon + 1));
    }

    private static ClientBlockDefinition read(JsonObject body) {
        NOT_READ.forEach((field, describes) -> {
            if (body.has(field)) AddonEntities.unsupported("blocks.json " + field + ", " + describes);
        });

        Map<String, String> faces = new LinkedHashMap<>();
        JsonElement textures = body.get("textures");
        if (textures != null && textures.isJsonPrimitive()) {
            faces.put("*", textures.getAsString());
        } else if (textures != null && textures.isJsonObject()) {
            for (Map.Entry<String, JsonElement> face : textures.getAsJsonObject().entrySet()) {
                if (!face.getValue().isJsonPrimitive()) continue;
                faces.put("side".equals(face.getKey()) ? ALL_SIDES : face.getKey(),
                    face.getValue().getAsString());
            }
        }
        return new ClientBlockDefinition(faces, Json.text(body, "sound", null));
    }
}
