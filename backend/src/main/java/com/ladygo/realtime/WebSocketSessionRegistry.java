package com.ladygo.realtime;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

/**
 * Tracks live WebSocket sessions so the per-user connection limit and token-expiry disconnect can both
 * be enforced server-side (docs/architecture/realtime-architecture.md section 7). {@link #reserve} is
 * called at handshake time, before any {@link WebSocketSession} exists, so the per-user slot is
 * claimed atomically and a burst of simultaneous handshakes for the same user can't all slip past the
 * count check (a plain "count then insert" without the reservation step would race).
 */
@Component
public class WebSocketSessionRegistry {

    private record Entry(String userId, Instant tokenExpiry, WebSocketSession session) {
    }

    private final Map<String, Entry> bySessionId = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> sessionIdsByUser = new ConcurrentHashMap<>();

    /** Returns false (and claims nothing) if {@code userId} already has {@code maxPerUser} sessions. */
    public synchronized boolean reserve(String sessionId, String userId, int maxPerUser) {
        Set<String> existing = sessionIdsByUser.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet());
        if (existing.size() >= maxPerUser) {
            return false;
        }
        existing.add(sessionId);
        bySessionId.put(sessionId, new Entry(userId, null, null));
        return true;
    }

    /** Swaps the handshake-time placeholder reservation for the real session, once Spring has assigned
     *  it an id - same user, same claimed slot, just the key changing from placeholder to real id. */
    public synchronized void promote(
            String reservationKey, String sessionId, String userId, Instant tokenExpiry, WebSocketSession session) {
        bySessionId.remove(reservationKey);
        Set<String> set = sessionIdsByUser.get(userId);
        if (set != null) {
            set.remove(reservationKey);
            set.add(sessionId);
        }
        bySessionId.put(sessionId, new Entry(userId, tokenExpiry, session));
    }

    public void release(String sessionId) {
        Entry entry = bySessionId.remove(sessionId);
        if (entry != null) {
            Set<String> set = sessionIdsByUser.get(entry.userId());
            if (set != null) {
                set.remove(sessionId);
            }
        }
    }

    /** Live {@link WebSocketSession}s whose token expiry has passed - the caller closes them. */
    public List<WebSocketSession> sessionsExpiredAsOf(Instant now) {
        return bySessionId.values().stream()
                .filter(e -> e.session() != null && e.tokenExpiry() != null && !now.isBefore(e.tokenExpiry()))
                .map(Entry::session)
                .toList();
    }

    /** Test/diagnostic only. */
    int sessionCountForUser(String userId) {
        Set<String> set = sessionIdsByUser.get(userId);
        return set == null ? 0 : set.size();
    }
}
