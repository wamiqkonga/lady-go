package com.ladygo.driver.api.generated.apis

import com.ladygo.driver.api.generated.infrastructure.CollectionFormats.*
import retrofit2.http.*
import retrofit2.Response
import okhttp3.RequestBody
import com.squareup.moshi.Json

import com.ladygo.driver.api.generated.models.ApiResponseCustomerProfileResponse
import com.ladygo.driver.api.generated.models.ApiResponseDeclarationResponse
import com.ladygo.driver.api.generated.models.ApiResponseEmergencyContactResponse
import com.ladygo.driver.api.generated.models.ApiResponseListEmergencyContactResponse
import com.ladygo.driver.api.generated.models.ApiResponseListSavedLocationResponse
import com.ladygo.driver.api.generated.models.ApiResponsePageResponseBookingDetailResponse
import com.ladygo.driver.api.generated.models.ApiResponseRideSelfieResponse
import com.ladygo.driver.api.generated.models.ApiResponseSavedLocationResponse
import com.ladygo.driver.api.generated.models.ApiResponseVoid
import com.ladygo.driver.api.generated.models.DeclarationAcceptRequest
import com.ladygo.driver.api.generated.models.EmergencyContactRequest
import com.ladygo.driver.api.generated.models.Pageable
import com.ladygo.driver.api.generated.models.SavedLocationRequest
import com.ladygo.driver.api.generated.models.UpdateProfileRequest
import com.ladygo.driver.api.generated.models.UploadSelfieRequest

import com.ladygo.driver.api.generated.models.*

interface CustomerControllerApi {
    /**
     * POST api/v1/customers/me/declaration
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @param declarationAcceptRequest 
     * @return [ApiResponseDeclarationResponse]
     */
    @POST("api/v1/customers/me/declaration")
    suspend fun acceptDeclaration1(@Body declarationAcceptRequest: DeclarationAcceptRequest): Response<ApiResponseDeclarationResponse>

    /**
     * POST api/v1/customers/me/ride-selfies
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @param uploadSelfieRequest  (optional)
     * @return [ApiResponseRideSelfieResponse]
     */
    @POST("api/v1/customers/me/ride-selfies")
    suspend fun captureRideSelfie(@Body uploadSelfieRequest: UploadSelfieRequest? = null): Response<ApiResponseRideSelfieResponse>

    /**
     * POST api/v1/customers/me/emergency-contacts
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @param emergencyContactRequest 
     * @return [ApiResponseEmergencyContactResponse]
     */
    @POST("api/v1/customers/me/emergency-contacts")
    suspend fun createEmergencyContact(@Body emergencyContactRequest: EmergencyContactRequest): Response<ApiResponseEmergencyContactResponse>

    /**
     * POST api/v1/customers/me/saved-locations
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @param savedLocationRequest 
     * @return [ApiResponseSavedLocationResponse]
     */
    @POST("api/v1/customers/me/saved-locations")
    suspend fun createSavedLocation(@Body savedLocationRequest: SavedLocationRequest): Response<ApiResponseSavedLocationResponse>

    /**
     * DELETE api/v1/customers/me/emergency-contacts/{id}
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @param id 
     * @return [ApiResponseVoid]
     */
    @DELETE("api/v1/customers/me/emergency-contacts/{id}")
    suspend fun deleteEmergencyContact(@Path("id") id: java.util.UUID): Response<ApiResponseVoid>

    /**
     * DELETE api/v1/customers/me/saved-locations/{id}
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @param id 
     * @return [ApiResponseVoid]
     */
    @DELETE("api/v1/customers/me/saved-locations/{id}")
    suspend fun deleteSavedLocation(@Path("id") id: java.util.UUID): Response<ApiResponseVoid>

    /**
     * GET api/v1/customers/me
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @return [ApiResponseCustomerProfileResponse]
     */
    @GET("api/v1/customers/me")
    suspend fun getProfile(): Response<ApiResponseCustomerProfileResponse>

    /**
     * GET api/v1/customers/me/emergency-contacts
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @return [ApiResponseListEmergencyContactResponse]
     */
    @GET("api/v1/customers/me/emergency-contacts")
    suspend fun listEmergencyContacts(): Response<ApiResponseListEmergencyContactResponse>

    /**
     * GET api/v1/customers/me/saved-locations
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @return [ApiResponseListSavedLocationResponse]
     */
    @GET("api/v1/customers/me/saved-locations")
    suspend fun listSavedLocations(): Response<ApiResponseListSavedLocationResponse>

    /**
     * GET api/v1/customers/me/rides
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @param page  (optional)
     * @param size  (optional)
     * @param sort  (optional)
     * @return [ApiResponsePageResponseBookingDetailResponse]
     */
    @GET("api/v1/customers/me/rides")
    suspend fun rideHistory(@Query("page") page: kotlin.Int? = null, @Query("size") size: kotlin.Int? = null, @Query("sort") sort: kotlin.collections.List<kotlin.String>? = null): Response<ApiResponsePageResponseBookingDetailResponse>

    /**
     * PUT api/v1/customers/me/emergency-contacts/{id}
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @param id 
     * @param emergencyContactRequest 
     * @return [ApiResponseEmergencyContactResponse]
     */
    @PUT("api/v1/customers/me/emergency-contacts/{id}")
    suspend fun updateEmergencyContact(@Path("id") id: java.util.UUID, @Body emergencyContactRequest: EmergencyContactRequest): Response<ApiResponseEmergencyContactResponse>

    /**
     * PUT api/v1/customers/me
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @param updateProfileRequest 
     * @return [ApiResponseCustomerProfileResponse]
     */
    @PUT("api/v1/customers/me")
    suspend fun updateProfile(@Body updateProfileRequest: UpdateProfileRequest): Response<ApiResponseCustomerProfileResponse>

    /**
     * PUT api/v1/customers/me/saved-locations/{id}
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @param id 
     * @param savedLocationRequest 
     * @return [ApiResponseSavedLocationResponse]
     */
    @PUT("api/v1/customers/me/saved-locations/{id}")
    suspend fun updateSavedLocation(@Path("id") id: java.util.UUID, @Body savedLocationRequest: SavedLocationRequest): Response<ApiResponseSavedLocationResponse>

}
