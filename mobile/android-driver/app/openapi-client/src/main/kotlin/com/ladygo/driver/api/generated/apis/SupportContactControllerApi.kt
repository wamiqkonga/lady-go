package com.ladygo.driver.api.generated.apis

import com.ladygo.driver.api.generated.infrastructure.CollectionFormats.*
import retrofit2.http.*
import retrofit2.Response
import okhttp3.RequestBody
import com.squareup.moshi.Json

import com.ladygo.driver.api.generated.models.ApiResponseSupportContactResponse

interface SupportContactControllerApi {
    /**
     * GET api/v1/support/contact
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @return [ApiResponseSupportContactResponse]
     */
    @GET("api/v1/support/contact")
    suspend fun getSupportContact(): Response<ApiResponseSupportContactResponse>

}
