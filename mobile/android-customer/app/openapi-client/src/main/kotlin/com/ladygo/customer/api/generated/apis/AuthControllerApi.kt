package com.ladygo.customer.api.generated.apis

import com.ladygo.customer.api.generated.infrastructure.CollectionFormats.*
import retrofit2.http.*
import retrofit2.Response
import okhttp3.RequestBody
import com.squareup.moshi.Json

import com.ladygo.customer.api.generated.models.ApiResponseMessageResponse
import com.ladygo.customer.api.generated.models.ApiResponseObject
import com.ladygo.customer.api.generated.models.ApiResponseTokenResponse
import com.ladygo.customer.api.generated.models.ApiResponseVoid
import com.ladygo.customer.api.generated.models.LogoutRequest
import com.ladygo.customer.api.generated.models.OtpRequestRequest
import com.ladygo.customer.api.generated.models.OtpVerifyRequest
import com.ladygo.customer.api.generated.models.RefreshRequest

interface AuthControllerApi {
    /**
     * POST api/v1/auth/logout
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @param logoutRequest 
     * @return [ApiResponseVoid]
     */
    @POST("api/v1/auth/logout")
    suspend fun logout(@Body logoutRequest: LogoutRequest): Response<ApiResponseVoid>

    /**
     * POST api/v1/auth/refresh
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @param refreshRequest 
     * @return [ApiResponseTokenResponse]
     */
    @POST("api/v1/auth/refresh")
    suspend fun refresh(@Body refreshRequest: RefreshRequest): Response<ApiResponseTokenResponse>

    /**
     * POST api/v1/auth/otp/request
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @param otpRequestRequest 
     * @return [ApiResponseMessageResponse]
     */
    @POST("api/v1/auth/otp/request")
    suspend fun requestOtp(@Body otpRequestRequest: OtpRequestRequest): Response<ApiResponseMessageResponse>

    /**
     * POST api/v1/auth/otp/verify
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @param otpVerifyRequest 
     * @return [ApiResponseObject]
     */
    @POST("api/v1/auth/otp/verify")
    suspend fun verifyOtp(@Body otpVerifyRequest: OtpVerifyRequest): Response<ApiResponseObject>

}
