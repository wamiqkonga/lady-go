package com.ladygo.auth.web.dto;

/** Admin web only - the refresh token travels in an HttpOnly cookie, never in this body
 *  (docs/product/admin-web-design.md section 7). */
public record AdminAccessTokenResponse(String accessToken) {
}
