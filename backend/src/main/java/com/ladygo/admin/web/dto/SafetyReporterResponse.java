package com.ladygo.admin.web.dto;

/** The person who raised the incident: a name, the role they use the app in and a masked phone (last 4 digits only). */
public record SafetyReporterResponse(String name, String role, String phoneMasked) {
}
