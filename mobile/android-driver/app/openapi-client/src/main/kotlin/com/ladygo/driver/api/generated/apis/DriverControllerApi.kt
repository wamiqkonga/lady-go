package com.ladygo.driver.api.generated.apis

import com.ladygo.driver.api.generated.infrastructure.CollectionFormats.*
import retrofit2.http.*
import retrofit2.Response
import okhttp3.RequestBody
import com.squareup.moshi.Json

import com.ladygo.driver.api.generated.models.ApiResponseDeclarationResponse
import com.ladygo.driver.api.generated.models.ApiResponseDeclarationStatusResponse
import com.ladygo.driver.api.generated.models.ApiResponseDriverAvailabilityResponse
import com.ladygo.driver.api.generated.models.ApiResponseDriverDocumentResponse
import com.ladygo.driver.api.generated.models.ApiResponseDriverProfileResponse
import com.ladygo.driver.api.generated.models.ApiResponseListDriverDocumentResponse
import com.ladygo.driver.api.generated.models.ApiResponseVehicleResponse
import com.ladygo.driver.api.generated.models.ApiResponseVoid
import com.ladygo.driver.api.generated.models.DeclarationAcceptRequest
import com.ladygo.driver.api.generated.models.DriverAvailabilityRequest
import com.ladygo.driver.api.generated.models.DriverLocationRequest
import com.ladygo.driver.api.generated.models.DriverProfileRequest
import com.ladygo.driver.api.generated.models.UploadSelfieRequest
import com.ladygo.driver.api.generated.models.VehicleRequest

interface DriverControllerApi {
    /**
     * POST api/v1/drivers/me/declaration
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @param declarationAcceptRequest 
     * @return [ApiResponseDeclarationResponse]
     */
    @POST("api/v1/drivers/me/declaration")
    suspend fun acceptDeclaration(@Body declarationAcceptRequest: DeclarationAcceptRequest): Response<ApiResponseDeclarationResponse>

    /**
     * POST api/v1/drivers/me
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @param driverProfileRequest 
     * @return [ApiResponseDriverProfileResponse]
     */
    @POST("api/v1/drivers/me")
    suspend fun bootstrapProfile(@Body driverProfileRequest: DriverProfileRequest): Response<ApiResponseDriverProfileResponse>

    /**
     * GET api/v1/drivers/me/availability
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @return [ApiResponseDriverAvailabilityResponse]
     */
    @GET("api/v1/drivers/me/availability")
    suspend fun getAvailability(): Response<ApiResponseDriverAvailabilityResponse>

    /**
     * GET api/v1/drivers/me/declaration
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @return [ApiResponseDeclarationStatusResponse]
     */
    @GET("api/v1/drivers/me/declaration")
    suspend fun getDeclarationStatus(): Response<ApiResponseDeclarationStatusResponse>

    /**
     * GET api/v1/drivers/me
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @return [ApiResponseDriverProfileResponse]
     */
    @GET("api/v1/drivers/me")
    suspend fun getProfile1(): Response<ApiResponseDriverProfileResponse>

    /**
     * GET api/v1/drivers/me/vehicle
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @return [ApiResponseVehicleResponse]
     */
    @GET("api/v1/drivers/me/vehicle")
    suspend fun getVehicle(): Response<ApiResponseVehicleResponse>

    /**
     * GET api/v1/drivers/me/documents
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @return [ApiResponseListDriverDocumentResponse]
     */
    @GET("api/v1/drivers/me/documents")
    suspend fun listDocuments(): Response<ApiResponseListDriverDocumentResponse>

    /**
     * PUT api/v1/drivers/me/availability
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @param driverAvailabilityRequest 
     * @return [ApiResponseDriverAvailabilityResponse]
     */
    @PUT("api/v1/drivers/me/availability")
    suspend fun setAvailability(@Body driverAvailabilityRequest: DriverAvailabilityRequest): Response<ApiResponseDriverAvailabilityResponse>

    /**
     * POST api/v1/drivers/me/location
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @param driverLocationRequest 
     * @return [ApiResponseVoid]
     */
    @POST("api/v1/drivers/me/location")
    suspend fun updateLocation(@Body driverLocationRequest: DriverLocationRequest): Response<ApiResponseVoid>


    /**
    * enum for parameter type
    */
    enum class TypeUploadDocument(val value: kotlin.String) {
        @Json(name = "GOVERNMENT_ID") GOVERNMENT_ID("GOVERNMENT_ID"),
        @Json(name = "LICENSE") LICENSE("LICENSE"),
        @Json(name = "VEHICLE_REGISTRATION") VEHICLE_REGISTRATION("VEHICLE_REGISTRATION"),
        @Json(name = "VEHICLE_PHOTO") VEHICLE_PHOTO("VEHICLE_PHOTO"),
        @Json(name = "INSURANCE") INSURANCE("INSURANCE"),
        @Json(name = "FITNESS_CERTIFICATE") FITNESS_CERTIFICATE("FITNESS_CERTIFICATE"),
        @Json(name = "POLLUTION_CERTIFICATE") POLLUTION_CERTIFICATE("POLLUTION_CERTIFICATE"),
        @Json(name = "POLICE_VERIFICATION") POLICE_VERIFICATION("POLICE_VERIFICATION"),
        @Json(name = "PERMIT") PERMIT("PERMIT"),
        @Json(name = "PHOTO") PHOTO("PHOTO"),
        @Json(name = "SELFIE") SELFIE("SELFIE"),
        @Json(name = "OTHER") OTHER("OTHER")
    }

    /**
     * POST api/v1/drivers/me/documents
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @param type 
     * @param number  (optional)
     * @param expiresAt  (optional)
     * @param uploadSelfieRequest  (optional)
     * @return [ApiResponseDriverDocumentResponse]
     */
    @POST("api/v1/drivers/me/documents")
    suspend fun uploadDocument(@Query("type") type: TypeUploadDocument, @Query("number") number: kotlin.String? = null, @Query("expiresAt") expiresAt: kotlin.String? = null, @Body uploadSelfieRequest: UploadSelfieRequest? = null): Response<ApiResponseDriverDocumentResponse>

    /**
     * POST api/v1/drivers/me/selfie
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @param uploadSelfieRequest  (optional)
     * @return [ApiResponseDriverDocumentResponse]
     */
    @POST("api/v1/drivers/me/selfie")
    suspend fun uploadSelfie(@Body uploadSelfieRequest: UploadSelfieRequest? = null): Response<ApiResponseDriverDocumentResponse>

    /**
     * PUT api/v1/drivers/me/vehicle
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @param vehicleRequest 
     * @return [ApiResponseVehicleResponse]
     */
    @PUT("api/v1/drivers/me/vehicle")
    suspend fun upsertVehicle(@Body vehicleRequest: VehicleRequest): Response<ApiResponseVehicleResponse>

}
