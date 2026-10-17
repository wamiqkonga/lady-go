package com.ladygo.customer.api.generated.apis

import com.ladygo.customer.api.generated.infrastructure.CollectionFormats.*
import retrofit2.http.*
import retrofit2.Response
import okhttp3.RequestBody
import com.squareup.moshi.Json

import com.ladygo.customer.api.generated.models.ApiResponsePageResponseAdminAuditLogResponse
import com.ladygo.customer.api.generated.models.Pageable

import com.ladygo.customer.api.generated.models.*

interface AdminAuditLogControllerApi {
    /**
     * GET api/v1/admin/audit-logs
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @param page  (optional)
     * @param size  (optional)
     * @param sort  (optional)
     * @param actorId  (optional)
     * @param action  (optional)
     * @param targetType  (optional)
     * @param targetId  (optional)
     * @param createdAfter  (optional)
     * @param createdBefore  (optional)
     * @return [ApiResponsePageResponseAdminAuditLogResponse]
     */
    @GET("api/v1/admin/audit-logs")
    suspend fun list2(@Query("page") page: kotlin.Int? = null, @Query("size") size: kotlin.Int? = null, @Query("sort") sort: kotlin.collections.List<kotlin.String>? = null, @Query("actorId") actorId: java.util.UUID? = null, @Query("action") action: kotlin.String? = null, @Query("targetType") targetType: kotlin.String? = null, @Query("targetId") targetId: kotlin.String? = null, @Query("createdAfter") createdAfter: kotlin.String? = null, @Query("createdBefore") createdBefore: kotlin.String? = null): Response<ApiResponsePageResponseAdminAuditLogResponse>

}
