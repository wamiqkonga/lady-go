package com.ladygo.driver.api.generated.apis

import com.ladygo.driver.api.generated.infrastructure.CollectionFormats.*
import retrofit2.http.*
import retrofit2.Response
import okhttp3.RequestBody
import com.squareup.moshi.Json

import com.ladygo.driver.api.generated.models.ApiResponseBookingDetailResponse
import com.ladygo.driver.api.generated.models.ApiResponseDriverLocationForRiderResponse
import com.ladygo.driver.api.generated.models.ApiResponseEligibilityReportResponse
import com.ladygo.driver.api.generated.models.ApiResponseFareEstimateResponse
import com.ladygo.driver.api.generated.models.ApiResponsePageResponseBookingDetailResponse
import com.ladygo.driver.api.generated.models.ApiResponseTripShareCreatedResponse
import com.ladygo.driver.api.generated.models.ApiResponseVoid
import com.ladygo.driver.api.generated.models.BookingRequest
import com.ladygo.driver.api.generated.models.CancelBookingRequest
import com.ladygo.driver.api.generated.models.DestinationChangeEstimateRequest
import com.ladygo.driver.api.generated.models.DestinationChangeRequest
import com.ladygo.driver.api.generated.models.DisputeCashRequest
import com.ladygo.driver.api.generated.models.EligibilityReportRequest
import com.ladygo.driver.api.generated.models.EndRideEarlyRequest
import com.ladygo.driver.api.generated.models.FareEstimateRequest
import com.ladygo.driver.api.generated.models.Pageable
import com.ladygo.driver.api.generated.models.RideFlagRespondRequest

import com.ladygo.driver.api.generated.models.*

interface BookingControllerApi {
    /**
     * POST api/v1/bookings/{id}/cancel
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @param id 
     * @param cancelBookingRequest 
     * @return [ApiResponseBookingDetailResponse]
     */
    @POST("api/v1/bookings/{id}/cancel")
    suspend fun cancel(@Path("id") id: java.util.UUID, @Body cancelBookingRequest: CancelBookingRequest): Response<ApiResponseBookingDetailResponse>

    /**
     * POST api/v1/bookings/{id}/cancel-safety-concern
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @param id 
     * @param cancelBookingRequest 
     * @return [ApiResponseBookingDetailResponse]
     */
    @POST("api/v1/bookings/{id}/cancel-safety-concern")
    suspend fun cancelForSafetyConcern(@Path("id") id: java.util.UUID, @Body cancelBookingRequest: CancelBookingRequest): Response<ApiResponseBookingDetailResponse>

    /**
     * POST api/v1/bookings/{id}/change-destination
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @param id 
     * @param destinationChangeRequest 
     * @return [ApiResponseBookingDetailResponse]
     */
    @POST("api/v1/bookings/{id}/change-destination")
    suspend fun changeDestination(@Path("id") id: java.util.UUID, @Body destinationChangeRequest: DestinationChangeRequest): Response<ApiResponseBookingDetailResponse>

    /**
     * POST api/v1/bookings/{id}/confirm-arrival
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @param id 
     * @return [ApiResponseBookingDetailResponse]
     */
    @POST("api/v1/bookings/{id}/confirm-arrival")
    suspend fun confirmArrival(@Path("id") id: java.util.UUID): Response<ApiResponseBookingDetailResponse>

    /**
     * POST api/v1/bookings
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @param idempotencyKey 
     * @param bookingRequest 
     * @return [ApiResponseBookingDetailResponse]
     */
    @POST("api/v1/bookings")
    suspend fun create(@Header("Idempotency-Key") idempotencyKey: kotlin.String, @Body bookingRequest: BookingRequest): Response<ApiResponseBookingDetailResponse>

    /**
     * POST api/v1/bookings/{id}/trip-shares
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @param id 
     * @return [ApiResponseTripShareCreatedResponse]
     */
    @POST("api/v1/bookings/{id}/trip-shares")
    suspend fun createTripShare(@Path("id") id: java.util.UUID): Response<ApiResponseTripShareCreatedResponse>

