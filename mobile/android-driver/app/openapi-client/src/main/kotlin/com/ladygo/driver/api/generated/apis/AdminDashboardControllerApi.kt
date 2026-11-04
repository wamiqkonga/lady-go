package com.ladygo.driver.api.generated.apis

import com.ladygo.driver.api.generated.infrastructure.CollectionFormats.*
import retrofit2.http.*
import retrofit2.Response
import okhttp3.RequestBody
import com.squareup.moshi.Json

import com.ladygo.driver.api.generated.models.ApiResponseAdminDashboardResponse

interface AdminDashboardControllerApi {
    /**
     * GET api/v1/admin/dashboard
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @return [ApiResponseAdminDashboardResponse]
     */
    @GET("api/v1/admin/dashboard")
    suspend fun dashboard(): Response<ApiResponseAdminDashboardResponse>

}
