package fi.alavesa.phone.ui;

import net.kyori.adventure.text.Component;

/**
 * Turns a view into the text of a dialog. Two implementations: {@link FancyRenderer} draws with the resource
 * pack's font (panels, speech bubbles, icons), {@link PlainRenderer} uses ordinary text for players without the pack.
 * Renderers hold no player state and are safe to call from any thread.
 *
 * <p>{@code belowPx} is the height of the inputs and buttons the dialog puts under the text, so a renderer that
 * draws a background can extend it behind them.
 */
public interface ScreenRenderer {

    /** Width of the dialog text area the screens are designed for. */
    int bodyWidth();

    Component lock(Views.Lock view, int belowPx);

    Component home(Views.Home view, int belowPx);

    Component contacts(Views.Contacts view, int belowPx);

    Component conversation(Views.Conversation view, int belowPx);

    Component ownMessages(Views.OwnMessages view, int belowPx);

    Component editMessage(Views.EditMessage view, int belowPx);

    Component photoResult(Views.PhotoResult view, int belowPx);

    Component settings(Views.Settings view, int belowPx);

    /** Label of an app on the home screen ("messages", "camera", "settings"). {@code badge} > 0 shows an unread count. */
    Component appButton(String app, String label, int badge);

    int appButtonWidth();

    /** Label of one contact in the contact list. */
    Component contactButton(Views.Contact contact);

    /** Label of one of the viewer's own messages on the edit list. */
    Component ownMessageButton(Views.Bubble message);

    /** Width of the full-row buttons (contacts, own messages). */
    int rowButtonWidth();

    /** Text lines one message takes on the conversation screen (for scrolling). */
    int messageLines(String body);
}
