package at.ioexception.mcaddon.client.screen;

import at.ioexception.mcaddon.platform.Platform;
import net.minecraft.client.gui.ComponentPath;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * A screen of this mod's own: a heading, a column of text and widgets that scrolls when it is taller
 * than the window, and a Done button under it that goes back to the screen it was opened from.
 */
abstract class ColumnScreen extends Screen {
    static final int WIDTH = 310;
    static final int ROW_HEIGHT = 24;
    static final int BUTTON_HEIGHT = 20;
    private static final int LINE_HEIGHT = 12;
    private static final int TEXT_PADDING = 8;
    private static final int TOP_MARGIN = 32;
    private static final int HEADING_GAP = 6;
    private static final int FOOTER = 32;
    private static final int DONE_WIDTH = 200;

    private final Screen parent;
    private final ScrollingColumn column = new ScrollingColumn(ROW_HEIGHT);
    private int row;

    ColumnScreen(Component title, Screen parent) {
        super(title);
        this.parent = parent;
    }

    /** Adds the column's content, top to bottom, through {@link #text} and {@link #widget}. */
    protected abstract void fill();

    int left() {
        return width / 2 - WIDTH / 2;
    }

    @Override
    protected final void init() {
        int scrolled = column.scrolled();
        column.clear();
        row = 0;

        StringWidget heading = addRenderableWidget(new StringWidget(left(), 0, WIDTH, LINE_HEIGHT, title, font));
        addRenderableOnly(column.clip());
        fill();
        addRenderableOnly(column.unclip());

        column.place(left(), WIDTH, TOP_MARGIN, height - FOOTER, row);
        column.scrollTo(scrolled);
        heading.setY(TOP_MARGIN - LINE_HEIGHT - HEADING_GAP);
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> onClose())
            .bounds(width / 2 - DONE_WIDTH / 2, height - FOOTER + (FOOTER - BUTTON_HEIGHT) / 2, DONE_WIDTH,
                BUTTON_HEIGHT)
            .build());
    }

    /**
     * Text in as many lines as it needs at the column's width. Java draws a widget's message on one
     * line, so the breaks a pack wrote are taken here and a line too wide is broken at a space.
     */
    void text(String text) {
        wrap(font, text, WIDTH - TEXT_PADDING).forEach(line -> {
            addRenderableWidget(column.add(new StringWidget(left(), 0, WIDTH, LINE_HEIGHT,
                Component.literal(line), font), row));
            row += LINE_HEIGHT;
        });
    }

    /** A widget on a row of its own, or beside the ones before it on the same row. */
    <T extends AbstractWidget> T widget(T widget) {
        addRenderableWidget(column.add(widget, row));
        return widget;
    }

    /** Ends a row of widgets. */
    void nextRow() {
        row += ROW_HEIGHT;
    }

    /** Half a row of space, which keeps two groups from reading as one. */
    void gap() {
        row += ROW_HEIGHT / 2;
    }

    /** Text broken at its own line breaks, and at a space wherever a line is wider than `width`. */
    static List<String> wrap(net.minecraft.client.gui.Font font, String text, int width) {
        List<String> lines = new ArrayList<>();
        for (String paragraph : text.split("\\r?\\n", -1)) {
            String rest = paragraph;
            while (font.width(rest) > width) {
                int cut = rest.length();
                while (cut > 1 && font.width(rest.substring(0, cut)) > width) {
                    cut = rest.lastIndexOf(' ', cut - 1) > 0 ? rest.lastIndexOf(' ', cut - 1) : cut - 1;
                }
                lines.add(rest.substring(0, cut));
                rest = rest.substring(cut).stripLeading();
            }
            lines.add(rest);
        }
        return lines;
    }

    @Override
    public void onClose() {
        Platform.setScreen(parent);
    }

    /** Tab and the arrow keys move the focus to a widget the column may have scrolled out of sight. */
    @Override
    protected void changeFocus(ComponentPath path) {
        super.changeFocus(path);
        column.reveal();
    }

    //? if >=26 {
    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
        return column.grab(event.x(), event.y())
            || (column.shows(event.y()) || event.y() >= height - FOOTER) && super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(net.minecraft.client.input.MouseButtonEvent event, double dragX, double dragY) {
        return column.drag(event.y()) || super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(net.minecraft.client.input.MouseButtonEvent event) {
        column.release();
        return super.mouseReleased(event);
    }
    //?} else {
    /*@Override
    public boolean mouseClicked(double x, double y, int button) {
        return column.grab(x, y) || (column.shows(y) || y >= height - FOOTER) && super.mouseClicked(x, y, button);
    }

    @Override
    public boolean mouseDragged(double x, double y, int button, double dragX, double dragY) {
        return column.drag(y) || super.mouseDragged(x, y, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double x, double y, int button) {
        column.release();
        return super.mouseReleased(x, y, button);
    }
    *///?}

    //? if >=1.20.2 {
    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        return column.wheel(scrollY) || super.mouseScrolled(x, y, scrollX, scrollY);
    }
    //?} else {
    /*@Override
    public boolean mouseScrolled(double x, double y, double scrollY) {
        return column.wheel(scrollY) || super.mouseScrolled(x, y, scrollY);
    }
    *///?}
}
