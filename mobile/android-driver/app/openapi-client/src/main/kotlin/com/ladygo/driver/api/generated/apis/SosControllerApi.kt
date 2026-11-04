package com.ladygo.driver.api.generated.apis

import com.ladygo.driver.api.generated.infrastructure.CollectionFormats.*
import retrofit2.http.*
import retrofit2.Response
import okhttp3.RequestBody
import com.squareup.moshi.Json

import com.ladygo.driver.api.generated.models.ApiResponseSosResponse
import com.ladygo.driver.api.generated.models.SosRequest

interface SosControllerApi {
    /**
     * POST api/v1/customers/me/sos
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @param sosRequest 
     * @return [ApiResponseSosResponse]
     */
    @POST("api/v1/customers/me/sos")
    suspend fun customerSos(@Body sosRequest: SosRequest): Response<ApiResponseSosResponse>

    /**
     * POST api/v1/drivers/me/sos
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @param sosRequest 
     * @return [ApiResponseSosResponse]
     */
    @POST("api/v1/drivers/me/sos")
    suspend fun driverSos(@Body sosRequest: SosRequest): Response<ApiResponseSosResponse>

}
