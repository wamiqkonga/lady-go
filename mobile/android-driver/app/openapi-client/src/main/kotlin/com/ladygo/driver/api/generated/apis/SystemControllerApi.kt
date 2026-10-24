package com.ladygo.driver.api.generated.apis

import com.ladygo.driver.api.generated.infrastructure.CollectionFormats.*
import retrofit2.http.*
import retrofit2.Response
import okhttp3.RequestBody
import com.squareup.moshi.Json

import com.ladygo.driver.api.generated.models.ApiResponseMapStringString

interface SystemControllerApi {
    /**
     * GET api/v1/system/ping
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @return [ApiResponseMapStringString]
     */
    @GET("api/v1/system/ping")
    suspend fun ping(): Response<ApiResponseMapStringString>

}
