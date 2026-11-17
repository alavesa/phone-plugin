package fi.alavesa.phone.messaging;

import java.util.UUID;

/** A conversation partner with the latest message of that conversation. {@code name} is null for a player the server has never seen. */
public record ContactRow(UUID partner, String name, String lastBody, long lastTime, boolean lastFromMe, int unread) {}
