package at.ioexception.mcaddon.bedrock.ui;

/**
 * What a screen hands its JSON UI: the values of its bindings, its collections and its factories,
 * and how wide the font draws a line.
 */
public interface UiData {
    /** A binding no collection holds, such as `#title_text` or `#hud_title_text_string`, or null. */
    Object global(String binding);

    /** A binding of one item of a collection, such as `#form_button_text` of `form_buttons`, or null. */
    Object item(String collection, int index, String binding);

    int length(String collection);

    /** Which of a factory's `control_ids` builds one item of a collection: `button`, `label` and so on. */
    String kind(String collection, int index);

    /** What a factory this screen drives has made for `owner`, such as a form or a title, or null. */
    default UiControl made(String factory, UiControl owner) {
        return null;
    }

    int textWidth(String text);

    int lineHeight();
}
