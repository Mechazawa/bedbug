package at.ioexception.mcaddon.client.screen;

import at.ioexception.mcaddon.client.WorldAddons;
import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.registry.ActiveAddons;
import at.ioexception.mcaddon.state.WorldAddonList;
import com.mojang.blaze3d.platform.NativeImage;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
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
 * Open it with `new AddonsScreen(parent)`; Done goes back to `parent`. In a world each row reads On
 * or Off as the world plays it and nothing switches. Create World and Edit World open it with a
 * switch on each row over the world's add-on list: Create World plays the choice once Done is
 * pressed, and Edit World writes each switch into the world's list at once and activates nothing.
 */
public final class AddonsScreen extends ColumnScreen {
    /** Where the list is opened from, which decides whether a row can be switched. */
    private enum Mode {
        IN_WORLD, CREATE_WORLD, EDIT_WORLD
    }

    private static final Identifier GEAR = Platform.id("mcaddon", "textures/gui/gear.png");
    private static final Identifier GEAR_OFF = Platform.id("mcaddon", "textures/gui/gear_off.png");
    private static final Identifier INFO = Platform.id("mcaddon", "textures/gui/info.png");

    private final Mode mode;
    /** The world whose list Edit World switches, or null. */
    private final Path world;
    private final WorldAddonList initial;
    private WorldAddonList chosen;
    private final Set<Path> opened = new HashSet<>();
    private final Map<Path, Identifier> icons = new HashMap<>();

    public AddonsScreen(Screen parent) {
        this(parent, Mode.IN_WORLD, null, null);
    }

    private AddonsScreen(Screen parent, Mode mode, Path world, WorldAddonList list) {
        super(Component.translatable("mcaddon.addons.title"), parent);
        this.mode = mode;
        this.world = world;
        this.initial = list;
        this.chosen = list;
    }

    /** The add-ons a world being created will play, every installed one on until the player chooses. */
    public static AddonsScreen forNewWorld(CreateWorldScreen parent) {
        return new AddonsScreen(parent, Mode.CREATE_WORLD, null,
            WorldAddons.chosen().orElseGet(() -> WorldAddonList.allOn(ActiveAddons.installed())));
    }

    /** The add-ons a saved world plays, switched in its own list. */
    public static AddonsScreen forWorld(Screen parent, Path world) {
        return new AddonsScreen(parent, Mode.EDIT_WORLD, world, WorldAddonList.read(world)
            .map(list -> list.naming(ActiveAddons.installed()))
            .orElseGet(() -> WorldAddonList.allOn(ActiveAddons.installed())));
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
        List<AddonEntry> addons = AddonEntry.loaded().stream()
            .map(addon -> chosen == null ? addon : new AddonEntry(addon.converted(), chosen.plays(addon.uuid())))
            .toList();
        if (addons.isEmpty()) text(Component.translatable("mcaddon.addons.none").getString());
        addons.forEach(this::show);
    }

    private void show(AddonEntry addon) {
        Path root = addon.converted().root();
        boolean switchable = chosen != null;
        String warning = mode == Mode.EDIT_WORLD && !addon.active() && addon.converted().addsDimension()
            ? Component.translatable("mcaddon.addons.dimension_warning").getString() : "";
        AddonRow row = widget(new AddonRow(left(), WIDTH, font, addon, iconOf(root), opened.contains(root),
            switchable, warning, () -> {
                if (!opened.remove(root)) opened.add(root);
                rebuildWidgets();
            }));

        if (switchable) {
            Button turn = widgetBelow(Button.builder(Component.translatable(addon.active()
                    ? "mcaddon.addons.state.on" : "mcaddon.addons.state.off"), button -> turn(addon))
                .size(AddonRow.SWITCH, BUTTON_HEIGHT).build(), (AddonRow.HEADER - BUTTON_HEIGHT) / 2);
            turn.setX(left() + row.controlsLeft());
        }

        IconButton settings = widgetBelow(new IconButton(Component.translatable("mcaddon.addons.settings"), GEAR,
            GEAR_OFF, () -> Platform.setScreen(new PackSettingsScreen(this, addon))), AddonRow.CONTROLS_TOP);
        settings.setX(left() + row.iconsLeft());
        settings.active = addon.hasSettings();
        settings.setTooltip(Tooltip.create(Component.translatable(
            settings.active ? "mcaddon.addons.settings" : "mcaddon.addons.settings.none")));

        IconButton report = widgetBelow(new IconButton(Component.translatable("mcaddon.addons.report"), INFO, INFO,
            () -> Platform.setScreen(new AddonReportScreen(this, addon))), AddonRow.CONTROLS_TOP);
        report.setX(settings.getX() + IconButton.SIZE + 2);
        report.setTooltip(Tooltip.create(Component.translatable("mcaddon.addons.report")));

        skip(row.getHeight() + AddonRow.GAP);
    }

    /** Switches one add-on in the list, which Edit World writes into the world at once. */
    private void turn(AddonEntry addon) {
        chosen = chosen.with(addon.uuid(), !addon.active());
        if (mode == Mode.EDIT_WORLD) chosen.write(world);
        rebuildWidgets();
    }

    /** Create World plays a changed choice as it is shown again. */
    @Override
    public void onClose() {
        super.onClose();
        if (mode == Mode.CREATE_WORLD && !chosen.equals(initial)) {
            WorldAddons.choose((CreateWorldScreen) parent, chosen);
        }
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
