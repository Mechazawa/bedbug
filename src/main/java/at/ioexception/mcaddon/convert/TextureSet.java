package at.ioexception.mcaddon.convert;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.pack.BedrockPack;
import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonObject;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * One `*.texture_set.json`, which is how a Bedrock pack describes a physically based surface.
 *
 * A set names its layers by bare file name, relative to the directory the set itself is in, so every
 * name here is already resolved to a pack-relative path without its extension.
 *
 * @param combined metalness, emissive, roughness and subsurface packed into one image's four channels
 */
public record TextureSet(String colour, String normal, String heightmap, String combined) {
    /** Null for a file that is not a texture set, which is what the caller skips. */
    public static TextureSet tryFrom(BedrockPack pack, Path file) {
        try {
            JsonObject body = Json.parseObject(file).getAsJsonObject("minecraft:texture_set");
            if (body == null) return null;

            String relative = pack.root().relativize(file).toString().replace('\\', '/');
            String directory = relative.substring(0, relative.lastIndexOf('/') + 1);

            String combined = Json.text(body, "metalness_emissive_roughness", null);
            if (combined == null) combined = Json.text(body, "metalness_emissive_roughness_subsurface", null);

            return new TextureSet(
                resolve(directory, Json.text(body, "color", null)),
                resolve(directory, Json.text(body, "normal", null)),
                resolve(directory, Json.text(body, "heightmap", null)),
                resolve(directory, combined));
        } catch (IOException | RuntimeException error) {
            McAddon.LOGGER.warn("Could not read {}.", file.getFileName());
            return null;
        }
    }

    private static String resolve(String directory, String name) {
        return name == null ? null : directory + name;
    }

    /** The layers that are not the colour one, which no Java texture should be written for. */
    public Set<String> auxiliary() {
        Set<String> auxiliary = new LinkedHashSet<>();
        for (String layer : new String[] {normal, heightmap, combined}) {
            if (layer != null) auxiliary.add(layer);
        }
        return auxiliary;
    }

    /**
     * The glowing pixels of the colour texture, on their own and with everything else transparent.
     *
     * This is the one part of a physically based set vanilla Java can show without a shader pack: a
     * copy drawn again at full brightness. Null when the set has no emissive pixels to speak of.
     */
    public BufferedImage emissiveLayer(BedrockPack pack, int threshold) {
        BufferedImage base = read(pack, colour);
        BufferedImage map = read(pack, combined);
        if (base == null || map == null) return null;

        BufferedImage glow = new BufferedImage(base.getWidth(), base.getHeight(),
            BufferedImage.TYPE_INT_ARGB);
        boolean any = false;

        for (int y = 0; y < base.getHeight(); y++) {
            for (int x = 0; x < base.getWidth(); x++) {
                if (channel(map, base, x, y, 8) < threshold) continue;
                glow.setRGB(x, y, base.getRGB(x, y));
                any = true;
            }
        }
        return any ? glow : null;
    }

    /**
     * The same surface written the way Iris and OptiFine shader packs read one.
     *
     * Those loaders take physically based data from two images named after the colour texture rather
     * than from any API, so a pack converted into that layout lights correctly under any labPBR
     * shader pack with nothing else installed. Nothing is lost that Bedrock stored: `_n` carries the
     * normal, the ambient occlusion and the height, and `_s` carries smoothness, metalness,
     * subsurface scattering and emission.
     */
    public List<Sidecar> shaderSidecars(BedrockPack pack) {
        BufferedImage base = read(pack, colour);
        if (base == null) return List.of();

        List<Sidecar> sidecars = new ArrayList<>();
        BufferedImage normalMap = read(pack, normal);
        BufferedImage height = read(pack, heightmap);
        if (normalMap != null || height != null) {
            sidecars.add(new Sidecar("_n", normals(base, normalMap, height)));
        }

        BufferedImage map = read(pack, combined);
        if (map != null) sidecars.add(new Sidecar("_s", specular(base, map)));
        return sidecars;
    }

