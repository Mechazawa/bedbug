package at.ioexception.mcaddon.client.screen;

import at.ioexception.mcaddon.platform.Platform;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/** A square button drawn with a picture instead of a label; the label is what it is narrated and found by. */
final class IconButton extends AbstractButton {
    static final int SIZE = 20;
    private static final int PICTURE = 16;

    private final Identifier picture;
    private final Identifier dimmed;
    private final Runnable action;

    IconButton(Component label, Identifier picture, Identifier dimmed, Runnable action) {
        super(0, 0, SIZE, SIZE, label);
        this.picture = picture;
        this.dimmed = dimmed;
        this.action = action;
    }

    @Override
    public void onPress(InputWithModifiers input) {
        action.run();
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        extractDefaultSprite(graphics);
        Platform.paint(graphics, painter -> painter.image(active ? picture : dimmed,
            getX() + (SIZE - PICTURE) / 2, getY() + (SIZE - PICTURE) / 2, PICTURE, PICTURE));
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
