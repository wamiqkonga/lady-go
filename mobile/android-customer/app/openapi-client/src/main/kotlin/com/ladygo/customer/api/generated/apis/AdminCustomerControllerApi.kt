package com.ladygo.customer.api.generated.apis

import com.ladygo.customer.api.generated.infrastructure.CollectionFormats.*
import retrofit2.http.*
import retrofit2.Response
import okhttp3.RequestBody
import com.squareup.moshi.Json

import com.ladygo.customer.api.generated.models.AdminCustomerActionRequest
import com.ladygo.customer.api.generated.models.ApiResponseAdminCustomerDetailResponse
import com.ladygo.customer.api.generated.models.ApiResponsePageResponseAdminCustomerSummaryResponse
import com.ladygo.customer.api.generated.models.Pageable

import com.ladygo.customer.api.generated.models.*

interface AdminCustomerControllerApi {
    /**
     * POST api/v1/admin/customers/{id}/activate
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @param id 
     * @param adminCustomerActionRequest 
     * @return [ApiResponseAdminCustomerDetailResponse]
     */
    @POST("api/v1/admin/customers/{id}/activate")
    suspend fun activateCustomer(@Path("id") id: java.util.UUID, @Body adminCustomerActionRequest: AdminCustomerActionRequest): Response<ApiResponseAdminCustomerDetailResponse>

    /**
     * GET api/v1/admin/customers/{id}
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @param id 
     * @return [ApiResponseAdminCustomerDetailResponse]
     */
    @GET("api/v1/admin/customers/{id}")
    suspend fun getCustomer(@Path("id") id: java.util.UUID): Response<ApiResponseAdminCustomerDetailResponse>


    /**
    * enum for parameter status
    */
    enum class StatusListCustomers(val value: kotlin.String) {
        @Json(name = "ACTIVE") ACTIVE("ACTIVE"),
        @Json(name = "SUSPENDED") SUSPENDED("SUSPENDED"),
        @Json(name = "DELETED") DELETED("DELETED")
    }

    /**
     * GET api/v1/admin/customers
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @param page  (optional)
     * @param size  (optional)
     * @param sort  (optional)
     * @param q  (optional)
     * @param status  (optional)
     * @return [ApiResponsePageResponseAdminCustomerSummaryResponse]
     */
    @GET("api/v1/admin/customers")
    suspend fun listCustomers(@Query("page") page: kotlin.Int? = null, @Query("size") size: kotlin.Int? = null, @Query("sort") sort: kotlin.collections.List<kotlin.String>? = null, @Query("q") q: kotlin.String? = null, @Query("status") status: StatusListCustomers? = null): Response<ApiResponsePageResponseAdminCustomerSummaryResponse>

    /**
     * POST api/v1/admin/customers/{id}/suspend
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @param id 
     * @param adminCustomerActionRequest 
     * @return [ApiResponseAdminCustomerDetailResponse]
     */
    @POST("api/v1/admin/customers/{id}/suspend")
    suspend fun suspendCustomer(@Path("id") id: java.util.UUID, @Body adminCustomerActionRequest: AdminCustomerActionRequest): Response<ApiResponseAdminCustomerDetailResponse>

}
