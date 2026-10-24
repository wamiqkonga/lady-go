package com.ladygo.customer.api.generated.apis

import com.ladygo.customer.api.generated.infrastructure.CollectionFormats.*
import retrofit2.http.*
import retrofit2.Response
import okhttp3.RequestBody
import com.squareup.moshi.Json

import com.ladygo.customer.api.generated.models.ApiResponseBookingDetailResponse
import com.ladygo.customer.api.generated.models.EndRideEarlyRequest
import com.ladygo.customer.api.generated.models.StartRideRequest

interface RideLifecycleControllerApi {
    /**
     * POST api/v1/drivers/me/rides/{id}/arrived
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @param id 
     * @return [ApiResponseBookingDetailResponse]
     */
    @POST("api/v1/drivers/me/rides/{id}/arrived")
    suspend fun arrived(@Path("id") id: java.util.UUID): Response<ApiResponseBookingDetailResponse>

    /**
     * POST api/v1/drivers/me/rides/{id}/arriving
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @param id 
     * @return [ApiResponseBookingDetailResponse]
     */
    @POST("api/v1/drivers/me/rides/{id}/arriving")
    suspend fun arriving(@Path("id") id: java.util.UUID): Response<ApiResponseBookingDetailResponse>

    /**
     * POST api/v1/drivers/me/rides/{id}/complete
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @param id 
     * @return [ApiResponseBookingDetailResponse]
     */
    @POST("api/v1/drivers/me/rides/{id}/complete")
    suspend fun complete(@Path("id") id: java.util.UUID): Response<ApiResponseBookingDetailResponse>

    /**
     * POST api/v1/drivers/me/rides/{id}/confirm-cash-collected
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @param id 
     * @return [ApiResponseBookingDetailResponse]
     */
    @POST("api/v1/drivers/me/rides/{id}/confirm-cash-collected")
    suspend fun confirmCashCollected(@Path("id") id: java.util.UUID): Response<ApiResponseBookingDetailResponse>

    /**
     * POST api/v1/drivers/me/rides/{id}/end-early
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @param id 
     * @param endRideEarlyRequest 
     * @return [ApiResponseBookingDetailResponse]
     */
    @POST("api/v1/drivers/me/rides/{id}/end-early")
    suspend fun endEarly(@Path("id") id: java.util.UUID, @Body endRideEarlyRequest: EndRideEarlyRequest): Response<ApiResponseBookingDetailResponse>

    /**
     * POST api/v1/drivers/me/rides/{id}/start
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @param id 
     * @param startRideRequest 
     * @return [ApiResponseBookingDetailResponse]
     */
    @POST("api/v1/drivers/me/rides/{id}/start")
    suspend fun start(@Path("id") id: java.util.UUID, @Body startRideRequest: StartRideRequest): Response<ApiResponseBookingDetailResponse>

}
