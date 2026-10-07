package com.ladygo.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

/** docs/architecture/realtime-architecture.md section 7 - a session must not keep working past its
 *  token's expiry just because the socket is still open. */
class SessionExpirySweepJobTest {

    @Test
    void aSessionPastItsTokenExpiryIsClosed() throws Exception {
        WebSocketSessionRegistry registry = new WebSocketSessionRegistry();
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("s1");
        registry.reserve("reservation-1", "user-1", 3);
        registry.promote("reservation-1", "s1", "user-1", Instant.now().minusSeconds(1), session);

        new SessionExpirySweepJob(registry).closeExpiredSessions();

        verify(session).close(any(CloseStatus.class));
    }

    @Test
    void aSessionNotYetExpiredIsLeftOpen() throws Exception {
        WebSocketSessionRegistry registry = new WebSocketSessionRegistry();
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("s2");
        registry.reserve("reservation-2", "user-2", 3);
        registry.promote("reservation-2", "s2", "user-2", Instant.now().plusSeconds(60), session);

        new SessionExpirySweepJob(registry).closeExpiredSessions();

        verify(session, org.mockito.Mockito.never()).close(any(CloseStatus.class));
    }

    @Test
    void releaseFreesTheSlotForAnotherSessionFromTheSameUser() {
        WebSocketSessionRegistry registry = new WebSocketSessionRegistry();

        assertThat(registry.reserve("a", "user-3", 1)).isTrue();
        assertThat(registry.reserve("b", "user-3", 1)).isFalse(); // at the limit already

        registry.release("a");

        assertThat(registry.reserve("c", "user-3", 1)).isTrue(); // slot freed
    }
}
