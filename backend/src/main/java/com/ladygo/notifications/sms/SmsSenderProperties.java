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
 * @param senderId optional: SMS sender id/header, for providers that need one (blank for WhatsApp)
 * @param dltTemplateId optional: India DLT template id (SMS only; blank for WhatsApp)
 * @param dltEntityId optional: India DLT principal entity id (SMS only; blank for WhatsApp)
 * @param bodyTemplate the provider's request body, as a template string. Placeholders:
 *     {@code {{to}}}, {@code {{senderId}}}, {@code {{dltTemplateId}}}, {@code {{dltEntityId}}}, and
 *     {@code {{var:KEY}}} for each entry in the caller's variables map (e.g. {@code {{var:code}}}).
 *     This is the one piece of provider-specific shape in the whole adapter, and it lives entirely in
 *     configuration, never in code.
 * @param responseMessageIdField JSON field (dot path, numeric segments index arrays) in a 2xx response
 *     holding the provider's message id, e.g. {@code messageId} or {@code messages.0.id}
 * @param responseErrorCodeField JSON field (same dot-path syntax) in a non-2xx response holding a
 *     provider error code, e.g. {@code errorCode} or {@code error.code}
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
        return notBlank(baseUrl) && notBlank(authHeaderName) && notBlank(authHeaderValue) && notBlank(bodyTemplate)
                && placeholderSatisfied("{{senderId}}", senderId)
                && placeholderSatisfied("{{dltTemplateId}}", dltTemplateId)
                && placeholderSatisfied("{{dltEntityId}}", dltEntityId);
    }

    /** The sender/DLT ids are optional (a WhatsApp body template has none) - but if the body template
     *  actually references one, a blank value would silently send an empty field and every OTP would be
     *  rejected at runtime, so that stays a startup failure. */
    private boolean placeholderSatisfied(String placeholder, String value) {
        return !bodyTemplate.contains(placeholder) || notBlank(value);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
