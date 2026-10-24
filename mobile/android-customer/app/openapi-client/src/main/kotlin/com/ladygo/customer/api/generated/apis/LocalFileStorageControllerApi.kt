package com.ladygo.customer.api.generated.apis

import com.ladygo.customer.api.generated.infrastructure.CollectionFormats.*
import retrofit2.http.*
import retrofit2.Response
import okhttp3.RequestBody
import com.squareup.moshi.Json


interface LocalFileStorageControllerApi {
    /**
     * GET internal/files
     * 
     * 
     * Responses:
     *  - 200: OK
     *
     * @param key 
     * @param exp 
     * @param sig 
     * @return [kotlin.ByteArray]
     */
    @GET("internal/files")
    suspend fun serve(@Query("key") key: kotlin.String, @Query("exp") exp: kotlin.Long, @Query("sig") sig: kotlin.String): Response<kotlin.ByteArray>

}
