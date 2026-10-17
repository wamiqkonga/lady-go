package com.ladygo.customer.api.generated.apis

import com.ladygo.customer.api.generated.infrastructure.CollectionFormats.*
import retrofit2.http.*
import retrofit2.Response
import okhttp3.RequestBody
import com.squareup.moshi.Json

import com.ladygo.customer.api.generated.models.ApiResponseDriverDocumentAdminResponse
import com.ladygo.customer.api.generated.models.ApiResponseDriverReviewSummaryResponse
import com.ladygo.customer.api.generated.models.ApiResponseEligibilityReportAdminResponse
import com.ladygo.customer.api.generated.models.ApiResponsePageResponseDriverDocumentAdminResponse
import com.ladygo.customer.api.generated.models.ApiResponsePageResponseEligibilityReportAdminResponse
import com.ladygo.customer.api.generated.models.ApiResponseURI
import com.ladygo.customer.api.generated.models.ApiResponseVoid
import com.ladygo.customer.api.generated.models.DocumentReviewRequest
import com.ladygo.customer.api.generated.models.EligibilityReportResolveRequest
import com.ladygo.customer.api.generated.models.Pageable

import com.ladygo.customer.api.generated.models.*

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
