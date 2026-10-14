package com.ladygo.driver.api.generated.apis

import com.ladygo.driver.api.generated.infrastructure.CollectionFormats.*
import retrofit2.http.*
import retrofit2.Response
import okhttp3.RequestBody
import com.squareup.moshi.Json

import com.ladygo.driver.api.generated.models.ApiResponseTripShareLookupResponse

interface TripShareLookupControllerApi {
    /**
     * GET api/v1/trip-shares/lookup
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @param xTripShareToken  (optional)
     * @return [ApiResponseTripShareLookupResponse]
     */
    @GET("api/v1/trip-shares/lookup")
    suspend fun lookup(@Header("X-Trip-Share-Token") xTripShareToken: kotlin.String? = null): Response<ApiResponseTripShareLookupResponse>

}
