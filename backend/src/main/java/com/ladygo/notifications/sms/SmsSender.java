package com.ladygo.notifications.sms;

import java.util.Map;

/**
 * Provider-agnostic SMS delivery SPI. Nothing outside this package talks to a carrier/aggregator
 * directly - callers name a template (e.g. {@code "otp_code"}) and supply the variables it needs;
 * which provider, request shape, DLT template/entity ids and sender id apply is entirely a matter of
 * which implementation is wired and how it's configured (see {@link HttpSmsSender}), never a matter of
 * which provider-specific class a caller imports.
 *
 * <p>Two implementations: {@link LoggingSmsSender} (dev/test only) and {@link HttpSmsSender} (the
 * real, generic HTTP-based adapter). {@link SmsSenderStartupGuard} refuses to start staging/prod with
 * the former active or the latter misconfigured.
 */
public interface SmsSender {

    SmsDeliveryResult send(String toE164, String templateKey, Map<String, String> variables);
}