    /**
     * POST api/v1/bookings/{id}/dispute-cash
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @param id 
     * @param disputeCashRequest 
     * @return [ApiResponseBookingDetailResponse]
     */
    @POST("api/v1/bookings/{id}/dispute-cash")
    suspend fun disputeCash(@Path("id") id: java.util.UUID, @Body disputeCashRequest: DisputeCashRequest): Response<ApiResponseBookingDetailResponse>

    /**
     * GET api/v1/bookings/{id}/driver-location
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @param id 
     * @return [ApiResponseDriverLocationForRiderResponse]
     */
    @GET("api/v1/bookings/{id}/driver-location")
    suspend fun driverLocation(@Path("id") id: java.util.UUID): Response<ApiResponseDriverLocationForRiderResponse>

    /**
     * POST api/v1/bookings/{id}/end-early
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @param id 
     * @param endRideEarlyRequest 
     * @return [ApiResponseBookingDetailResponse]
     */
    @POST("api/v1/bookings/{id}/end-early")
    suspend fun endEarly1(@Path("id") id: java.util.UUID, @Body endRideEarlyRequest: EndRideEarlyRequest): Response<ApiResponseBookingDetailResponse>

    /**
     * POST api/v1/bookings/estimate
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @param fareEstimateRequest 
     * @return [ApiResponseFareEstimateResponse]
     */
    @POST("api/v1/bookings/estimate")
    suspend fun estimate(@Body fareEstimateRequest: FareEstimateRequest): Response<ApiResponseFareEstimateResponse>

    /**
     * POST api/v1/bookings/{id}/estimate-destination-change
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @param id 
     * @param destinationChangeEstimateRequest 
     * @return [ApiResponseFareEstimateResponse]
     */
    @POST("api/v1/bookings/{id}/estimate-destination-change")
    suspend fun estimateDestinationChange(@Path("id") id: java.util.UUID, @Body destinationChangeEstimateRequest: DestinationChangeEstimateRequest): Response<ApiResponseFareEstimateResponse>

    /**
     * GET api/v1/bookings/{id}
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @param id 
     * @return [ApiResponseBookingDetailResponse]
     */
    @GET("api/v1/bookings/{id}")
    suspend fun get1(@Path("id") id: java.util.UUID): Response<ApiResponseBookingDetailResponse>

    /**
     * GET api/v1/bookings
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @param page  (optional)
     * @param size  (optional)
     * @param sort  (optional)
     * @return [ApiResponsePageResponseBookingDetailResponse]
     */
    @GET("api/v1/bookings")
    suspend fun history(@Query("page") page: kotlin.Int? = null, @Query("size") size: kotlin.Int? = null, @Query("sort") sort: kotlin.collections.List<kotlin.String>? = null): Response<ApiResponsePageResponseBookingDetailResponse>

    /**
     * POST api/v1/bookings/{id}/eligibility-report
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @param id 
     * @param eligibilityReportRequest 
     * @return [ApiResponseEligibilityReportResponse]
     */
    @POST("api/v1/bookings/{id}/eligibility-report")
    suspend fun reportEligibilityConcern(@Path("id") id: java.util.UUID, @Body eligibilityReportRequest: EligibilityReportRequest): Response<ApiResponseEligibilityReportResponse>

    /**
     * POST api/v1/bookings/{id}/ride-flags/{flagId}/respond
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @param id 
     * @param flagId 
     * @param rideFlagRespondRequest 
     * @return [ApiResponseVoid]
     */
    @POST("api/v1/bookings/{id}/ride-flags/{flagId}/respond")
    suspend fun respondToFlag(@Path("id") id: java.util.UUID, @Path("flagId") flagId: java.util.UUID, @Body rideFlagRespondRequest: RideFlagRespondRequest): Response<ApiResponseVoid>

    /**
     * DELETE api/v1/bookings/{id}/trip-shares/{shareId}
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @param id 
     * @param shareId 
     * @return [ApiResponseVoid]
     */
    @DELETE("api/v1/bookings/{id}/trip-shares/{shareId}")
    suspend fun revokeTripShare(@Path("id") id: java.util.UUID, @Path("shareId") shareId: java.util.UUID): Response<ApiResponseVoid>

}
