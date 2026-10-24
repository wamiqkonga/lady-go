package com.ladygo.driver.api.generated.apis

import com.ladygo.driver.api.generated.infrastructure.CollectionFormats.*
import retrofit2.http.*
import retrofit2.Response
import okhttp3.RequestBody
import com.squareup.moshi.Json

import com.ladygo.driver.api.generated.models.ApiResponseListCityResponse

interface CityControllerApi {
    /**
     * GET api/v1/cities
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @return [ApiResponseListCityResponse]
     */
    @GET("api/v1/cities")
    suspend fun list(): Response<ApiResponseListCityResponse>

}
