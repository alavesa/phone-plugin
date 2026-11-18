package fi.alavesa.phone.ui;

import java.util.List;
import java.util.UUID;

/** What a screen shows, already resolved to display strings. Built by {@link UiController}, drawn by a {@link ScreenRenderer}. */
public final class Views {

    private Views() {}

    /** The lock screen in its three uses. */
    public enum LockMode { LOCKED, SETUP, CHANGE }

    public record Lock(String clock, String date, String playerName, LockMode mode, String notice) {}

    public record Home(String clock, String playerName, int unread) {}

    public record Contact(UUID id, String name, boolean online, String preview, boolean previewFromMe, String time, int unread) {}

    public record Contacts(String clock, List<Contact> page, int pageIndex, int pageCount, String filter, int totalUnread) {}

    public record Bubble(String body, boolean outgoing, String time, boolean read, boolean edited) {}

    public record Conversation(String clock, UUID partnerId, String partnerName, boolean online, List<Bubble> messages,
                               boolean hasOlder, boolean hasNewer, boolean blocked, String notice) {}

    /** The list of the viewer's own messages to pick one for editing. */
    public record OwnMessages(String clock, String partnerName, int count, String notice) {}

    public record EditMessage(String clock, String partnerName, Bubble message, String notice) {}

    /** @param seconds 0 for a photo, the length of a video otherwise */
    public record PhotoResult(String clock, int zoom, String location, double seconds) {}

    public record Settings(String clock, String playerName, String packLine) {}
}
