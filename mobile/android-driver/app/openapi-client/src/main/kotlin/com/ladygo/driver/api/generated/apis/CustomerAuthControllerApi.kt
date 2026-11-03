package com.ladygo.driver.api.generated.apis

import com.ladygo.driver.api.generated.infrastructure.CollectionFormats.*
import retrofit2.http.*
import retrofit2.Response
import okhttp3.RequestBody
import com.squareup.moshi.Json

import com.ladygo.driver.api.generated.models.ApiResponseMessageResponse
import com.ladygo.driver.api.generated.models.ApiResponseTokenResponse
import com.ladygo.driver.api.generated.models.CustomerLoginRequest
import com.ladygo.driver.api.generated.models.CustomerRegisterRequest
import com.ladygo.driver.api.generated.models.ForgotPasswordRequest
import com.ladygo.driver.api.generated.models.RegisterOtpRequest
import com.ladygo.driver.api.generated.models.ResetPasswordRequest

interface CustomerAuthControllerApi {
    /**
     * POST api/v1/auth/customer/password/forgot
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @param forgotPasswordRequest 
     * @return [ApiResponseMessageResponse]
     */
    @POST("api/v1/auth/customer/password/forgot")
    suspend fun forgotPassword(@Body forgotPasswordRequest: ForgotPasswordRequest): Response<ApiResponseMessageResponse>

    /**
     * POST api/v1/auth/customer/login
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @param customerLoginRequest 
     * @return [ApiResponseTokenResponse]
     */
    @POST("api/v1/auth/customer/login")
    suspend fun login(@Body customerLoginRequest: CustomerLoginRequest): Response<ApiResponseTokenResponse>

    /**
     * POST api/v1/auth/customer/register
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @param customerRegisterRequest 
     * @return [ApiResponseTokenResponse]
     */
    @POST("api/v1/auth/customer/register")
    suspend fun register1(@Body customerRegisterRequest: CustomerRegisterRequest): Response<ApiResponseTokenResponse>

    /**
     * POST api/v1/auth/customer/register/otp
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @param registerOtpRequest 
     * @return [ApiResponseMessageResponse]
     */
    @POST("api/v1/auth/customer/register/otp")
    suspend fun requestRegistrationOtp(@Body registerOtpRequest: RegisterOtpRequest): Response<ApiResponseMessageResponse>

    /**
     * POST api/v1/auth/customer/password/reset
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @param resetPasswordRequest 
     * @return [ApiResponseMessageResponse]
     */
    @POST("api/v1/auth/customer/password/reset")
    suspend fun resetPassword(@Body resetPasswordRequest: ResetPasswordRequest): Response<ApiResponseMessageResponse>

}
