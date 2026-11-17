package fi.alavesa.phone.messaging;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Sliding window: at most {@code max} events per {@code windowMillis} for each player. Main thread only. */
public final class RateLimiter {

    private final Map<UUID, ArrayDeque<Long>> events = new HashMap<>();
    private final int max;
    private final long windowMillis;

    public RateLimiter(int max, long windowMillis) {
        this.max = max;
        this.windowMillis = windowMillis;
    }

    /** Records an event and returns true, or returns false when the player is over the limit. */
    public boolean tryAcquire(UUID player, long now) {
        ArrayDeque<Long> times = events.computeIfAbsent(player, k -> new ArrayDeque<>());
        while (!times.isEmpty() && now - times.peekFirst() >= windowMillis) times.pollFirst();
        if (times.size() >= max) return false;
        times.addLast(now);
        return true;
    }

    public void forget(UUID player) {
        events.remove(player);
    }
}
