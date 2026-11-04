package com.ladygo.customer.api.generated.apis

import com.ladygo.customer.api.generated.infrastructure.CollectionFormats.*
import retrofit2.http.*
import retrofit2.Response
import okhttp3.RequestBody
import com.squareup.moshi.Json

import com.ladygo.customer.api.generated.models.ApiResponseListPricingCityResponse
import com.ladygo.customer.api.generated.models.ApiResponsePricingPreviewResponse
import com.ladygo.customer.api.generated.models.ApiResponsePricingRuleResponse
import com.ladygo.customer.api.generated.models.ApiResponsePricingRulesViewResponse
import com.ladygo.customer.api.generated.models.PricingRuleCancelRequest
import com.ladygo.customer.api.generated.models.PricingRuleCreateRequest
import com.ladygo.customer.api.generated.models.PricingRulePreviewRequest

interface AdminPricingRuleControllerApi {
    /**
     * POST api/v1/admin/pricing-rules/{id}/cancel
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @param id 
     * @param pricingRuleCancelRequest 
     * @return [ApiResponsePricingRuleResponse]
     */
    @POST("api/v1/admin/pricing-rules/{id}/cancel")
    suspend fun cancel1(@Path("id") id: java.util.UUID, @Body pricingRuleCancelRequest: PricingRuleCancelRequest): Response<ApiResponsePricingRuleResponse>

    /**
     * GET api/v1/admin/pricing-rules/cities
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @return [ApiResponseListPricingCityResponse]
     */
    @GET("api/v1/admin/pricing-rules/cities")
    suspend fun cities(): Response<ApiResponseListPricingCityResponse>

    /**
     * POST api/v1/admin/pricing-rules
     * 
     * 
     * Responses:
     *  - 201: Created
     *
     * @param pricingRuleCreateRequest 
     * @return [ApiResponsePricingRuleResponse]
     */
    @POST("api/v1/admin/pricing-rules")
    suspend fun create1(@Body pricingRuleCreateRequest: PricingRuleCreateRequest): Response<ApiResponsePricingRuleResponse>

    /**
     * POST api/v1/admin/pricing-rules/preview
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @param pricingRulePreviewRequest 
     * @return [ApiResponsePricingPreviewResponse]
     */
    @POST("api/v1/admin/pricing-rules/preview")
    suspend fun preview(@Body pricingRulePreviewRequest: PricingRulePreviewRequest): Response<ApiResponsePricingPreviewResponse>


    /**
    * enum for parameter vehicleType
    */
    enum class VehicleTypeView(val value: kotlin.String) {
        @Json(name = "CAB") CAB("CAB"),
        @Json(name = "SUV") SUV("SUV"),
        @Json(name = "AUTO") AUTO("AUTO"),
        @Json(name = "BIKE") BIKE("BIKE")
    }

    /**
     * GET api/v1/admin/pricing-rules
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @param cityId 
     * @param vehicleType 
     * @return [ApiResponsePricingRulesViewResponse]
     */
    @GET("api/v1/admin/pricing-rules")
    suspend fun view(@Query("cityId") cityId: java.util.UUID, @Query("vehicleType") vehicleType: VehicleTypeView): Response<ApiResponsePricingRulesViewResponse>

}
