package com.ladygo.driver.api.generated.apis

import com.ladygo.driver.api.generated.infrastructure.CollectionFormats.*
import retrofit2.http.*
import retrofit2.Response
import okhttp3.RequestBody
import com.squareup.moshi.Json

import com.ladygo.driver.api.generated.models.AdminUnlockRequest
import com.ladygo.driver.api.generated.models.AdminUserInviteRequest
import com.ladygo.driver.api.generated.models.AdminUserReasonRequest
import com.ladygo.driver.api.generated.models.AdminUserRoleRequest
import com.ladygo.driver.api.generated.models.ApiResponseAdminUserResponse
import com.ladygo.driver.api.generated.models.ApiResponseListAdminUserResponse
import com.ladygo.driver.api.generated.models.ApiResponseVoid

interface AdminUserControllerApi {
    /**
     * POST api/v1/admin/users/{id}/role
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @param id 
     * @param adminUserRoleRequest 
     * @return [ApiResponseAdminUserResponse]
     */
    @POST("api/v1/admin/users/{id}/role")
    suspend fun changeAdminUserRole(@Path("id") id: java.util.UUID, @Body adminUserRoleRequest: AdminUserRoleRequest): Response<ApiResponseAdminUserResponse>

    /**
     * POST api/v1/admin/users/{id}/deactivate
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @param id 
     * @param adminUserReasonRequest 
     * @return [ApiResponseAdminUserResponse]
     */
    @POST("api/v1/admin/users/{id}/deactivate")
    suspend fun deactivateAdminUser(@Path("id") id: java.util.UUID, @Body adminUserReasonRequest: AdminUserReasonRequest): Response<ApiResponseAdminUserResponse>

    /**
     * GET api/v1/admin/users/{id}
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @param id 
     * @return [ApiResponseAdminUserResponse]
     */
    @GET("api/v1/admin/users/{id}")
    suspend fun getAdminUser(@Path("id") id: java.util.UUID): Response<ApiResponseAdminUserResponse>

    /**
     * POST api/v1/admin/users
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @param adminUserInviteRequest 
     * @return [ApiResponseAdminUserResponse]
     */
    @POST("api/v1/admin/users")
    suspend fun inviteAdminUser(@Body adminUserInviteRequest: AdminUserInviteRequest): Response<ApiResponseAdminUserResponse>

    /**
     * GET api/v1/admin/users
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @return [ApiResponseListAdminUserResponse]
     */
    @GET("api/v1/admin/users")
    suspend fun listAdminUsers(): Response<ApiResponseListAdminUserResponse>

    /**
     * POST api/v1/admin/users/{id}/reactivate
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @param id 
     * @param adminUserReasonRequest 
     * @return [ApiResponseAdminUserResponse]
     */
    @POST("api/v1/admin/users/{id}/reactivate")
    suspend fun reactivateAdminUser(@Path("id") id: java.util.UUID, @Body adminUserReasonRequest: AdminUserReasonRequest): Response<ApiResponseAdminUserResponse>

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
