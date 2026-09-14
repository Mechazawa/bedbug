package at.ioexception.mcaddon.convert;

import at.ioexception.mcaddon.pack.BedrockPack;
import at.ioexception.mcaddon.registry.AddonEntities;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One page of a pack's own glyphs, as a Java bitmap font provider.
 *
 * A Bedrock pack draws its glyphs into `font/glyph_<page>.png`, one image per high byte of a
 * codepoint holding a sixteen by sixteen grid, so `glyph_e1.png` carries U+E100 to U+E1FF.
 * `default8` and `default32` are that same grid for the page the Latin alphabet sits in, at the two
 * sizes the Bedrock client picks between by screen size.
 *
 * Java reads the identical grid through a `bitmap` provider naming the image and the characters each
 * row holds, and merges the providers every loaded pack declares for one font, so writing them into
 * `minecraft:default` puts a pack's glyphs in ordinary text without taking the game's own away. The
 * height is the eight pixels Java draws a line of text at, whatever resolution the page was painted
 * at.
 */
public record GlyphPage(Path source, String name, int page, int width) {

    /** How many glyphs a page holds on each axis, which fixes a cell at a sixteenth of the image. */
    private static final int GRID = 16;

    /** Java's own font is drawn eight pixels tall with the baseline one pixel above the bottom. */
    private static final int HEIGHT = 8;
    private static final int ASCENT = 7;

    /**
     * Every page the pack ships, one per codepoint page.
     *
     * A pack shipping `default8` beside `default32` has painted one page twice, and the larger image
     * is the one kept: Java scales whichever it is given to the height a line of text takes, so the
     * finer painting loses nothing and the coarser one loses detail.
     */
    public static List<GlyphPage> readAll(BedrockPack pack) {
        Map<Integer, GlyphPage> byPage = new LinkedHashMap<>();
        for (Path file : pack.list("font", ".png")) {
            String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
            Integer page = pageOf(name.substring(0, name.length() - ".png".length()));
            if (page == null) continue;

            GlyphPage candidate = new GlyphPage(file, name, page, widthOf(file));
            GlyphPage held = byPage.get(page);
            if (held == null || candidate.width > held.width) byPage.put(page, candidate);
        }
        return List.copyOf(byPage.values());
    }

    /**
     * Which codepoint page a file name stands for, or null for a name that names none.
     *
     * The surrogate pages are left out because no character lives there: half a surrogate pair is
     * not a codepoint, and a provider claiming those rows would name text that cannot exist.
     */
    private static Integer pageOf(String name) {
        if (name.startsWith("default")) return 0;
        if (!name.startsWith("glyph_")) return null;

        try {
            int page = Integer.parseInt(name.substring("glyph_".length()), 16);
            return page >= 0xD8 && page <= 0xDF ? null : page;
        } catch (NumberFormatException notAPage) {
            return null;
        }
    }

    private static int widthOf(Path file) {
        try {
            BufferedImage image = ImageIO.read(file.toFile());
            return image == null ? 0 : image.getWidth();
        } catch (IOException unreadable) {
            return 0;
        }
    }

    /** Where the image goes in the converted pack, under the textures Java resolves a font from. */
    public String texturePath() {
        return "font/" + name;
    }

    /**
     * The provider Java reads this page through, or null for an image whose grid does not divide.
     *
     * Every row is the sixteen characters that row of the image draws, written out as themselves,
     * which is the only form the game reads them in.
     */
    public JsonObject provider(String namespace) {
        if (width < GRID || width % GRID != 0) {
            AddonEntities.unsupported("resource pack font/" + name);
            return null;
        }

        JsonArray rows = new JsonArray();
        for (int row = 0; row < GRID; row++) {
            StringBuilder characters = new StringBuilder();
            for (int column = 0; column < GRID; column++) {
                characters.appendCodePoint(page * 0x100 + row * GRID + column);
            }
            rows.add(characters.toString());
        }

        JsonObject provider = new JsonObject();
        provider.addProperty("type", "bitmap");
        provider.addProperty("file", namespace + ":" + texturePath());
        provider.addProperty("height", HEIGHT);
        provider.addProperty("ascent", ASCENT);
        provider.add("chars", rows);
        return provider;
    }
}
