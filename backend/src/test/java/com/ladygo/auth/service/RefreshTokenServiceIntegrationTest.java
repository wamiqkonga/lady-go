package com.ladygo.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import com.ladygo.common.exception.UnauthorizedException;
import com.ladygo.testsupport.IntegrationTest;
import com.ladygo.users.domain.UserRole;
import com.ladygo.users.domain.User;
import com.ladygo.users.repository.UserRepository;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@IntegrationTest
@Transactional
class RefreshTokenServiceIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger(7000);

    @Autowired
    private RefreshTokenService refreshTokenService;
    @Autowired
    private UserRepository users;

    private UUID newUserId() {
        User user = new User();
        user.setPhone("+9197" + String.format("%08d", SEQ.incrementAndGet()));
        user.setRole(UserRole.CUSTOMER);
        return users.saveAndFlush(user).getId();
    }

    @Test
    void issuedTokenCanBeRotatedExactlyOnce() {
        UUID userId = newUserId();
        String raw = refreshTokenService.issue(userId, "device-1");

        RefreshTokenService.Rotated rotated = refreshTokenService.rotate(raw);
        assertThat(rotated.userId()).isEqualTo(userId);
        assertThat(rotated.rawRefreshToken()).isNotEqualTo(raw);

        // The original token is now revoked (rotated); presenting it again is reuse.
        assertThatThrownBy(() -> refreshTokenService.rotate(raw)).isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void reusingARotatedTokenRevokesTheWholeDeviceFamily() {
        UUID userId = newUserId();
        String first = refreshTokenService.issue(userId, "device-1");
        RefreshTokenService.Rotated second = refreshTokenService.rotate(first);

        // Reuse of `first` (already rotated) must revoke `second` too.
        assertThatThrownBy(() -> refreshTokenService.rotate(first)).isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(() -> refreshTokenService.rotate(second.rawRefreshToken()))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void otherDeviceIsUnaffectedByReuseOnAnotherDevice() {
        UUID userId = newUserId();
        String deviceOne = refreshTokenService.issue(userId, "device-1");
        String deviceTwo = refreshTokenService.issue(userId, "device-2");
        refreshTokenService.rotate(deviceOne); // rotate once, legitimately

        assertThatThrownBy(() -> refreshTokenService.rotate(deviceOne)) // now reuse -> revokes device-1 family only
                .isInstanceOf(UnauthorizedException.class);

        RefreshTokenService.Rotated stillGood = refreshTokenService.rotate(deviceTwo);
        assertThat(stillGood.userId()).isEqualTo(userId);
    }

    @Test
    void unknownTokenIsRejected() {
        assertThatThrownBy(() -> refreshTokenService.rotate("not-a-real-token"))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void logoutRevokesOnlyThatToken() {
        UUID userId = newUserId();
        String deviceOne = refreshTokenService.issue(userId, "device-1");
        String deviceTwo = refreshTokenService.issue(userId, "device-2");

        refreshTokenService.revoke(deviceOne);

        assertThatThrownBy(() -> refreshTokenService.rotate(deviceOne)).isInstanceOf(UnauthorizedException.class);
        assertThat(refreshTokenService.rotate(deviceTwo).userId()).isEqualTo(userId);
    }

    @Test
    void revokeAllInvalidatesEveryDevice() {
        UUID userId = newUserId();
        String deviceOne = refreshTokenService.issue(userId, "device-1");
        String deviceTwo = refreshTokenService.issue(userId, "device-2");

        refreshTokenService.revokeAll(userId);

        assertThatThrownBy(() -> refreshTokenService.rotate(deviceOne)).isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(() -> refreshTokenService.rotate(deviceTwo)).isInstanceOf(UnauthorizedException.class);
    }
}
