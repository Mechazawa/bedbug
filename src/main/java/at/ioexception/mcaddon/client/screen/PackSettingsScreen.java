package at.ioexception.mcaddon.client.screen;

import at.ioexception.mcaddon.client.PlayerPackSettings;
import at.ioexception.mcaddon.pack.PackManifest;
import at.ioexception.mcaddon.pack.PackRole;
import at.ioexception.mcaddon.pack.PackSettings;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * One add-on's settings, drawn from its manifests the way Bedrock draws them: labels, toggles,
 * sliders and dropdowns, in the order the pack lists them.
 *
 * A behaviour pack's settings are the world's. They are shown as the server last told this client
 * and changed by asking the server, so they stay greyed out unless the server says this player may
 * change them, and outside a world. A resource pack's settings are the player's own and change here
 * at once.
 */
public final class PackSettingsScreen extends ColumnScreen {
    private final AddonEntry addon;
    private final List<Runnable> shown = new ArrayList<>();
    private boolean drawnChangeable;

    PackSettingsScreen(Screen parent, AddonEntry addon) {
        super(Component.translatable("mcaddon.settings.title", addon.converted().displayName()), parent);
        this.addon = addon;
    }

    @Override
    public void added() {
        super.added();
        PlayerPackSettings.ask();
    }

    /**
     * The server answered: every control shows the value it holds now, and the screen is laid out
     * again when whether this player may change the world's settings has changed.
     */
    public void told() {
        if (drawnChangeable != PlayerPackSettings.mayChangeWorld()) {
            rebuildWidgets();
        } else {
            shown.forEach(Runnable::run);
        }
    }

    @Override
    protected void fill() {
        shown.clear();
        drawnChangeable = PlayerPackSettings.mayChangeWorld();
        section(PackRole.BEHAVIOUR);
        section(PackRole.RESOURCE);
    }

    private void section(PackRole role) {
        List<PackManifest.Setting> settings = addon.packs().stream()
            .filter(pack -> pack.role() == role)
            .flatMap(pack -> pack.manifest().settings().stream())
            .toList();
        if (settings.isEmpty()) return;

        boolean world = role == PackRole.BEHAVIOUR;
        text(Component.translatable(world ? "mcaddon.settings.world" : "mcaddon.settings.player").getString());
        if (world && !PlayerPackSettings.connected()) {
            text(Component.translatable("mcaddon.settings.world.closed").getString());
        } else if (world && !drawnChangeable) {
            text(Component.translatable("mcaddon.settings.world.refused").getString());
        }
        settings.forEach(setting -> control(setting, world));
        gap();
    }

    private void control(PackManifest.Setting declared, boolean world) {
        if (declared.name() == null) {
            text(Component.translatable(declared.text()).getString());
            return;
        }

        PackSettings layer = world ? PackSettings.TOLD : PackSettings.CHOSEN;
        boolean changeable = !world || drawnChangeable;
        switch (declared.type()) {
            case "toggle" -> {
                Button toggle = widget(Button.builder(Component.empty(), button ->
                        change(world, declared.name(), new JsonPrimitive(!layer.of(declared.name()).enabled())))
                    .bounds(left(), 0, WIDTH, BUTTON_HEIGHT).build());
                show(() -> toggle.setMessage(labelled(declared, Component.translatable(
                    layer.of(declared.name()).enabled() ? "mcaddon.settings.on" : "mcaddon.settings.off"))));
                toggle.active = changeable;
            }
            case "dropdown" -> {
                Button dropdown = widget(Button.builder(Component.empty(), button ->
                        change(world, declared.name(), new JsonPrimitive(following(layer.of(declared.name())))))
                    .bounds(left(), 0, WIDTH, BUTTON_HEIGHT).build());
                show(() -> dropdown.setMessage(labelled(declared, chosenText(layer.of(declared.name())))));
                dropdown.active = changeable;
            }
            case "slider" -> {
                SettingSlider slider = widget(new SettingSlider(declared, layer, world));
                show(slider::showHeld);
                slider.active = changeable;
            }
            default -> {
                return;
            }
        }
        nextRow();
    }

    private void show(Runnable shows) {
        shows.run();
        shown.add(shows);
    }

    private void change(boolean world, String name, JsonElement value) {
        if (world) {
            PlayerPackSettings.propose(name, value);
        } else {
            PlayerPackSettings.choose(name, value);
            shown.forEach(Runnable::run);
        }
    }

    private static Component labelled(PackManifest.Setting setting, Component value) {
        return Component.translatable("mcaddon.settings.value", Component.translatable(setting.text()), value);
    }

    /** The option after the one a dropdown holds, the first again after the last. */
    private static String following(PackManifest.Setting dropdown) {
        List<PackManifest.Setting.Option> options = dropdown.options();
        if (options.isEmpty()) return dropdown.value() == null ? "" : dropdown.value().getAsString();

        int held = options.stream().map(PackManifest.Setting.Option::name).toList()
            .indexOf(dropdown.value() == null ? "" : dropdown.value().getAsString());
        return options.get((held + 1) % options.size()).name();
    }

    private static Component chosenText(PackManifest.Setting dropdown) {
        return dropdown.options().stream()
            .filter(option -> dropdown.selected(option.name()))
            .findFirst()
            .<Component>map(option -> Component.translatable(option.text()))
            .orElse(Component.empty());
    }

    /**
     * A slider over a setting's range that stops on its steps, showing the value and not a
     * fraction. Moving it changes the setting only when it lands on another step.
     */
    private final class SettingSlider extends AbstractSliderButton {
        private final PackManifest.Setting declared;
        private final PackSettings layer;
        private final boolean world;
        private final double low;
        private final double high;

        SettingSlider(PackManifest.Setting declared, PackSettings layer, boolean world) {
            super(left(), 0, WIDTH, BUTTON_HEIGHT, Component.empty(), 0);
            this.declared = declared;
            this.layer = layer;
            this.world = world;
            this.low = Double.isFinite(declared.min()) ? declared.min() : 0;
            this.high = Double.isFinite(declared.max()) && declared.max() > low ? declared.max() : low + 1;
        }

        /** Moves the slider to the value the setting holds, without changing the setting. */
        void showHeld() {
            value = (layer.of(declared.name()).number() - low) / (high - low);
            updateMessage();
        }

        private double pointedAt() {
            return declared.with(new JsonPrimitive(low + value * (high - low))).number();
        }

        @Override
        protected void updateMessage() {
            double shown = pointedAt();
            setMessage(labelled(declared, Component.literal(
                shown == Math.rint(shown) ? String.valueOf((long) shown) : String.valueOf(shown))));
        }

        @Override
        protected void applyValue() {
            if (pointedAt() != layer.of(declared.name()).number()) {
                change(world, declared.name(), new JsonPrimitive(pointedAt()));
            }
        }
    }
}
