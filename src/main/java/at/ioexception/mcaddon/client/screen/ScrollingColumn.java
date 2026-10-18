package at.ioexception.mcaddon.client.screen;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;

/**
 * Widgets stacked one under another in a window of the screen that may be shorter than they are.
 *
 * Every widget keeps its place in the column, and the column moves them all up by how far it is
 * scrolled. What lies outside the window is cut off by a scissor set around the widgets as they are
 * drawn, so the widgets themselves stay visible and keyboard navigation still reaches all of them.
 *
 * The scroll position outlives a rebuild of the widgets: a column filled again keeps where it was,
 * moved back only as far as the new content needs.
 */
final class ScrollingColumn {
    private static final int BAR_WIDTH = 6;
    private static final int BAR_GAP = 4;
    private static final int MIN_THUMB = 8;
    private static final int TRACK_COLOUR = 0x80000000;
    private static final int THUMB_COLOUR = 0xFFA0A0A0;

    private record Placed(AbstractWidget widget, int offset) {
    }

    private final List<Placed> placed = new ArrayList<>();
    private final int pixelsPerNotch;
    private int left;
    private int right;
    private int top;
    private int bottom;
    private int contentHeight;
    private int scrolled;
    private boolean dragging;

    ScrollingColumn(int pixelsPerNotch) {
        this.pixelsPerNotch = pixelsPerNotch;
    }

    /** Empties the column for widgets laid out afresh, keeping how far it is scrolled. */
    void clear() {
        placed.clear();
        dragging = false;
    }

    /** A widget at this distance from the top of the content. */
    <T extends AbstractWidget> T add(T widget, int offset) {
        placed.add(new Placed(widget, offset));
        return widget;
    }

    /**
     * Gives the column its window on the screen once everything is in it, and moves every widget to
     * where the scroll position puts it.
     */
    void place(int left, int width, int top, int bottom, int contentHeight) {
        this.left = left;
        this.right = left + width;
        this.top = top;
        this.bottom = bottom;
        this.contentHeight = contentHeight;
        scrollTo(scrolled);
    }

    private int furthest() {
        return Math.max(0, contentHeight - (bottom - top));
    }

    int scrolled() {
        return scrolled;
    }

    void scrollTo(int position) {
        scrolled = Mth.clamp(position, 0, furthest());
        placed.forEach(entry -> entry.widget().setY(top + entry.offset() - scrolled));
    }

    /** Scrolls by the wheel's turn, answering whether there was anywhere to go. */
    boolean wheel(double notches) {
        if (furthest() == 0) return false;
        scrollTo(scrolled - (int) Math.round(notches * pixelsPerNotch));
        return true;
    }

    /** Scrolls just far enough for whichever of the column's widgets has the focus to be wholly shown. */
    void reveal() {
        placed.stream().map(Placed::widget).filter(AbstractWidget::isFocused).findFirst().ifPresent(widget -> {
            if (widget.getY() < top) {
                scrollTo(scrolled - (top - widget.getY()));
            } else if (widget.getY() + widget.getHeight() > bottom) {
                scrollTo(scrolled + widget.getY() + widget.getHeight() - bottom);
            }
        });
    }

    /** Whether a point is inside the window, where the column's widgets can be seen and clicked. */
    boolean shows(double y) {
        return y >= top && y < bottom;
    }

    private int barLeft() {
        return right + BAR_GAP;
    }

    /** Takes a press on the scroll bar, which moves the thumb under the pointer and follows it. */
    boolean grab(double x, double y) {
        if (furthest() == 0 || x < barLeft() || x >= barLeft() + BAR_WIDTH || !shows(y)) return false;
        dragging = true;
        drag(y);
        return true;
    }

    /** Follows the pointer while the bar is held, answering whether it is. */
    boolean drag(double y) {
        if (!dragging) return false;
        int travel = bottom - top - thumbHeight();
        double along = travel <= 0 ? 0 : (y - top - thumbHeight() / 2.0) / travel;
        scrollTo((int) Math.round(Mth.clamp(along, 0, 1) * furthest()));
        return true;
    }

    void release() {
        dragging = false;
    }

    private int thumbHeight() {
        int window = bottom - top;
        return Math.max(MIN_THUMB, window * window / Math.max(1, contentHeight));
    }

    /** Drawn before the column's widgets: everything after it is cut to the window. */
    Renderable clip() {
        return (graphics, mouseX, mouseY, partialTick) -> graphics.enableScissor(left, top, barLeft() + BAR_WIDTH, bottom);
    }

    /** Drawn after the column's widgets: ends the cut and draws the scroll bar when there is one. */
    Renderable unclip() {
        return (graphics, mouseX, mouseY, partialTick) -> {
            graphics.disableScissor();
            if (furthest() == 0) return;

            int thumbTop = top + (bottom - top - thumbHeight()) * scrolled / furthest();
            graphics.fill(barLeft(), top, barLeft() + BAR_WIDTH, bottom, TRACK_COLOUR);
            graphics.fill(barLeft(), thumbTop, barLeft() + BAR_WIDTH, thumbTop + thumbHeight(), THUMB_COLOUR);
        };
    }
}
