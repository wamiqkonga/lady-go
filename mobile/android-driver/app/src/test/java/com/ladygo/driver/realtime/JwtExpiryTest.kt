package com.ladygo.driver.realtime

import com.ladygo.driver.api.generated.infrastructure.Serializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64

class JwtExpiryTest {
    private val moshi = Serializer.moshi

    @Test
    fun readsTheExpClaimFromAWellFormedJwt() {
        val token = fakeJwt("""{"sub":"driver-1","exp":1700000000}""")

        assertEquals(1_700_000_000L, jwtExpiryEpochSeconds(token, moshi))
    }

    @Test
    fun returnsNullForATokenWithTheWrongNumberOfSegments() {
        assertNull(jwtExpiryEpochSeconds("not-a-jwt", moshi))
        assertNull(jwtExpiryEpochSeconds("only.two", moshi))
    }

    @Test
    fun returnsNullWhenThePayloadSegmentIsNotValidBase64() {
        assertNull(jwtExpiryEpochSeconds("header.!!!not-base64!!!.signature", moshi))
    }

    @Test
    fun returnsNullWhenThePayloadHasNoExpClaim() {
        val token = fakeJwt("""{"sub":"driver-1"}""")

        assertNull(jwtExpiryEpochSeconds(token, moshi))
    }

    @Test
    fun handlesUnpaddedBase64UrlPayloads() {
        // Deliberately picks a payload whose base64url encoding has no trailing '=' padding at all,
        // to prove the padding-restoration logic isn't just coincidentally unneeded in other tests.
        val token = fakeJwt("""{"exp":42}""")

        assertEquals(42L, jwtExpiryEpochSeconds(token, moshi))
    }

    private fun fakeJwt(payloadJson: String): String {
        val header = Base64.getUrlEncoder().withoutPadding().encodeToString("""{"alg":"none"}""".toByteArray())
        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString(payloadJson.toByteArray())
        return "$header.$payload.signature"
    }
}
