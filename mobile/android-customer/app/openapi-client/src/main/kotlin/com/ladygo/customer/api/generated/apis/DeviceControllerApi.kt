package com.ladygo.customer.api.generated.apis

import com.ladygo.customer.api.generated.infrastructure.CollectionFormats.*
import retrofit2.http.*
import retrofit2.Response
import okhttp3.RequestBody
import com.squareup.moshi.Json

import com.ladygo.customer.api.generated.models.ApiResponseVoid
import com.ladygo.customer.api.generated.models.RegisterDeviceRequest
import com.ladygo.customer.api.generated.models.UnregisterDeviceRequest

interface DeviceControllerApi {
    /**
     * POST api/v1/notifications/devices
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @param registerDeviceRequest 
     * @return [ApiResponseVoid]
     */
    @POST("api/v1/notifications/devices")
    suspend fun register(@Body registerDeviceRequest: RegisterDeviceRequest): Response<ApiResponseVoid>

    /**
     * DELETE api/v1/notifications/devices
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @param unregisterDeviceRequest 
     * @return [ApiResponseVoid]
     */
    @DELETE("api/v1/notifications/devices")
    suspend fun unregister(@Body unregisterDeviceRequest: UnregisterDeviceRequest): Response<ApiResponseVoid>

}
