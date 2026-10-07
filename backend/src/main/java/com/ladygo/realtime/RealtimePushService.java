package com.ladygo.realtime;

import java.util.UUID;

/**
 * The one way any other module pushes to a connected user (docs/api/matching.md section 7). Never the
 * source of truth for anything - every caller's correctness holds even if a push never arrives (HTTP
 * polling / the next request's response is always the fallback). The default implementation delivers
 * only to this node; {@code ladygo.realtime.redis.enabled=true} layers in cross-node fan-out without
 * changing this interface or any caller.
 */
public interface RealtimePushService {

    void pushToUser(UUID userId, String destination, Object payload);
}
