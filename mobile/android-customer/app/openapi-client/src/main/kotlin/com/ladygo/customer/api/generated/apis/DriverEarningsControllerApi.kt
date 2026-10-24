package com.ladygo.customer.api.generated.apis

import com.ladygo.customer.api.generated.infrastructure.CollectionFormats.*
import retrofit2.http.*
import retrofit2.Response
import okhttp3.RequestBody
import com.squareup.moshi.Json

import com.ladygo.customer.api.generated.models.ApiResponseDriverEarningsResponse
import com.ladygo.customer.api.generated.models.ApiResponsePageResponseDriverRideHistoryItemResponse
import com.ladygo.customer.api.generated.models.Pageable

import com.ladygo.customer.api.generated.models.*

interface DriverEarningsControllerApi {
    /**
     * GET api/v1/drivers/me/earnings
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @param from  (optional)
     * @param to  (optional)
     * @return [ApiResponseDriverEarningsResponse]
     */
    @GET("api/v1/drivers/me/earnings")
    suspend fun getMyEarnings(@Query("from") from: kotlin.String? = null, @Query("to") to: kotlin.String? = null): Response<ApiResponseDriverEarningsResponse>

    /**
     * GET api/v1/drivers/me/rides
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @param page  (optional)
     * @param size  (optional)
     * @param sort  (optional)
     * @return [ApiResponsePageResponseDriverRideHistoryItemResponse]
     */
    @GET("api/v1/drivers/me/rides")
    suspend fun listMyRides(@Query("page") page: kotlin.Int? = null, @Query("size") size: kotlin.Int? = null, @Query("sort") sort: kotlin.collections.List<kotlin.String>? = null): Response<ApiResponsePageResponseDriverRideHistoryItemResponse>

}
