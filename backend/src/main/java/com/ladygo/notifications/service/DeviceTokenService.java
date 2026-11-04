package com.ladygo.notifications.service;

import com.ladygo.notifications.domain.AppType;
import com.ladygo.notifications.domain.DevicePlatform;
import com.ladygo.notifications.domain.DeviceToken;
import com.ladygo.notifications.repository.DeviceTokenRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DeviceTokenService {

    /** A user rarely has more than a phone and a tablet; older tokens beyond this are dropped so the table cannot grow unbounded. */
    static final int MAX_TOKENS_PER_USER_AND_APP = 5;

    private final DeviceTokenRepository tokens;

    public DeviceTokenService(DeviceTokenRepository tokens) {
        this.tokens = tokens;
    }

    /**
     * Idempotent. A token already registered to someone else (a shared or handed-over phone) moves to the caller:
     * pushes follow whoever is signed in on that device now.
     */
    @Transactional
    public void register(UUID userId, AppType app, DevicePlatform platform, String token) {
        DeviceToken device = tokens.findByToken(token).orElseGet(DeviceToken::new);
        device.setUserId(userId);
        device.setApp(app);
        device.setPlatform(platform);
        device.setToken(token);
        device.setLastSeenAt(Instant.now());
        tokens.saveAndFlush(device);

        List<DeviceToken> mine = tokens.findByUserIdAndAppOrderByLastSeenAtDesc(userId, app);
        if (mine.size() > MAX_TOKENS_PER_USER_AND_APP) {
            tokens.deleteAll(mine.subList(MAX_TOKENS_PER_USER_AND_APP, mine.size()));
        }
    }

    /** Sign-out: stop pushing to this device. Only the owner can remove a token; anything else is a silent no-op. */
    @Transactional
    public void unregister(UUID userId, String token) {
        tokens.findByToken(token).filter(d -> d.getUserId().equals(userId)).ifPresent(tokens::delete);
    }
}
