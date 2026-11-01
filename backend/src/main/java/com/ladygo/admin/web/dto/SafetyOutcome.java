package com.ladygo.admin.web.dto;

/** How an incident ended. Mandatory when resolving, so the timeline and the audit log always say what was done. */
public enum SafetyOutcome {
    NO_ACTION_NEEDED,
    RESOLVED_BY_PHONE,
    POLICE_INFORMED,
    ACTION_TAKEN_AGAINST_DRIVER,
    ACTION_TAKEN_AGAINST_RIDER,
    FALSE_ALARM,
    OTHER
}
