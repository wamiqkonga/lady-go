package com.ladygo.notifications.sms;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Entirely generic, no provider-specific field - see docs/development/sms-provider-setup.md. Every
 * value here comes from {@code LADYGO_SMS_*} environment variables in staging/prod (application-
 * staging.yml/application-prod.yml bind them with no default, so a missing one fails startup via
 * Spring's own property resolution); {@link SmsSenderStartupGuard} additionally checks the required
 * ones are non-blank, as defence in depth against a default ever creeping back in.
 *
 * @param baseUrl the provider's send endpoint
 * @param authHeaderName HTTP header carrying the provider credential (e.g. {@code "Authorization"})
 * @param authHeaderValue the credential itself - secret, env-only
 * @param senderId the registered SMS sender id/header the provider requires
 * @param dltTemplateId India DLT template id for the OTP message content template
 * @param dltEntityId India DLT principal entity id
 * @param bodyTemplate the provider's request body, as a template string. Placeholders:
 *     {@code {{to}}}, {@code {{senderId}}}, {@code {{dltTemplateId}}}, {@code {{dltEntityId}}}, and
 *     {@code {{var:KEY}}} for each entry in the caller's variables map (e.g. {@code {{var:code}}}).
 *     This is the one piece of provider-specific shape in the whole adapter, and it lives entirely in
 *     configuration, never in code.
 * @param responseMessageIdField top-level JSON field in a 2xx response holding the provider's message id
 * @param responseErrorCodeField top-level JSON field in a non-2xx response holding a provider error code
 * @param connectTimeoutMs connect timeout
 * @param readTimeoutMs read/response timeout
 * @param maxRetries additional attempts after the first, only on a 5xx response or a timeout/IO error
 * @param retryBaseDelayMs base delay for exponential backoff between retries
 * @param circuitBreakerFailureThreshold consecutive failures before the breaker opens (fail-fast)
 * @param circuitBreakerCooldownMs how long the breaker stays open before allowing one probe attempt
 */
@ConfigurationProperties(prefix = "ladygo.sms.http")
public record SmsSenderProperties(
        String baseUrl,
        String authHeaderName,
        String authHeaderValue,
        String senderId,
        String dltTemplateId,
        String dltEntityId,
        String bodyTemplate,
        String responseMessageIdField,
        String responseErrorCodeField,
        long connectTimeoutMs,
        long readTimeoutMs,
        int maxRetries,
        long retryBaseDelayMs,
        int circuitBreakerFailureThreshold,
        long circuitBreakerCooldownMs) {

    boolean isFullyConfigured() {
        return notBlank(baseUrl) && notBlank(authHeaderName) && notBlank(authHeaderValue)
                && notBlank(senderId) && notBlank(dltTemplateId) && notBlank(dltEntityId) && notBlank(bodyTemplate);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
