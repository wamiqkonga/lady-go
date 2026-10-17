package com.ladygo.customer.api.generated.apis

import com.ladygo.customer.api.generated.infrastructure.CollectionFormats.*
import retrofit2.http.*
import retrofit2.Response
import okhttp3.RequestBody
import com.squareup.moshi.Json

import com.ladygo.customer.api.generated.models.AdminConfigUpdateRequest
import com.ladygo.customer.api.generated.models.ApiResponseAdminConfigEntryResponse
import com.ladygo.customer.api.generated.models.ApiResponseListAdminConfigEntryResponse

interface AdminConfigControllerApi {
    /**
     * GET api/v1/admin/config
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @return [ApiResponseListAdminConfigEntryResponse]
     */
    @GET("api/v1/admin/config")
    suspend fun list1(): Response<ApiResponseListAdminConfigEntryResponse>

    /**
     * PUT api/v1/admin/config/{id}
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @param id 
     * @param adminConfigUpdateRequest 
     * @return [ApiResponseAdminConfigEntryResponse]
     */
    @PUT("api/v1/admin/config/{id}")
    suspend fun update(@Path("id") id: java.util.UUID, @Body adminConfigUpdateRequest: AdminConfigUpdateRequest): Response<ApiResponseAdminConfigEntryResponse>

}