    /** One shader-pack image and the suffix its name has to carry for a loader to find it. */
    public record Sidecar(String suffix, BufferedImage image) {
    }

    /** `_n`: the normal in red and green, ambient occlusion in blue, and the height in alpha. */
    private static BufferedImage normals(BufferedImage base, BufferedImage normalMap, BufferedImage height) {
        BufferedImage result = new BufferedImage(base.getWidth(), base.getHeight(),
            BufferedImage.TYPE_INT_ARGB);

        for (int y = 0; y < base.getHeight(); y++) {
            for (int x = 0; x < base.getWidth(); x++) {
                int red = normalMap == null ? 127 : channel(normalMap, base, x, y, 16);
                int green = normalMap == null ? 127 : channel(normalMap, base, x, y, 8);
                int alpha = height == null ? 255 : channel(height, base, x, y, 16);
                result.setRGB(x, y, alpha << 24 | red << 16 | green << 8 | 255);
            }
        }
        return result;
    }

    /**
     * `_s`: perceptual smoothness, the reflectance of the surface, subsurface scattering, emission.
     *
     * labPBR reads green above 229 as a metal and below it as a dielectric's reflectance, and reads
     * an alpha of 255 as "not emissive" rather than as the brightest possible, so neither channel is
     * a straight copy of what Bedrock stores.
     */
    private static BufferedImage specular(BufferedImage base, BufferedImage map) {
        BufferedImage result = new BufferedImage(base.getWidth(), base.getHeight(),
            BufferedImage.TYPE_INT_ARGB);

        for (int y = 0; y < base.getHeight(); y++) {
            for (int x = 0; x < base.getWidth(); x++) {
                int metalness = channel(map, base, x, y, 16);
                int emissive = channel(map, base, x, y, 8);
                int roughness = channel(map, base, x, y, 0);
                int subsurface = map.getColorModel().hasAlpha() ? channel(map, base, x, y, 24) : 0;

                int smoothness = (int) Math.round(255 * (1 - Math.sqrt(roughness / 255.0)));
                int reflectance = metalness >= 128 ? 255 : Math.max(10, metalness * 229 / 255);
                int scattering = subsurface == 0 ? 0 : 65 + subsurface * 190 / 255;
                int emission = emissive == 0 ? 255 : Math.min(254, emissive);
                result.setRGB(x, y,
                    emission << 24 | smoothness << 16 | reflectance << 8 | scattering);
            }
        }
        return result;
    }

    /**
     * One channel of a map, sampled where the colour texture's pixel sits.
     *
     * A pack is free to ship its maps at a different resolution from the colour layer, so the
     * coordinate is scaled rather than used directly.
     */
    private static int channel(BufferedImage map, BufferedImage base, int x, int y, int shift) {
        int argb = map.getRGB(x * map.getWidth() / base.getWidth(),
            y * map.getHeight() / base.getHeight());
        return (argb >> shift) & 0xFF;
    }

    private static BufferedImage read(BedrockPack pack, String path) {
        if (path == null) return null;

        Path png = pack.file(path + ".png");
        Path targa = pack.file(path + ".tga");

        try {
            if (Files.isRegularFile(png)) return ImageIO.read(png.toFile());
            if (Files.isRegularFile(targa)) return TgaReader.read(Files.readAllBytes(targa));
            return null;
        } catch (IOException | RuntimeException error) {
            McAddon.LOGGER.warn("Could not read the texture {}.", path);
            return null;
        }
    }

    /** Every set a pack ships, so a caller can walk them once and use each for several things. */
    public static List<TextureSet> readAll(BedrockPack pack) {
        List<TextureSet> sets = new ArrayList<>();
        for (Path file : pack.list("textures", ".texture_set.json")) {
            TextureSet set = tryFrom(pack, file);
            if (set != null && set.colour() != null) sets.add(set);
        }
        return sets;
    }
}
