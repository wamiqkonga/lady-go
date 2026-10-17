package com.ladygo.notifications.sms;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Generic, provider-agnostic SMS adapter: everything about the target provider (endpoint, auth
 * header, request body shape, sender id, DLT ids) comes from {@link SmsSenderProperties}, never from
 * code specific to any carrier. Choosing MSG91, Twilio, Kaleyra, etc. later means filling in
 * {@code LADYGO_SMS_*} env vars (docs/development/sms-provider-setup.md), not writing a new class.
 *
 * <p>Reliability (docs/development/sms-provider-setup.md section "Reliability"): bounded retry with
 * exponential backoff, but ONLY on a 5xx response or a timeout/IO error - a 4xx means the request
 * itself is wrong (bad number, bad template id, auth failure) and retrying it would just repeat the
 * same failure while burning the retry budget and the thread's time. A {@link SimpleCircuitBreaker}
 * sits in front of the retry loop so that once the provider has clearly stopped responding, later
 * calls fail immediately with no HTTP attempt at all - the actual mechanism protecting request
 * threads from a saturated/dead provider, which bounded retries alone do not do (a thread would still
 * block through every retry's own timeout on every single request).
 *
 * <p>Never logs the rendered request body (it contains the message content, e.g. the OTP code) or a
 * full phone number - only a last-4-digit suffix, the template key, and the outcome. This is the path
 * that actually runs in staging/prod; {@link LoggingSmsSender} is the dev/test-only exception that
 * logs in full, by design, because there is no real carrier there to protect secrets from.
 */
@Component
@Profile("!dev & !test")
@EnableConfigurationProperties(SmsSenderProperties.class)
public class HttpSmsSender implements SmsSender {

    private static final Logger log = LoggerFactory.getLogger(HttpSmsSender.class);

    private final SmsSenderProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final SimpleCircuitBreaker circuitBreaker;

    public HttpSmsSender(SmsSenderProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, Clock.systemUTC());
    }

    HttpSmsSender(SmsSenderProperties properties, ObjectMapper objectMapper, Clock clock) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(properties.connectTimeoutMs(), 1)))
                .build();
        this.circuitBreaker = new SimpleCircuitBreaker(
                Math.max(properties.circuitBreakerFailureThreshold(), 1),
                Duration.ofMillis(Math.max(properties.circuitBreakerCooldownMs(), 1)),
                clock);
    }

    @Override
    public SmsDeliveryResult send(String toE164, String templateKey, Map<String, String> variables) {
        if (!circuitBreaker.allowRequest()) {
            log.warn("operation=sms_send result=circuit_open phone_suffix={} templateKey={}",
                    suffix(toE164), templateKey);
            return SmsDeliveryResult.failed("CIRCUIT_OPEN");
        }

        String body = renderBody(toE164, variables);
        int attempts = 1 + Math.max(properties.maxRetries(), 0);
        SmsDeliveryResult result = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            Attempt outcome = attemptSend(body);
            if (outcome.retryable && attempt < attempts) {
                log.warn("operation=sms_send result=retrying attempt={} phone_suffix={} templateKey={} status={}",
                        attempt, suffix(toE164), templateKey, outcome.result.errorCode());
                sleepBackoff(attempt);
                continue;
            }
            result = outcome.result;
            break;
        }

        if (result.accepted()) {
            circuitBreaker.recordSuccess();
        } else {
            circuitBreaker.recordFailure();
        }
        log.info("operation=sms_send result={} phone_suffix={} templateKey={} errorCode={}",
                result.accepted() ? "accepted" : "failed", suffix(toE164), templateKey, result.errorCode());
        return result;
    }

    private record Attempt(SmsDeliveryResult result, boolean retryable) {
    }

    private Attempt attemptSend(String body) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(properties.baseUrl()))
                .timeout(Duration.ofMillis(Math.max(properties.readTimeoutMs(), 1)))
                .header("Content-Type", "application/json")
                .header(properties.authHeaderName(), properties.authHeaderValue())
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                return new Attempt(SmsDeliveryResult.accepted(extractField(response.body(), properties.responseMessageIdField())), false);
            }
            String errorCode = extractField(response.body(), properties.responseErrorCodeField());
            String code = errorCode != null ? errorCode : "HTTP_" + status;
            boolean serverError = status >= 500;
            return new Attempt(SmsDeliveryResult.failed(code), serverError);
        } catch (HttpTimeoutException ex) {
            return new Attempt(SmsDeliveryResult.failed("TIMEOUT"), true);
        } catch (IOException ex) {
            return new Attempt(SmsDeliveryResult.failed("IO_ERROR"), true);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new Attempt(SmsDeliveryResult.failed("INTERRUPTED"), false);
        }
    }

    /** {@code fieldName} is a dot path into the JSON response, with numeric segments indexing arrays
     *  (e.g. {@code messages.0.id} for WhatsApp Cloud API); a plain name is a top-level field. */
    private String extractField(String responseBody, String fieldName) {
        if (fieldName == null || fieldName.isBlank() || responseBody == null || responseBody.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(responseBody);
            for (String segment : fieldName.split("\\.")) {
                if (node == null) {
                    return null;
                }
                Integer index = node.isArray() ? parseIndex(segment) : null;
                node = index != null ? node.get(index) : node.get(segment);
            }
            return node == null || node.isNull() || node.isContainerNode() ? null : node.asText();
        } catch (IOException ex) {
            return null;
        }
    }

    private static Integer parseIndex(String segment) {
        try {
            return Integer.valueOf(segment);
        } catch (NumberFormatException ex) {
            return null; // not an array index (or absurdly large) - treated as "not found" by node.get
        }
    }

    /** The one place provider-specific request shape enters the picture - entirely via config, never
     *  code. Values are escaped as JSON string content before substitution (see the class javadoc on
     *  {@link SmsSenderProperties#bodyTemplate} - this adapter assumes a JSON-shaped body template). */
    private String renderBody(String toE164, Map<String, String> variables) {
        String rendered = properties.bodyTemplate()
                .replace("{{to}}", escapeJson(toE164))
                .replace("{{senderId}}", escapeJson(properties.senderId()))
                .replace("{{dltTemplateId}}", escapeJson(properties.dltTemplateId()))
                .replace("{{dltEntityId}}", escapeJson(properties.dltEntityId()));
        for (var entry : variables.entrySet()) {
            rendered = rendered.replace("{{var:" + entry.getKey() + "}}", escapeJson(entry.getValue()));
        }
        return rendered;
    }

    private static String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    private void sleepBackoff(int attempt) {
        try {
            long delay = properties.retryBaseDelayMs() * (1L << (attempt - 1));
            Thread.sleep(Math.max(delay, 0));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static String suffix(String phone) {
        return phone != null && phone.length() > 4 ? phone.substring(phone.length() - 4) : "----";
    }
}
