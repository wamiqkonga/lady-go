package com.ladygo.driver.realtime

import com.squareup.moshi.Moshi
import java.util.Base64

private data class JwtExpClaim(
    val exp: Long? = null,
)

/**
 * Reads the `exp` claim out of a JWT's payload segment, without verifying the signature - this is
 * only ever used client-side to decide whether to refresh *proactively* before it expires (see
 * [TokenRefresher]/[StompConnectionManager]). The server independently verifies every token on every
 * real request; a forged or tampered `exp` here can only make this client refresh too early or too
 * late, never bypass server-side auth.
 *
 * Returns null for anything that doesn't parse as a three-segment JWT with a decodable `exp` claim -
 * callers treat that the same as "unknown expiry, refresh to be safe."
 */
fun jwtExpiryEpochSeconds(
    token: String,
    moshi: Moshi,
): Long? {
    val segments = token.split(".")
    if (segments.size != 3) return null
    val payloadJson =
        runCatching { String(Base64.getUrlDecoder().decode(segments[1].padBase64Url())) }.getOrNull() ?: return null
    return runCatching { moshi.adapter(JwtExpClaim::class.java).fromJson(payloadJson)?.exp }.getOrNull()
}

/** JWT base64url segments omit padding; [Base64.getUrlDecoder] requires it. */
private fun String.padBase64Url(): String {
    val remainder = length % 4
    return if (remainder == 0) this else this + "=".repeat(4 - remainder)
}
