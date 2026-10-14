package com.ladygo.auth.challenge;

import org.springframework.stereotype.Component;

/**
 * Placeholder {@link ChallengeVerifier} - only checks a token is present, which is not real bot
 * protection. Only ever consulted at all when {@code otp.challenge.enabled} is turned on (default
 * false everywhere - see {@code OtpLoginService}); the whole hook is a no-op by default. Replace this
 * bean with a real provider adapter when one is chosen - nothing else needs to change.
 */
@Component
public class NoOpChallengeVerifier implements ChallengeVerifier {

    @Override
    public boolean verify(String challengeToken) {
        return challengeToken != null && !challengeToken.isBlank();
    }
}
