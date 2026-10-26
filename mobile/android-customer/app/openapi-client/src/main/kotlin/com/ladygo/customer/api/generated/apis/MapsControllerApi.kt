package com.ladygo.customer.api.generated.apis

import com.ladygo.customer.api.generated.infrastructure.CollectionFormats.*
import retrofit2.http.*
import retrofit2.Response
import okhttp3.RequestBody
import com.squareup.moshi.Json

import com.ladygo.customer.api.generated.models.ApiResponseReverseGeocodeResponse
import com.ladygo.customer.api.generated.models.ReverseGeocodeRequest

interface MapsControllerApi {
    /**
     * POST api/v1/maps/reverse-geocode
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @param reverseGeocodeRequest 
     * @return [ApiResponseReverseGeocodeResponse]
     */
    @POST("api/v1/maps/reverse-geocode")
    suspend fun reverseGeocode(@Body reverseGeocodeRequest: ReverseGeocodeRequest): Response<ApiResponseReverseGeocodeResponse>

}
