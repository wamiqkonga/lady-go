package com.ladygo.driver.api.generated.apis

import com.ladygo.driver.api.generated.infrastructure.CollectionFormats.*
import retrofit2.http.*
import retrofit2.Response
import okhttp3.RequestBody
import com.squareup.moshi.Json

import com.ladygo.driver.api.generated.models.ApiResponseDriverPasswordStatusResponse
import com.ladygo.driver.api.generated.models.ApiResponseMessageResponse
import com.ladygo.driver.api.generated.models.ApiResponseTokenResponse
import com.ladygo.driver.api.generated.models.ApiResponseVoid
import com.ladygo.driver.api.generated.models.DriverPasswordChangeRequest
import com.ladygo.driver.api.generated.models.DriverPasswordForgotRequest
import com.ladygo.driver.api.generated.models.DriverPasswordLoginRequest
import com.ladygo.driver.api.generated.models.DriverPasswordResetRequest
import com.ladygo.driver.api.generated.models.DriverPasswordSetRequest

interface DriverAuthControllerApi {
    /**
     * POST api/v1/auth/driver/password/change
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @param driverPasswordChangeRequest 
     * @return [ApiResponseTokenResponse]
     */
    @POST("api/v1/auth/driver/password/change")
    suspend fun driverPasswordChange(@Body driverPasswordChangeRequest: DriverPasswordChangeRequest): Response<ApiResponseTokenResponse>

    /**
     * POST api/v1/auth/driver/password/forgot
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @param driverPasswordForgotRequest 
     * @return [ApiResponseMessageResponse]
     */
    @POST("api/v1/auth/driver/password/forgot")
    suspend fun driverPasswordForgot(@Body driverPasswordForgotRequest: DriverPasswordForgotRequest): Response<ApiResponseMessageResponse>

    /**
     * POST api/v1/auth/driver/password/login
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @param driverPasswordLoginRequest 
     * @return [ApiResponseTokenResponse]
     */
    @POST("api/v1/auth/driver/password/login")
    suspend fun driverPasswordLogin(@Body driverPasswordLoginRequest: DriverPasswordLoginRequest): Response<ApiResponseTokenResponse>

    /**
     * POST api/v1/auth/driver/password/reset
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @param driverPasswordResetRequest 
     * @return [ApiResponseVoid]
     */
    @POST("api/v1/auth/driver/password/reset")
    suspend fun driverPasswordReset(@Body driverPasswordResetRequest: DriverPasswordResetRequest): Response<ApiResponseVoid>

    /**
     * POST api/v1/auth/driver/password/set
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @param driverPasswordSetRequest 
     * @return [ApiResponseVoid]
     */
    @POST("api/v1/auth/driver/password/set")
    suspend fun driverPasswordSet(@Body driverPasswordSetRequest: DriverPasswordSetRequest): Response<ApiResponseVoid>

    /**
     * GET api/v1/auth/driver/password/status
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @return [ApiResponseDriverPasswordStatusResponse]
     */
    @GET("api/v1/auth/driver/password/status")
    suspend fun driverPasswordStatus(): Response<ApiResponseDriverPasswordStatusResponse>

}
