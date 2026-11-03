package com.ladygo.driver.api.generated.apis

import com.ladygo.driver.api.generated.infrastructure.CollectionFormats.*
import retrofit2.http.*
import retrofit2.Response
import okhttp3.RequestBody
import com.squareup.moshi.Json

import com.ladygo.driver.api.generated.models.AdminDriverActionRequest
import com.ladygo.driver.api.generated.models.ApiResponseAdminDriverDetailResponse
import com.ladygo.driver.api.generated.models.ApiResponsePageResponseAdminDriverListItemResponse
import com.ladygo.driver.api.generated.models.ApiResponseVoid
import com.ladygo.driver.api.generated.models.Pageable

import com.ladygo.driver.api.generated.models.*

interface AdminDriverControllerApi {
    /**
     * POST api/v1/admin/drivers/{id}/activate
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @param id 
     * @param adminDriverActionRequest 
     * @return [ApiResponseVoid]
     */
    @POST("api/v1/admin/drivers/{id}/activate")
    suspend fun activateDriver(@Path("id") id: java.util.UUID, @Body adminDriverActionRequest: AdminDriverActionRequest): Response<ApiResponseVoid>

    /**
     * GET api/v1/admin/drivers/{id}
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @param id 
     * @return [ApiResponseAdminDriverDetailResponse]
     */
    @GET("api/v1/admin/drivers/{id}")
    suspend fun getDriver(@Path("id") id: java.util.UUID): Response<ApiResponseAdminDriverDetailResponse>


    /**
    * enum for parameter verificationStatus
    */
    enum class VerificationStatusListDrivers(val value: kotlin.String) {
        @Json(name = "NOT_SUBMITTED") NOT_SUBMITTED("NOT_SUBMITTED"),
        @Json(name = "PENDING_REVIEW") PENDING_REVIEW("PENDING_REVIEW"),
        @Json(name = "APPROVED") APPROVED("APPROVED"),
        @Json(name = "REJECTED") REJECTED("REJECTED"),
        @Json(name = "SUSPENDED") SUSPENDED("SUSPENDED")
    }

    /**
     * GET api/v1/admin/drivers
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @param page  (optional)
     * @param size  (optional)
     * @param sort  (optional)
     * @param q  (optional)
     * @param verificationStatus  (optional)
     * @return [ApiResponsePageResponseAdminDriverListItemResponse]
     */
    @GET("api/v1/admin/drivers")
    suspend fun listDrivers(@Query("page") page: kotlin.Int? = null, @Query("size") size: kotlin.Int? = null, @Query("sort") sort: kotlin.collections.List<kotlin.String>? = null, @Query("q") q: kotlin.String? = null, @Query("verificationStatus") verificationStatus: VerificationStatusListDrivers? = null): Response<ApiResponsePageResponseAdminDriverListItemResponse>

    /**
     * POST api/v1/admin/drivers/{id}/suspend
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @param id 
     * @param adminDriverActionRequest 
     * @return [ApiResponseVoid]
     */
    @POST("api/v1/admin/drivers/{id}/suspend")
    suspend fun suspendDriver(@Path("id") id: java.util.UUID, @Body adminDriverActionRequest: AdminDriverActionRequest): Response<ApiResponseVoid>

}
