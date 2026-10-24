package com.ladygo.driver.network

import com.ladygo.driver.api.generated.infrastructure.Serializer
import com.ladygo.driver.api.generated.models.ApiError
import retrofit2.Response

sealed interface ApiResult<out T> {
    data class Success<T>(
        val value: T,
    ) : ApiResult<T>

    data class Failure(
        val httpStatus: Int,
        val code: String,
        val message: String,
    ) : ApiResult<Nothing>
}

/** Thrown only where a caller needs an exception rather than an [ApiResult] to branch on (e.g. inside
 *  a `runCatching` block feeding a UI error state). */
class ApiException(
    val httpStatus: Int,
    val code: String,
    message: String,
) : Exception(message)

private data class ErrorEnvelope(
    val error: ApiError? = null,
)

private val errorEnvelopeAdapter by lazy { Serializer.moshi.adapter(ErrorEnvelope::class.java) }

/**
 * Every endpoint in this backend wraps its body in the same `{success, data, error}` envelope
 * (docs/api/openapi.json's `ApiResponse*` schemas) - this turns a Retrofit [Response] into one
 * [ApiResult], the same success/data/error unwrapping every endpoint needs, without assuming the
 * server never sends a non-2xx status with no parseable body at all (a plain network/proxy error
 * page, for instance).
 */
fun <T, R> Response<T>.toApiResult(dataOf: (T) -> R?): ApiResult<R?> {
    if (isSuccessful) {
        val body = body()
        return ApiResult.Success(body?.let(dataOf))
    }
    val parsedError = parseErrorBody(this)
    return ApiResult.Failure(
        httpStatus = code(),
        code = parsedError?.code ?: "UNKNOWN_ERROR",
        message = parsedError?.message ?: "Request failed with status ${code()}.",
    )
}

/** Best-effort parse of a non-2xx response's `{error: {code, message}}` body. Returns null for a
 *  response with no parseable body at all (a plain network/proxy error page, for instance) rather
 *  than throwing - callers already have a status code to fall back to a generic message with. */
fun parseErrorBody(response: Response<*>): ApiError? {
    val raw = response.errorBody()?.string() ?: return null
    return runCatching { errorEnvelopeAdapter.fromJson(raw) }.getOrNull()?.error
}

/** Convenience for call sites that just want a displayable string, not the full [ApiError]. */
fun errorMessageOrDefault(
    response: Response<*>,
    default: String,
): String = parseErrorBody(response)?.message ?: default
