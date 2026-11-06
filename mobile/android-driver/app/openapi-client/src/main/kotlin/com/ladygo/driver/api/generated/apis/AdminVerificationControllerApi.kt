package com.ladygo.driver.api.generated.apis

import com.ladygo.driver.api.generated.infrastructure.CollectionFormats.*
import retrofit2.http.*
import retrofit2.Response
import okhttp3.RequestBody
import com.squareup.moshi.Json

import com.ladygo.driver.api.generated.models.ApiResponseDriverDocumentAdminResponse
import com.ladygo.driver.api.generated.models.ApiResponseDriverReviewSummaryResponse
import com.ladygo.driver.api.generated.models.ApiResponseEligibilityReportAdminResponse
import com.ladygo.driver.api.generated.models.ApiResponsePageResponseDriverDocumentAdminResponse
import com.ladygo.driver.api.generated.models.ApiResponsePageResponseDriverVerificationQueueItemResponse
import com.ladygo.driver.api.generated.models.ApiResponsePageResponseEligibilityReportAdminResponse
import com.ladygo.driver.api.generated.models.ApiResponseURI
import com.ladygo.driver.api.generated.models.ApiResponseVoid
import com.ladygo.driver.api.generated.models.DocumentReviewRequest
import com.ladygo.driver.api.generated.models.EligibilityReportResolveRequest
import com.ladygo.driver.api.generated.models.Pageable

import com.ladygo.driver.api.generated.models.*

interface AdminVerificationControllerApi {
    /**
     * POST api/v1/admin/driver-documents/{id}/approve
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @param id 
     * @param documentReviewRequest  (optional)
     * @return [ApiResponseDriverDocumentAdminResponse]
     */
    @POST("api/v1/admin/driver-documents/{id}/approve")
    suspend fun approveDocument(@Path("id") id: java.util.UUID, @Body documentReviewRequest: DocumentReviewRequest? = null): Response<ApiResponseDriverDocumentAdminResponse>

    /**
     * POST api/v1/admin/drivers/{id}/approve
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @param id 
     * @return [ApiResponseVoid]
     */
    @POST("api/v1/admin/drivers/{id}/approve")
    suspend fun approveDriver(@Path("id") id: java.util.UUID): Response<ApiResponseVoid>

    /**
     * POST api/v1/admin/vehicles/{id}/approve
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @param id 
     * @return [ApiResponseVoid]
     */
    @POST("api/v1/admin/vehicles/{id}/approve")
    suspend fun approveVehicle(@Path("id") id: java.util.UUID): Response<ApiResponseVoid>


    /**
    * enum for parameter status
    */
    enum class StatusCaptainQueue(val value: kotlin.String) {
        @Json(name = "NOT_SUBMITTED") NOT_SUBMITTED("NOT_SUBMITTED"),
        @Json(name = "PENDING_REVIEW") PENDING_REVIEW("PENDING_REVIEW"),
        @Json(name = "APPROVED") APPROVED("APPROVED"),
        @Json(name = "REJECTED") REJECTED("REJECTED"),
        @Json(name = "SUSPENDED") SUSPENDED("SUSPENDED")
    }

    /**
     * GET api/v1/admin/driver-verification/queue
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
     * @param overdueOnly  (optional, default to false)
     * @param readyOnly  (optional, default to false)
     * @return [ApiResponsePageResponseDriverVerificationQueueItemResponse]
     */
    @GET("api/v1/admin/driver-verification/queue")
    suspend fun captainQueue(@Query("page") page: kotlin.Int? = null, @Query("size") size: kotlin.Int? = null, @Query("sort") sort: kotlin.collections.List<kotlin.String>? = null, @Query("q") q: kotlin.String? = null, @Query("status") status: StatusCaptainQueue? = null, @Query("overdueOnly") overdueOnly: kotlin.Boolean? = false, @Query("readyOnly") readyOnly: kotlin.Boolean? = false): Response<ApiResponsePageResponseDriverVerificationQueueItemResponse>

