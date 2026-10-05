package com.ladygo.bookings.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;

@ExtendWith(MockitoExtension.class)
class EligibilityPolicyStartupGuardTest {

    @Mock
    private Environment environment;

    private static final EligibilityPolicy PLACEHOLDER_LIKE = new DefaultEligibilityPolicy();
    private static final EligibilityPolicy REAL_LIKE = new EligibilityPolicy() {
        @Override
        public EligibilityDecision checkCustomerEligibility(com.ladygo.customers.domain.Customer customer) {
            return EligibilityDecision.allowed();
        }

        @Override
        public EligibilityDecision checkCompanions(com.ladygo.bookings.web.dto.BookingRequest request) {
            return EligibilityDecision.allowed();
        }
    };

    @Test
    void failsFastWhenPlaceholderIsActiveUnderProd() {
        when(environment.getActiveProfiles()).thenReturn(new String[] {"prod"});
        EligibilityPolicyStartupGuard guard = new EligibilityPolicyStartupGuard(List.of(PLACEHOLDER_LIKE), environment);
        assertThatThrownBy(guard::verifyNoPlaceholderInGuardedProfiles).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void failsFastWhenPlaceholderIsActiveUnderStaging() {
        when(environment.getActiveProfiles()).thenReturn(new String[] {"staging"});
        EligibilityPolicyStartupGuard guard = new EligibilityPolicyStartupGuard(List.of(PLACEHOLDER_LIKE), environment);
        assertThatThrownBy(guard::verifyNoPlaceholderInGuardedProfiles).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void passesUnderProdWhenOnlyARealPolicyIsPresent() {
        when(environment.getActiveProfiles()).thenReturn(new String[] {"prod"});
        EligibilityPolicyStartupGuard guard = new EligibilityPolicyStartupGuard(List.of(REAL_LIKE), environment);
        assertThatCode(guard::verifyNoPlaceholderInGuardedProfiles).doesNotThrowAnyException();
    }

    @Test
    void passesUnderDevEvenWithThePlaceholderPresent() {
        when(environment.getActiveProfiles()).thenReturn(new String[] {"dev"});
        EligibilityPolicyStartupGuard guard = new EligibilityPolicyStartupGuard(List.of(PLACEHOLDER_LIKE), environment);
        assertThatCode(guard::verifyNoPlaceholderInGuardedProfiles).doesNotThrowAnyException();
    }
}
