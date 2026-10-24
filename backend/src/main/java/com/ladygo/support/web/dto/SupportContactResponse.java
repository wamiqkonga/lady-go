package com.ladygo.support.web.dto;

/** Support contact numbers; each field is null when not configured. */
public record SupportContactResponse(String phone, String whatsapp) {
}
