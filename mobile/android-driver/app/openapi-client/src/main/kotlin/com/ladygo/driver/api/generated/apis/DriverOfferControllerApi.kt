package com.ladygo.driver.api.generated.apis

import com.ladygo.driver.api.generated.infrastructure.CollectionFormats.*
import retrofit2.http.*
import retrofit2.Response
import okhttp3.RequestBody
import com.squareup.moshi.Json

import com.ladygo.driver.api.generated.models.ApiResponseBookingDetailResponse
import com.ladygo.driver.api.generated.models.ApiResponseOfferResponse
import com.ladygo.driver.api.generated.models.ApiResponseVoid
import com.ladygo.driver.api.generated.models.OfferRejectRequest

interface DriverOfferControllerApi {
    /**
     * POST api/v1/drivers/me/offers/{id}/accept
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @param id 
     * @return [ApiResponseBookingDetailResponse]
     */
    @POST("api/v1/drivers/me/offers/{id}/accept")
    suspend fun accept(@Path("id") id: java.util.UUID): Response<ApiResponseBookingDetailResponse>

    /**
     * GET api/v1/drivers/me/offers/current
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @return [ApiResponseOfferResponse]
     */
    @GET("api/v1/drivers/me/offers/current")
    suspend fun currentOffer(): Response<ApiResponseOfferResponse>

    /**
     * POST api/v1/drivers/me/offers/{id}/reject
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @param id 
     * @param offerRejectRequest  (optional)
     * @return [ApiResponseVoid]
     */
    @POST("api/v1/drivers/me/offers/{id}/reject")
    suspend fun reject(@Path("id") id: java.util.UUID, @Body offerRejectRequest: OfferRejectRequest? = null): Response<ApiResponseVoid>

}
