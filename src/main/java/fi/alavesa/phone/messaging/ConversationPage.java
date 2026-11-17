package fi.alavesa.phone.messaging;

import java.util.List;

/**
 * A page of a conversation, oldest message first.
 *
 * @param markedRead  how many incoming messages loading this page marked as read
 * @param unreadTotal the viewer's unread count over all conversations afterwards
 */
public record ConversationPage(List<Message> messages, boolean hasOlder, boolean hasNewer, int markedRead, int unreadTotal) {}
