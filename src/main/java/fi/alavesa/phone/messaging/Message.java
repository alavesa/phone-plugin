package fi.alavesa.phone.messaging;

import java.util.UUID;

/** One stored message. Messages belong to player UUIDs, never to a phone item. */
public record Message(long id, UUID sender, UUID recipient, String body, long sentAt, boolean read) {}
