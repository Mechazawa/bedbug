package at.ioexception.mcaddon.client.screen;

import at.ioexception.mcaddon.pack.BedrockPack;
import at.ioexception.mcaddon.pack.PackManifest;
import at.ioexception.mcaddon.pack.PackRole;
import at.ioexception.mcaddon.platform.Platform;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One add-on as a row of the add-on list: its icon, its name and version over one line of its
 * description, whether it is on, and room on the right for the settings and report buttons the
 * screen puts over it. A click on the row opens it to the full descriptions, the authors and both
 * packs, and another closes it.
 */
public final class AddonRow extends AbstractWidget {
    static final int HEADER = 40;
    static final int GAP = 4;
    static final int CONTROLS_TOP = (HEADER - IconButton.SIZE) / 2;
    private static final int PAD = 6;
    private static final int ICON = 32;
    private static final int LINE = 12;
    private static final int ELLIPSIS_MARGIN = 2;
    private static final int BACKGROUND = 0x70000000;
    private static final int OUTLINE = 0xFFFFFFFF;
    private static final int NAME = 0xFFFFFFFF;
    private static final int QUIET = 0xFFAAAAAA;
    private static final int ON = 0xFF55FF55;
    private static final Identifier UNKNOWN_ICON = Platform.id("minecraft", "textures/misc/unknown_pack.png");

    private final Font font;
    private final Identifier icon;
    private final boolean on;
    private final boolean expanded;
    private final Runnable toggle;
    private final String title;
    private final String summary;
    private final List<String> details;
    private final String state;
    private final int textRight;

    AddonRow(int left, int width, Font font, AddonEntry addon, Identifier icon, boolean expanded, Runnable toggle) {
        super(left, 0, width, HEADER, Component.empty());
        this.font = font;
        this.icon = icon;
        this.on = addon.active();
        this.expanded = expanded;
        this.toggle = toggle;
        this.title = addon.converted().displayName() + " " + addon.converted().pack().manifest().version();
        this.state = Component.translatable(on ? "mcaddon.addons.state.on" : "mcaddon.addons.state.off").getString();
        this.textRight = controlsLeft() - PAD - font.width(state) - PAD;
        this.summary = addon.descriptions().stream().findFirst()
            .map(description -> shortened(description.split("\\r?\\n", 2)[0], textRight - textLeft()))
            .orElse("");
        this.details = expanded ? details(addon, width - textLeft() - PAD) : List.of();
        setMessage(Component.literal(title));
        setHeight(HEADER + (details.isEmpty() ? 0 : details.size() * LINE + PAD));
    }

    public String title() {
        return title;
    }

    /** The one line of description shown under the name, cut short with an ellipsis when it is too wide. */
    public String summary() {
        return summary;
    }

    /** The lines shown when the row is open: empty while it is closed. */
    public List<String> details() {
        return details;
    }

    /** `On` or `Off`. */
    public String state() {
        return state;
    }

    public boolean expanded() {
        return expanded;
    }

    public Identifier icon() {
        return icon;
    }

    static Identifier unknownIcon() {
        return UNKNOWN_ICON;
    }

    /** Where the first of the two buttons the screen puts over the row begins, from the row's left. */
    int controlsLeft() {
        return getWidth() - PAD - 2 * IconButton.SIZE - 2;
    }

    private int textLeft() {
        return PAD + ICON + PAD;
    }

    private String shortened(String text, int width) {
        if (font.width(text) <= width) return text;
        String ellipsis = "...";
        return font.plainSubstrByWidth(text, width - font.width(ellipsis) - ELLIPSIS_MARGIN).stripTrailing()
            + ellipsis;
    }

    private List<String> details(AddonEntry addon, int width) {
        List<String> lines = new ArrayList<>();
        addon.descriptions().forEach(description -> lines.addAll(ColumnScreen.wrap(font, description, width)));
        if (!addon.authors().isEmpty()) {
            lines.add(Component.translatable("mcaddon.addons.by", String.join(", ", addon.authors())).getString());
        }
        addon.packs().forEach(pack -> {
            lines.add(packLine(pack, addon.active()));
            pack.manifest().modules().forEach(module -> lines.addAll(moduleLines(module, width - PAD)));
        });
        return lines;
    }

    private List<String> moduleLines(PackManifest.Module module, int width) {
        List<String> lines = new ArrayList<>();
        String type = module.type().name().toLowerCase(Locale.ROOT).replace('_', ' ');
        String version = module.version() == null ? "" : module.version().toString();
        lines.add("  " + Component.translatable("mcaddon.addons.module", type, version).getString().stripTrailing());
        if (module.description() != null && !module.description().isBlank()) {
            ColumnScreen.wrap(font, module.description(), width).forEach(line -> lines.add("    " + line));
        }
        return lines;
    }

    private static String packLine(BedrockPack pack, boolean active) {
        String kind = pack.role() == PackRole.RESOURCE
            ? "mcaddon.addons.pack.resources" : "mcaddon.addons.pack.behaviour";
        String state = active ? "mcaddon.addons.pack.active" : "mcaddon.addons.pack.inactive";
        return Component.translatable(state, Component.translatable(kind, pack.displayName())).getString();
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
        float partialTick) {
        int left = getX();
        int top = getY();
        Platform.paint(graphics, painter -> {
            painter.fill(left, top, left + width, top + height, BACKGROUND);
            if (isHoveredOrFocused()) {
                painter.fill(left, top, left + width, top + 1, OUTLINE);
                painter.fill(left, top + height - 1, left + width, top + height, OUTLINE);
                painter.fill(left, top, left + 1, top + height, OUTLINE);
                painter.fill(left + width - 1, top, left + width, top + height, OUTLINE);
            }
            painter.image(icon, left + PAD, top + (HEADER - ICON) / 2, ICON, ICON);
            painter.text(shortened(title, textRight - textLeft()), left + textLeft(), top + 10, NAME);
            painter.text(summary, left + textLeft(), top + 22, QUIET);
            painter.text(state, left + textRight + PAD, top + (HEADER - font.lineHeight) / 2, on ? ON : QUIET);
            for (int i = 0; i < details.size(); i++) {
                painter.text(details.get(i), left + textLeft(), top + HEADER + i * LINE, QUIET);
            }
        });
    }

    private boolean overButtons(double x, double y) {
        return y < getY() + HEADER && x >= getX() + controlsLeft();
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        return !overButtons(event.x(), event.y()) && super.mouseClicked(event, doubleClick);
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        toggle.run();
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (!isFocused() || !event.isSelection()) return super.keyPressed(event);
        toggle.run();
        return true;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
