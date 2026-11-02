package com.ladygo.auth.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** First password for a signed-in driver that has none. Length is checked by the password policy, not here, so the
 *  reply can say why in plain words. */
public record DriverPasswordSetRequest(@NotBlank @Size(max = 1000) String newPassword) {
}
