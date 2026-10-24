package com.ladygo.customer.booking

import com.ladygo.customer.api.generated.models.ApiResponseRideSelfieResponse
import okhttp3.MultipartBody
import retrofit2.Response
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part

/**
 * Hand-written because the generated `CustomerControllerApi.captureRideSelfie` is wrong for this
 * endpoint: the OpenAPI spec describes the multipart upload as an `application/json` body, so the
 * generator emits a JSON `@Body` instead of a `@Part`. (The web apps hit the same thing and send
 * `FormData` by hand.) Proper fix is `consumes = MULTIPART_FORM_DATA` on the controller - backlog.
 */
interface RideSelfieApi {
    @Multipart
    @POST("api/v1/customers/me/ride-selfies")
    suspend fun upload(
        @Part file: MultipartBody.Part,
    ): Response<ApiResponseRideSelfieResponse>
}
