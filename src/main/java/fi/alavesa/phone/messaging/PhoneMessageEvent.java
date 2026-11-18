package fi.alavesa.phone.messaging;

import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Called on the main thread when a player sends a phone message (or edits one: {@link #isEdit}), after the plugin's own checks (permission, mute,
 * rate limit, length) and before the message is stored or delivered. Cancel it to drop the message (the sender is
 * told it was not sent), change the text with {@link #setMessage}, or just log it.
 */
public final class PhoneMessageEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player sender;
    private final UUID recipientId;
    private final String recipientName;
    private String message;
    private boolean cancelled;
    private final boolean edit;

    public PhoneMessageEvent(Player sender, UUID recipientId, String recipientName, String message) {
        this(sender, recipientId, recipientName, message, false);
    }

    public PhoneMessageEvent(Player sender, UUID recipientId, String recipientName, String message, boolean edit) {
        this.edit = edit;
        this.sender = sender;
        this.recipientId = recipientId;
        this.recipientName = recipientName;
        this.message = message;
    }

    /** True when the sender is changing a message they sent earlier; {@link #getMessage} is the new text. */
    public boolean isEdit() {
        return edit;
    }

    public Player getSender() {
        return sender;
    }

    /** The recipient may be offline; the message is then delivered when they join. */
    public UUID getRecipientId() {
        return recipientId;
    }

    public String getRecipientName() {
        return recipientName;
    }

    public String getMessage() {
        return message;
    }

    /** Replaces the text. It is cleaned and cut to the configured length again afterwards. */
    public void setMessage(String message) {
        this.message = message == null ? "" : message;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
