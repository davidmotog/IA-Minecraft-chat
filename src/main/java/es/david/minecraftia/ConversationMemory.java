package es.david.minecraftia;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

/** Holds recent exchanges on the Paper main thread. */
final class ConversationMemory {
    private static final int MAX_EXCHANGES = 4;
    private static final int MAX_PLAYERS = 256;
    private static final int MAX_TEXT = 600;
    private static final long LIFETIME = Duration.ofMinutes(30).toNanos();
    record Exchange(String question, String answer, String itemId) {}
    record Turn(UUID player, Session session, List<Exchange> history) {}

    static final class Session {
        private final ArrayDeque<Exchange> exchanges = new ArrayDeque<>();
        private long lastActivity;

        private Session(long now) {
            lastActivity = now;
        }
    }

    private final LinkedHashMap<UUID, Session> sessions = new LinkedHashMap<>(16, 0.75f, true);

    Turn begin(UUID player, long now) {
        expire(now);
        Session session = sessions.computeIfAbsent(player, ignored -> new Session(now));
        if (sessions.size() > MAX_PLAYERS) sessions.pollFirstEntry();
        return new Turn(player, session, List.copyOf(session.exchanges));
    }

    void remember(Turn turn, String question, String answer, String itemId, long now) {
        Session current = sessions.get(turn.player());
        // Forgetting or expiring a conversation also invalidates replies already in flight.
        if (current != turn.session() || now - current.lastActivity >= LIFETIME) return;
        current.exchanges.addLast(new Exchange(shorten(question), shorten(answer), itemId));
        if (current.exchanges.size() > MAX_EXCHANGES) current.exchanges.removeFirst();
        current.lastActivity = now;
    }

    void expire(long now) {
        sessions.values().removeIf(session -> now - session.lastActivity >= LIFETIME);
    }

    void forget(UUID player) {
        sessions.remove(player);
    }

    void clear() {
        sessions.clear();
    }

    private static String shorten(String text) {
        int end = text.offsetByCodePoints(0, Math.min(text.codePointCount(0, text.length()), MAX_TEXT));
        return end == text.length() ? text : text.substring(0, end) + "…";
    }
}
