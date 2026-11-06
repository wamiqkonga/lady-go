package com.ladygo.admin.web.dto;

import java.time.Instant;

/**
 * For the navigation badge and the app-wide SOS banner: everything not yet resolved, what nobody has acknowledged
 * yet, how many are critical, and when the oldest unresolved critical incident was raised (null when none).
 */
public record SafetyOpenCountResponse(long unresolved, long needsAcknowledging, long critical, Instant oldestCriticalAt) {
}
