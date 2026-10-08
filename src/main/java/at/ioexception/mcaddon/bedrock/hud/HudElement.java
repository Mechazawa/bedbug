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
 * Three of Bedrock's pieces are drawn by nothing on a Java client: the paper doll, the touch
 * controls, and the hints Bedrock calls tooltips, which say what pressing a key would do to the
 * block or entity under the crosshair. Java's one such hint, how to get off a mount, is written to
 * the action bar a pack writes to as well.
 */
public enum HudElement {
    PAPERDOLL("paperdoll", false),
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
