package com.ladygo.customer.api.generated.apis

import com.ladygo.customer.api.generated.infrastructure.CollectionFormats.*
import retrofit2.http.*
import retrofit2.Response
import okhttp3.RequestBody
import com.squareup.moshi.Json

import com.ladygo.customer.api.generated.models.ApiResponseAdminLiveMapResponse

interface AdminLiveMapControllerApi {
    /**
     * GET api/v1/admin/live-map
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @return [ApiResponseAdminLiveMapResponse]
     */
    @GET("api/v1/admin/live-map")
    suspend fun snapshot(): Response<ApiResponseAdminLiveMapResponse>

}
