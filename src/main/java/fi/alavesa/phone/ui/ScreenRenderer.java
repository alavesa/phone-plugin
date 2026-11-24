package fi.alavesa.phone.ui;

import java.util.UUID;
import net.kyori.adventure.text.Component;

/**
 * Turns a view into the text of a dialog. Two implementations: {@link FancyRenderer} draws with the resource
 * pack's font (the phone frame, speech bubbles, icons, photos), {@link PlainRenderer} uses ordinary text for players
 * without the pack. Renderers hold no player state and are safe to call from any thread.
 *
 * <p>{@code inputsPx} is the height of the text inputs the dialog puts between the text and the buttons (10 px gap
 * included), so a renderer that draws a background can reach down to the first row of buttons.
 *
 * <p>Button labels get the grid they are in: {@code columns} and whether the button is the first of its row. The
 * fancy renderer paints the background of a whole row from its first button (see {@link FancyRenderer}).
 */
public interface ScreenRenderer {

    /** Width of the dialog text area the screens are designed for. */
    int bodyWidth();

    /** Width of each button in a grid of {@code columns} columns (1, 2, 3 or 5). */
    int buttonWidth(int columns);

    /** True when the screen is a drawn phone: rows are filled up and the home-indicator row closes the phone. */
    boolean framed();

    Component button(Views.Btn button, int columns, boolean first);

    /** The last row of a framed phone: the bottom edge with the home indicator. */
    Component homeBar();

    Component lock(Views.Lock view, int inputsPx);

    Component home(Views.Home view, int inputsPx);

    /** An app on the home screen ("messages", "camera", "photos", "gram", "settings"); {@code badge} > 0 shows a count. */
    Component appButton(String app, String label, int badge, boolean first);

    Component contacts(Views.Contacts view, int inputsPx);

    Component contactButton(Views.Contact contact, boolean first);

    Component conversation(Views.Conversation view, int inputsPx);

    Component ownMessages(Views.OwnMessages view, int inputsPx);

    Component ownMessageButton(Views.Bubble message, boolean first);

    Component editMessage(Views.EditMessage view, int inputsPx);

    Component settings(Views.Settings view, int inputsPx);

    Component photos(Views.Photos view, int inputsPx);

    Component share(Views.Share view, int inputsPx);

    Component feed(Views.Feed view, int inputsPx);

    Component comments(Views.Comments view, int inputsPx);

    Component account(Views.Account view, int inputsPx);

    Component compose(Views.Compose view, int inputsPx);

    Component search(Views.Search view, int inputsPx);

    /** An account in the search results. */
    Component accountButton(UUID id, String handle, int posts, boolean first);

    Component activity(Views.Activity view, int inputsPx);

    /** A line of the activity list: {@code icon} heart / comment / repost. */
    Component activityButton(UUID who, String icon, String text, String age, boolean first);

    /** Text lines one message takes on the conversation screen (for scrolling). */
    int messageLines(String body);
}
