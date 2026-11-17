package fi.alavesa.phone.ui;

import java.util.List;
import java.util.UUID;

/** What a screen shows, already resolved to display strings. Built by {@link UiController}, drawn by a {@link ScreenRenderer}. */
public final class Views {

    private Views() {}

    public record Home(String clock, String playerName, int unread) {}

    public record Contact(UUID id, String name, boolean online, String preview, boolean previewFromMe, String time, int unread) {}

    public record Contacts(String clock, List<Contact> page, int pageIndex, int pageCount, String filter, int totalUnread) {}

    public record Bubble(String body, boolean outgoing, String time, boolean read) {}

    public record Conversation(String clock, UUID partnerId, String partnerName, boolean online, List<Bubble> messages,
                               boolean hasOlder, boolean hasNewer, boolean blocked, String notice) {}

    public record PhotoResult(String clock, int zoom, String location) {}
}