    /**
     * GET api/v1/admin/drivers/{id}/review
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @param id 
     * @return [ApiResponseDriverReviewSummaryResponse]
     */
    @GET("api/v1/admin/drivers/{id}/review")
    suspend fun getDriverReview(@Path("id") id: java.util.UUID): Response<ApiResponseDriverReviewSummaryResponse>

    /**
     * GET api/v1/admin/eligibility-reports
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @param page  (optional)
     * @param size  (optional)
     * @param sort  (optional)
     * @return [ApiResponsePageResponseEligibilityReportAdminResponse]
     */
    @GET("api/v1/admin/eligibility-reports")
    suspend fun listOpenReports(@Query("page") page: kotlin.Int? = null, @Query("size") size: kotlin.Int? = null, @Query("sort") sort: kotlin.collections.List<kotlin.String>? = null): Response<ApiResponsePageResponseEligibilityReportAdminResponse>

    /**
     * GET api/v1/admin/driver-documents
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @param page  (optional)
     * @param size  (optional)
     * @param sort  (optional)
     * @return [ApiResponsePageResponseDriverDocumentAdminResponse]
     */
    @GET("api/v1/admin/driver-documents")
    suspend fun listPendingDocuments(@Query("page") page: kotlin.Int? = null, @Query("size") size: kotlin.Int? = null, @Query("sort") sort: kotlin.collections.List<kotlin.String>? = null): Response<ApiResponsePageResponseDriverDocumentAdminResponse>

    /**
     * POST api/v1/admin/driver-documents/{id}/reject
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @param id 
     * @param documentReviewRequest 
     * @return [ApiResponseDriverDocumentAdminResponse]
     */
    @POST("api/v1/admin/driver-documents/{id}/reject")
    suspend fun rejectDocument(@Path("id") id: java.util.UUID, @Body documentReviewRequest: DocumentReviewRequest): Response<ApiResponseDriverDocumentAdminResponse>

    /**
     * POST api/v1/admin/drivers/{id}/reject
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @param id 
     * @param documentReviewRequest 
     * @return [ApiResponseVoid]
     */
    @POST("api/v1/admin/drivers/{id}/reject")
    suspend fun rejectDriver(@Path("id") id: java.util.UUID, @Body documentReviewRequest: DocumentReviewRequest): Response<ApiResponseVoid>

    /**
     * POST api/v1/admin/vehicles/{id}/reject
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @param id 
     * @param documentReviewRequest 
     * @return [ApiResponseVoid]
     */
    @POST("api/v1/admin/vehicles/{id}/reject")
    suspend fun rejectVehicle(@Path("id") id: java.util.UUID, @Body documentReviewRequest: DocumentReviewRequest): Response<ApiResponseVoid>

    /**
     * POST api/v1/admin/eligibility-reports/{id}/resolve
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @param id 
     * @param eligibilityReportResolveRequest 
     * @return [ApiResponseEligibilityReportAdminResponse]
     */
    @POST("api/v1/admin/eligibility-reports/{id}/resolve")
    suspend fun resolveReport(@Path("id") id: java.util.UUID, @Body eligibilityReportResolveRequest: EligibilityReportResolveRequest): Response<ApiResponseEligibilityReportAdminResponse>

    /**
     * POST api/v1/admin/drivers/{id}/trigger-reverification
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @param id 
     * @return [ApiResponseVoid]
     */
    @POST("api/v1/admin/drivers/{id}/trigger-reverification")
    suspend fun triggerRandomReverification(@Path("id") id: java.util.UUID): Response<ApiResponseVoid>

    /**
     * GET api/v1/admin/driver-documents/{id}/view
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @param id 
     * @return [ApiResponseURI]
     */
    @GET("api/v1/admin/driver-documents/{id}/view")
    suspend fun viewDocument(@Path("id") id: java.util.UUID): Response<ApiResponseURI>

}
