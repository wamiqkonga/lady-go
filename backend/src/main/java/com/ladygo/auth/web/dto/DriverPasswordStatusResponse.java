package com.ladygo.auth.web.dto;

/** Whether the signed-in driver already has a password, so the website knows whether to ask for one. */
public record DriverPasswordStatusResponse(boolean passwordSet) {
}
