package com.ladygo.driver.ride

import com.ladygo.driver.BuildConfig
import com.ladygo.driver.api.generated.apis.DriverBookingControllerApi
import com.ladygo.driver.di.PlainClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import retrofit2.HttpException
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Raw image bytes; deliberately not a data class so a large array is never hashed/compared by value. */
class SelfieImage(
    val bytes: ByteArray,
)

sealed interface SelfieOutcome {
    data class Loaded(
        val image: SelfieImage,
    ) : SelfieOutcome

    data object Unavailable : SelfieOutcome
}

/**
 * The rider's verification selfie for the ARRIVED stage (design doc §1/§4). The backend hands out a
 * short-TTL signed URL to a watermarked derivative; this fetches it once into memory - never to disk,
 * never cached past the screen - and refuses anything that isn't plain http(s). The signed URL is
 * never logged.
 */
@Singleton
class RiderSelfieRepository
    @Inject
    constructor(
        private val bookingApi: DriverBookingControllerApi,
        @PlainClient private val httpClient: OkHttpClient,
    ) {
        suspend fun load(bookingId: UUID): SelfieOutcome =
            withContext(Dispatchers.IO) {
                try {
                    val response = bookingApi.riderSelfie(bookingId)
                    val uri = response.body()?.data
                    val url = if (response.isSuccessful && uri != null) safeImageUrl(uri.toString()) else null
                    if (url == null) SelfieOutcome.Unavailable else download(url)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: IOException) {
                    SelfieOutcome.Unavailable
                } catch (_: HttpException) {
                    SelfieOutcome.Unavailable
                } catch (_: RuntimeException) {
                    // A malformed envelope (Moshi) or URI must degrade to "photo unavailable", never crash mid-ride.
                    SelfieOutcome.Unavailable
                }
            }

        /** Resolves a possibly-relative signed URL against the API base; only plain http(s) is accepted. */
        private fun safeImageUrl(raw: String): HttpUrl? {
            val url = BuildConfig.API_BASE_URL.toHttpUrlOrNull()?.resolve(raw) ?: raw.toHttpUrlOrNull()
            return url?.takeIf { it.scheme == "https" || it.scheme == "http" }
        }

        private fun download(url: HttpUrl): SelfieOutcome =
            httpClient.newCall(Request.Builder().url(url).build()).execute().use { imageResponse ->
                val bytes = if (imageResponse.isSuccessful) imageResponse.body?.let(::readBounded) else null
                if (bytes == null || bytes.isEmpty()) SelfieOutcome.Unavailable else SelfieOutcome.Loaded(SelfieImage(bytes))
            }

        /** Reads at most [MAX_SELFIE_BYTES]; a larger body is treated as unavailable rather than buffered. */
        private fun readBounded(body: ResponseBody): ByteArray? {
            if (body.contentLength() > MAX_SELFIE_BYTES) return null
            val source = body.source()
            source.request(MAX_SELFIE_BYTES + 1)
            return if (source.buffer.size > MAX_SELFIE_BYTES) null else source.buffer.readByteArray()
        }

        private companion object {
            const val MAX_SELFIE_BYTES = 2L * 1024 * 1024
        }
    }
