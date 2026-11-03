package com.ladygo.driver.api.generated.apis

import com.ladygo.driver.api.generated.infrastructure.CollectionFormats.*
import retrofit2.http.*
import retrofit2.Response
import okhttp3.RequestBody
import com.squareup.moshi.Json

import com.ladygo.driver.api.generated.models.ApiResponseListSafetyIncidentResponse

interface AdminSafetyIncidentControllerApi {
    /**
     * GET api/v1/admin/safety-incidents
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @return [ApiResponseListSafetyIncidentResponse]
     */
    @GET("api/v1/admin/safety-incidents")
    suspend fun openIncidents(): Response<ApiResponseListSafetyIncidentResponse>

}
