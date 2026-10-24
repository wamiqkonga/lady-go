package com.ladygo.customer.api.generated.apis

import com.ladygo.customer.api.generated.infrastructure.CollectionFormats.*
import retrofit2.http.*
import retrofit2.Response
import okhttp3.RequestBody
import com.squareup.moshi.Json

import com.ladygo.customer.api.generated.models.ApiResponseDriverRatingSummaryResponse
import com.ladygo.customer.api.generated.models.ApiResponseRatingResponse
import com.ladygo.customer.api.generated.models.SubmitRatingRequest

interface RatingControllerApi {
    /**
     * GET api/v1/drivers/me/ratings/summary
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @return [ApiResponseDriverRatingSummaryResponse]
     */
    @GET("api/v1/drivers/me/ratings/summary")
    suspend fun getMyRatingSummary(): Response<ApiResponseDriverRatingSummaryResponse>

    /**
     * POST api/v1/bookings/{bookingId}/rating
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @param bookingId 
     * @param submitRatingRequest 
     * @return [ApiResponseRatingResponse]
     */
    @POST("api/v1/bookings/{bookingId}/rating")
    suspend fun rateDriver(@Path("bookingId") bookingId: java.util.UUID, @Body submitRatingRequest: SubmitRatingRequest): Response<ApiResponseRatingResponse>

    /**
     * POST api/v1/drivers/me/bookings/{bookingId}/rating
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @param bookingId 
     * @param submitRatingRequest 
     * @return [ApiResponseRatingResponse]
     */
    @POST("api/v1/drivers/me/bookings/{bookingId}/rating")
    suspend fun rateRider(@Path("bookingId") bookingId: java.util.UUID, @Body submitRatingRequest: SubmitRatingRequest): Response<ApiResponseRatingResponse>

}
