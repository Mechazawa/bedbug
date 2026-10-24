package at.ioexception.mcaddon.client.screen;

import at.ioexception.mcaddon.platform.Platform;
import com.mojang.blaze3d.platform.NativeImage;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Bedrock's pack list: every add-on this game has loaded as a row with its icon, name, description
 * and whether it is on, the way to its settings and to what this mod could not do for it, and a
 * click on the row opening it to the rest.
 *
 * Open it with `new AddonsScreen(parent)`; Done goes back to `parent`. In a world the list only
 * shows what is on; the other modes are where the player will choose.
 */
public final class AddonsScreen extends ColumnScreen {
    /** Where the list is opened from, which decides whether a row can be switched. */
    public enum Mode {
        IN_WORLD, CREATE_WORLD, EDIT_WORLD
    }

    private static final Identifier GEAR = Platform.id("mcaddon", "textures/gui/gear.png");
    private static final Identifier GEAR_OFF = Platform.id("mcaddon", "textures/gui/gear_off.png");
    private static final Identifier INFO = Platform.id("mcaddon", "textures/gui/info.png");

    private final Mode mode;
    private final Set<Path> opened = new HashSet<>();
    private final Map<Path, Identifier> icons = new HashMap<>();

    public AddonsScreen(Screen parent) {
        this(parent, Mode.IN_WORLD);
    }

    public AddonsScreen(Screen parent, Mode mode) {
        super(Component.translatable("mcaddon.addons.title"), parent);
        this.mode = mode;
    }

    /**
     * Puts an Add-Ons button on the pause menu, in the row of the button that leaves the world, which
     * moves down a row to make room.
     */
    public static void addToPauseMenu() {
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (!(screen instanceof PauseScreen)) return;

            //? if >=26 {
            List<AbstractWidget> buttons = Screens.getWidgets(screen);
            //?} else {
            /*List<AbstractWidget> buttons = Screens.getButtons(screen);
            *///?}
            buttons.stream()
                .filter(Button.class::isInstance)
                .max(Comparator.comparingInt(AbstractWidget::getY))
                .ifPresent(leave -> {
                    buttons.add(Button.builder(Component.translatable("mcaddon.addons.button"),
                            button -> Platform.setScreen(new AddonsScreen(screen)))
                        .bounds(leave.getX(), leave.getY(), leave.getWidth(), BUTTON_HEIGHT)
                        .build());
                    leave.setY(leave.getY() + ROW_HEIGHT);
                });
        });
    }

    @Override
    protected void fill() {
        List<AddonEntry> addons = AddonEntry.loaded();
        if (addons.isEmpty()) text(Component.translatable("mcaddon.addons.none").getString());
        addons.forEach(this::show);
    }

    private void show(AddonEntry addon) {
        Path root = addon.converted().root();
        AddonRow row = widget(new AddonRow(left(), WIDTH, font, addon, iconOf(root), opened.contains(root), () -> {
            if (!opened.remove(root)) opened.add(root);
            rebuildWidgets();
        }));

        IconButton settings = widgetBelow(new IconButton(Component.translatable("mcaddon.addons.settings"), GEAR,
            GEAR_OFF, () -> Platform.setScreen(new PackSettingsScreen(this, addon))), AddonRow.CONTROLS_TOP);
        settings.setX(left() + row.controlsLeft());
        settings.active = addon.hasSettings();
        settings.setTooltip(Tooltip.create(Component.translatable(
            settings.active ? "mcaddon.addons.settings" : "mcaddon.addons.settings.none")));

        IconButton report = widgetBelow(new IconButton(Component.translatable("mcaddon.addons.report"), INFO, INFO,
            () -> Platform.setScreen(new AddonReportScreen(this, addon))), AddonRow.CONTROLS_TOP);
        report.setX(settings.getX() + IconButton.SIZE + 2);
        report.setTooltip(Tooltip.create(Component.translatable("mcaddon.addons.report")));

        skip(row.getHeight() + AddonRow.GAP);
    }

    /** The pack's own icon as a texture of this screen's, or the game's blank one where there is none. */
    private Identifier iconOf(Path root) {
        return icons.computeIfAbsent(root, key -> {
            Path file = key.resolve("pack.png");
            if (!Files.isRegularFile(file)) return AddonRow.unknownIcon();
            Identifier id = Platform.id("mcaddon", "addon_icon/" + Integer.toHexString(key.toString().hashCode()));
            try (InputStream stream = Files.newInputStream(file)) {
                NativeImage image = NativeImage.read(stream);
                Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(id::toString, image));
                return id;
            } catch (IOException | RuntimeException unreadable) {
                return AddonRow.unknownIcon();
            }
        });
    }

    @Override
    public void removed() {
        icons.values().stream().filter(id -> !id.equals(AddonRow.unknownIcon()))
            .forEach(Minecraft.getInstance().getTextureManager()::release);
        icons.clear();
        super.removed();
    }
}
