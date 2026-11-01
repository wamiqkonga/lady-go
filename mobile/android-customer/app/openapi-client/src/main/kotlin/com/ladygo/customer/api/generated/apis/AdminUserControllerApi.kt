package com.ladygo.customer.api.generated.apis

import com.ladygo.customer.api.generated.infrastructure.CollectionFormats.*
import retrofit2.http.*
import retrofit2.Response
import okhttp3.RequestBody
import com.squareup.moshi.Json

import com.ladygo.customer.api.generated.models.AdminUnlockRequest
import com.ladygo.customer.api.generated.models.ApiResponseVoid

interface AdminUserControllerApi {
    /**
     * POST api/v1/admin/users/{id}/mfa/reset
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @param id 
     * @return [ApiResponseVoid]
     */
    @POST("api/v1/admin/users/{id}/mfa/reset")
    suspend fun resetMfa(@Path("id") id: java.util.UUID): Response<ApiResponseVoid>

    /**
     * POST api/v1/admin/users/{id}/unlock
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @param id 
     * @param adminUnlockRequest 
     * @return [ApiResponseVoid]
     */
    @POST("api/v1/admin/users/{id}/unlock")
    suspend fun unlock(@Path("id") id: java.util.UUID, @Body adminUnlockRequest: AdminUnlockRequest): Response<ApiResponseVoid>

}
