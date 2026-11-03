package com.ladygo.admin.web.dto;

/** For the navigation badge: everything not yet resolved, what nobody has acknowledged yet, and how many are critical. */
public record SafetyOpenCountResponse(long unresolved, long needsAcknowledging, long critical) {
}
