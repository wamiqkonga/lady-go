package com.ladygo.customer.api.generated.apis

import com.ladygo.customer.api.generated.infrastructure.CollectionFormats.*
import retrofit2.http.*
import retrofit2.Response
import okhttp3.RequestBody
import com.squareup.moshi.Json

import com.ladygo.customer.api.generated.models.AdminRideFlagReviewRequest
import com.ladygo.customer.api.generated.models.ApiResponseAdminRideDetailResponse
import com.ladygo.customer.api.generated.models.ApiResponseAdminRideFlagResponse
import com.ladygo.customer.api.generated.models.ApiResponsePageResponseAdminRideFlagResponse
import com.ladygo.customer.api.generated.models.ApiResponsePageResponseAdminRideSummaryResponse
import com.ladygo.customer.api.generated.models.Pageable

import com.ladygo.customer.api.generated.models.*

interface AdminRideControllerApi {
    /**
     * GET api/v1/admin/rides/{bookingId}
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @param bookingId 
     * @return [ApiResponseAdminRideDetailResponse]
     */
    @GET("api/v1/admin/rides/{bookingId}")
    suspend fun getRide(@Path("bookingId") bookingId: java.util.UUID): Response<ApiResponseAdminRideDetailResponse>


    /**
    * enum for parameter type
    */
    enum class TypeListFlags(val value: kotlin.String) {
        @Json(name = "EARLY_END") EARLY_END("EARLY_END"),
        @Json(name = "COMPLETION_FAR_FROM_DESTINATION") COMPLETION_FAR_FROM_DESTINATION("COMPLETION_FAR_FROM_DESTINATION"),
        @Json(name = "PIN_LOCKOUT") PIN_LOCKOUT("PIN_LOCKOUT"),
        @Json(name = "CASH_DISPUTE") CASH_DISPUTE("CASH_DISPUTE"),
        @Json(name = "RIDER_SAFETY_CANCEL") RIDER_SAFETY_CANCEL("RIDER_SAFETY_CANCEL"),
        @Json(name = "RIDER_CONFIRMED_ARRIVAL") RIDER_CONFIRMED_ARRIVAL("RIDER_CONFIRMED_ARRIVAL")
    }

    /**
     * GET api/v1/admin/ride-flags
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @param page  (optional)
     * @param size  (optional)
     * @param sort  (optional)
     * @param type  (optional)
     * @param reviewed  (optional)
     * @return [ApiResponsePageResponseAdminRideFlagResponse]
     */
    @GET("api/v1/admin/ride-flags")
    suspend fun listFlags(@Query("page") page: kotlin.Int? = null, @Query("size") size: kotlin.Int? = null, @Query("sort") sort: kotlin.collections.List<kotlin.String>? = null, @Query("type") type: TypeListFlags? = null, @Query("reviewed") reviewed: kotlin.Boolean? = null): Response<ApiResponsePageResponseAdminRideFlagResponse>


    /**
    * enum for parameter status
    */
    enum class StatusListRides(val value: kotlin.String) {
        @Json(name = "REQUESTED") REQUESTED("REQUESTED"),
        @Json(name = "SEARCHING_DRIVER") SEARCHING_DRIVER("SEARCHING_DRIVER"),
        @Json(name = "DRIVER_ASSIGNED") DRIVER_ASSIGNED("DRIVER_ASSIGNED"),
        @Json(name = "DRIVER_ACCEPTED") DRIVER_ACCEPTED("DRIVER_ACCEPTED"),
        @Json(name = "DRIVER_ARRIVING") DRIVER_ARRIVING("DRIVER_ARRIVING"),
        @Json(name = "DRIVER_ARRIVED") DRIVER_ARRIVED("DRIVER_ARRIVED"),
        @Json(name = "RIDE_STARTED") RIDE_STARTED("RIDE_STARTED"),
        @Json(name = "RIDE_COMPLETED") RIDE_COMPLETED("RIDE_COMPLETED"),
        @Json(name = "PAYMENT_PENDING") PAYMENT_PENDING("PAYMENT_PENDING"),
        @Json(name = "COMPLETED") COMPLETED("COMPLETED"),
        @Json(name = "CANCELLED") CANCELLED("CANCELLED")
    }

    /**
     * GET api/v1/admin/rides
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @param page  (optional)
     * @param size  (optional)
     * @param sort  (optional)
     * @param status  (optional)
     * @param driverId  (optional)
     * @param customerId  (optional)
     * @param createdAfter  (optional)
     * @param createdBefore  (optional)
     * @param hasOpenFlags  (optional)
     * @return [ApiResponsePageResponseAdminRideSummaryResponse]
     */
    @GET("api/v1/admin/rides")
    suspend fun listRides(@Query("page") page: kotlin.Int? = null, @Query("size") size: kotlin.Int? = null, @Query("sort") sort: kotlin.collections.List<kotlin.String>? = null, @Query("status") status: StatusListRides? = null, @Query("driverId") driverId: java.util.UUID? = null, @Query("customerId") customerId: java.util.UUID? = null, @Query("createdAfter") createdAfter: kotlin.String? = null, @Query("createdBefore") createdBefore: kotlin.String? = null, @Query("hasOpenFlags") hasOpenFlags: kotlin.Boolean? = null): Response<ApiResponsePageResponseAdminRideSummaryResponse>

    /**
     * POST api/v1/admin/ride-flags/{id}/review
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @param id 
     * @param adminRideFlagReviewRequest 
     * @return [ApiResponseAdminRideFlagResponse]
     */
    @POST("api/v1/admin/ride-flags/{id}/review")
    suspend fun reviewFlag(@Path("id") id: java.util.UUID, @Body adminRideFlagReviewRequest: AdminRideFlagReviewRequest): Response<ApiResponseAdminRideFlagResponse>

}
