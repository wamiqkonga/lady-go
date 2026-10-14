package com.ladygo.driver.api.generated.apis

import com.ladygo.driver.api.generated.infrastructure.CollectionFormats.*
import retrofit2.http.*
import retrofit2.Response
import okhttp3.RequestBody
import com.squareup.moshi.Json

import com.ladygo.driver.api.generated.models.ApiResponseBookingDetailResponse
import com.ladygo.driver.api.generated.models.ApiResponseURI
import com.ladygo.driver.api.generated.models.CancelBookingRequest

interface DriverBookingControllerApi {
    /**
     * POST api/v1/drivers/me/bookings/{id}/cancel-eligibility-concern
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @param id 
     * @param cancelBookingRequest 
     * @return [ApiResponseBookingDetailResponse]
     */
    @POST("api/v1/drivers/me/bookings/{id}/cancel-eligibility-concern")
    suspend fun cancelForEligibilityConcern(@Path("id") id: java.util.UUID, @Body cancelBookingRequest: CancelBookingRequest): Response<ApiResponseBookingDetailResponse>

    /**
     * GET api/v1/drivers/me/bookings/{id}
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @param id 
     * @return [ApiResponseBookingDetailResponse]
     */
    @GET("api/v1/drivers/me/bookings/{id}")
    suspend fun get(@Path("id") id: java.util.UUID): Response<ApiResponseBookingDetailResponse>

    /**
     * GET api/v1/drivers/me/bookings/{id}/rider-selfie
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @param id 
     * @return [ApiResponseURI]
     */
    @GET("api/v1/drivers/me/bookings/{id}/rider-selfie")
    suspend fun riderSelfie(@Path("id") id: java.util.UUID): Response<ApiResponseURI>

}
