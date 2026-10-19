package at.ioexception.mcaddon.bedrock.hud;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;

/**
 * A piece of the heads-up display that `/hud` and `ScreenDisplay.setHudVisibility` hide and reset.
 *
 * The constants stand in the order of the script API's own `HudElement`, so a constant's ordinal is
 * the number a script names it by, and a set of them crosses to a client as one bitmask.
 *
 * The paper doll is drawn by this mod, in `client.render.PaperDoll`. Two pieces are drawn by
 * nothing. The touch controls are the buttons Bedrock draws on a touch screen, which a Java client
 * never runs on. The tooltips are Bedrock's lists of button prompts (`left_helpers` and
 * `right_helpers` in its `hud_screen.json`), which say what a key would do to the block or entity
 * under the crosshair: which prompts show, for which key, on which side and in what order is
 * decided in engine code Mojang publishes nowhere.
 */
public enum HudElement {
    PAPERDOLL("paperdoll", true),
    ARMOR("armor", true),
    TOOLTIPS("tooltips", false),
    TOUCH_CONTROLS("touch_controls", false),
    CROSSHAIR("crosshair", true),
    HOTBAR("hotbar", true),
    HEALTH("health", true),
    PROGRESS_BAR("progress_bar", true),
    HUNGER("hunger", true),
    AIR_BUBBLES("air_bubbles", true),
    HORSE_HEALTH("horse_health", true),
    STATUS_EFFECTS("status_effects", true),
    ITEM_TEXT("item_text", true);

    private final String bedrockName;
    private final boolean drawnByJava;

    HudElement(String bedrockName, boolean drawnByJava) {
        this.bedrockName = bedrockName;
        this.drawnByJava = drawnByJava;
    }

    /** The piece a command names, or null for a word that names none, which `all` is as well. */
    public static HudElement tryFrom(String bedrockName) {
        for (HudElement element : values()) {
            if (element.bedrockName().equalsIgnoreCase(bedrockName)) return element;
        }
        return null;
    }

    /** The piece a script names by number, or null for a number that names none. */
    public static HudElement tryFrom(int scriptValue) {
        return scriptValue >= 0 && scriptValue < values().length ? values()[scriptValue] : null;
    }

    public String bedrockName() {
        return bedrockName;
    }

    public int scriptValue() {
        return ordinal();
    }

    public boolean drawnByJava() {
        return drawnByJava;
    }

    public static int bits(Collection<HudElement> elements) {
        return elements.stream().mapToInt(element -> 1 << element.ordinal()).reduce(0, (left, right) -> left | right);
    }

    public static Set<HudElement> fromBits(int bits) {
        Set<HudElement> elements = EnumSet.noneOf(HudElement.class);
        for (HudElement element : values()) {
            if ((bits & (1 << element.ordinal())) != 0) elements.add(element);
        }
        return elements;
    }
}
