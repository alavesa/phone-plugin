package fi.alavesa.phone.ui;

import java.util.List;
import java.util.UUID;

/** What a screen shows, already resolved to display strings. Built by {@link UiController}, drawn by a {@link ScreenRenderer}. */
public final class Views {

    private Views() {}

    /** The lock screen in its three uses. */
    public enum LockMode { LOCKED, SETUP, CHANGE }

    /** How a button's label is coloured. */
    public enum Tone { NORMAL, DIM, ACCENT, ACTIVE, PRINT, DANGER }

    /**
     * One button. {@code icon} is a mark of the pack ("heart", "left" ...; null = none), drawn before the label.
     * {@code path} is the click action (without the namespace); "nop" does nothing.
     */
    public record Btn(String icon, String label, Tone tone, String path, String tooltip) {
        public static Btn of(String label, String path) {
            return new Btn(null, label, Tone.NORMAL, path, null);
        }

        public static Btn blank() {
            return new Btn(null, "", Tone.DIM, "nop", null);
        }
    }

    public record Lock(String clock, String date, String playerName, LockMode mode, String notice) {}

    public record Home(String clock, String date, String playerName, int unread, int gramActivity) {}

    public record Contact(UUID id, String name, boolean online, String preview, boolean previewFromMe, String time, int unread) {}

    public record Contacts(String clock, List<Contact> page, int pageIndex, int pageCount, String filter, int totalUnread) {}

    public record Bubble(String body, boolean outgoing, String time, boolean read, boolean edited) {}

    public record Conversation(String clock, UUID partnerId, String partnerName, boolean online, List<Bubble> messages,
                               boolean hasOlder, boolean hasNewer, boolean blocked, String notice) {}

    /** The list of the viewer's own messages to pick one for editing. */
    public record OwnMessages(String clock, String partnerName, int count, String notice) {}

    public record EditMessage(String clock, String partnerName, Bubble message, String notice) {}

    public record Settings(String clock, String playerName, String packLine) {}

    /**
     * The Photos app showing one picture.
     *
     * @param photo  128x128 pixels, or null when the library is empty
     * @param index  0-based position, {@code count} pictures in all
     * @param info   date and origin ("from Alex", "video 3.0 s")
     */
    public record Photos(String clock, int[] photo, boolean video, int index, int count, String info, String notice) {}

    /** Who to share a picture with. */
    public record Share(String clock, String notice) {}

    /** One post on a Minestagram screen. {@code reposter} is the handle of who reposted it, else null. */
    public record Post(UUID author, String handle, String age, String caption, int[] photo, boolean video, int likes, int comments,
                       int reposts, boolean liked, boolean reposted, String reposter) {}

    /**
     * The feed or one account's page. {@code post} is null when there is nothing to show.
     *
     * @param profile the account whose page this is; null for the feed
     */
    public record Feed(String clock, Post post, int index, int total, Profile profile, String notice) {}

    public record Profile(UUID id, String handle, String bio, int posts, boolean own) {}

    public record Comment(String handle, String body, String age) {}

    public record Comments(String clock, String postHandle, String caption, List<Comment> comments, boolean hasOlder, boolean hasNewer,
                           String notice) {}

    /** Creating or editing a Minestagram account. */
    public record Account(String clock, boolean create, String notice) {}

    /** Picking a photo for a new post. {@code photo} null = the library is empty. */
    public record Compose(String clock, int[] photo, boolean video, int index, int count, String notice) {}

    public record Search(String clock, String query, int results, String notice) {}

    public record Activity(String clock, int count) {}
}
