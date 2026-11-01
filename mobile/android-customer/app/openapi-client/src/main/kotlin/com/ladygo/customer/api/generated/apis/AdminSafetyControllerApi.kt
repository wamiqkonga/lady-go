package com.ladygo.customer.api.generated.apis

import com.ladygo.customer.api.generated.infrastructure.CollectionFormats.*
import retrofit2.http.*
import retrofit2.Response
import okhttp3.RequestBody
import com.squareup.moshi.Json

import com.ladygo.customer.api.generated.models.ApiResponsePageResponseSafetyIncidentSummaryResponse
import com.ladygo.customer.api.generated.models.ApiResponseSafetyIncidentDetailResponse
import com.ladygo.customer.api.generated.models.ApiResponseSafetyOpenCountResponse
import com.ladygo.customer.api.generated.models.Pageable
import com.ladygo.customer.api.generated.models.SafetyIncidentNoteRequest
import com.ladygo.customer.api.generated.models.SafetyIncidentResolveRequest

import com.ladygo.customer.api.generated.models.*

interface AdminSafetyControllerApi {
    /**
     * POST api/v1/admin/safety/incidents/{id}/acknowledge
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @param id 
     * @return [ApiResponseSafetyIncidentDetailResponse]
     */
    @POST("api/v1/admin/safety/incidents/{id}/acknowledge")
    suspend fun acknowledge(@Path("id") id: java.util.UUID): Response<ApiResponseSafetyIncidentDetailResponse>

    /**
     * POST api/v1/admin/safety/incidents/{id}/notes
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @param id 
     * @param safetyIncidentNoteRequest 
     * @return [ApiResponseSafetyIncidentDetailResponse]
     */
    @POST("api/v1/admin/safety/incidents/{id}/notes")
    suspend fun addNote(@Path("id") id: java.util.UUID, @Body safetyIncidentNoteRequest: SafetyIncidentNoteRequest): Response<ApiResponseSafetyIncidentDetailResponse>

    /**
     * GET api/v1/admin/safety/incidents/{id}
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @param id 
     * @return [ApiResponseSafetyIncidentDetailResponse]
     */
    @GET("api/v1/admin/safety/incidents/{id}")
    suspend fun get2(@Path("id") id: java.util.UUID): Response<ApiResponseSafetyIncidentDetailResponse>


    /**
    * enum for parameter status
    */
    enum class StatusList1(val value: kotlin.String) {
        @Json(name = "OPEN") OPEN("OPEN"),
        @Json(name = "ACKNOWLEDGED") ACKNOWLEDGED("ACKNOWLEDGED"),
        @Json(name = "ESCALATED") ESCALATED("ESCALATED"),
        @Json(name = "RESOLVED") RESOLVED("RESOLVED")
    }

    /**
     * GET api/v1/admin/safety/incidents
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @param page  (optional)
     * @param size  (optional)
     * @param sort  (optional)
     * @param status  (optional)
     * @return [ApiResponsePageResponseSafetyIncidentSummaryResponse]
     */
    @GET("api/v1/admin/safety/incidents")
    suspend fun list1(@Query("page") page: kotlin.Int? = null, @Query("size") size: kotlin.Int? = null, @Query("sort") sort: kotlin.collections.List<kotlin.String>? = null, @Query("status") status: StatusList1? = null): Response<ApiResponsePageResponseSafetyIncidentSummaryResponse>

    /**
     * GET api/v1/admin/safety/incidents/open-count
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @return [ApiResponseSafetyOpenCountResponse]
     */
    @GET("api/v1/admin/safety/incidents/open-count")
    suspend fun openCount(): Response<ApiResponseSafetyOpenCountResponse>

    /**
     * POST api/v1/admin/safety/incidents/{id}/resolve
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @param id 
     * @param safetyIncidentResolveRequest 
     * @return [ApiResponseSafetyIncidentDetailResponse]
     */
    @POST("api/v1/admin/safety/incidents/{id}/resolve")
    suspend fun resolve(@Path("id") id: java.util.UUID, @Body safetyIncidentResolveRequest: SafetyIncidentResolveRequest): Response<ApiResponseSafetyIncidentDetailResponse>

}
