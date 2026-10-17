package com.ladygo.customer.di

import com.ladygo.customer.BuildConfig
import com.ladygo.customer.api.generated.apis.AuthControllerApi
import com.ladygo.customer.api.generated.apis.BookingControllerApi
import com.ladygo.customer.api.generated.apis.CityControllerApi
import com.ladygo.customer.api.generated.apis.CustomerControllerApi
import com.ladygo.customer.api.generated.apis.SystemControllerApi
import com.ladygo.customer.api.generated.infrastructure.Serializer
import com.ladygo.customer.auth.AuthInterceptor
import com.ladygo.customer.auth.EncryptedTokenStore
import com.ladygo.customer.auth.TokenAuthenticator
import com.ladygo.customer.auth.TokenStore
import com.squareup.moshi.Moshi
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class PlainClient

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AuthenticatedClient

@Module
@InstallIn(SingletonComponent::class)
abstract class NetworkBindsModule {
    @Binds
    @Singleton
    abstract fun bindTokenStore(impl: EncryptedTokenStore): TokenStore
}

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    @Provides
    @Singleton
    fun provideMoshi(): Moshi = Serializer.moshi

    @Provides
    @PlainClient
    @Singleton
    fun providePlainOkHttpClient(): OkHttpClient = baseClientBuilder().build()

    @Provides
    @AuthenticatedClient
    @Singleton
    fun provideAuthenticatedOkHttpClient(
        authInterceptor: AuthInterceptor,
        tokenAuthenticator: TokenAuthenticator,
    ): OkHttpClient =
        baseClientBuilder()
            .addInterceptor(authInterceptor)
            .authenticator(tokenAuthenticator)
            .build()

    private fun baseClientBuilder(): OkHttpClient.Builder {
        val builder =
            OkHttpClient
                .Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
        if (BuildConfig.DEBUG) {
            // Debug builds only - release never logs request/response bodies (which would include
            // phone numbers and bearer tokens). The Authorization header is redacted even here: the
            // REST clients happen to avoid logging it only because AuthInterceptor is added after this
            // interceptor, so redacting is belt-and-braces: any client added later without that
            // interceptor must not be able to log the bearer token.
            builder.addInterceptor(
                HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.BODY
                    redactHeader("Authorization")
                },
            )
        }
        return builder
    }

    @Provides
    @PlainClient
    @Singleton
    fun providePlainRetrofit(
        @PlainClient client: OkHttpClient,
        moshi: Moshi,
    ): Retrofit =
        Retrofit
            .Builder()
            .baseUrl(BuildConfig.API_BASE_URL)
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()

    @Provides
    @AuthenticatedClient
    @Singleton
    fun provideAuthenticatedRetrofit(
        @AuthenticatedClient client: OkHttpClient,
        moshi: Moshi,
    ): Retrofit =
        Retrofit
            .Builder()
            .baseUrl(BuildConfig.API_BASE_URL)
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()

    @Provides
    @Singleton
    fun provideAuthControllerApi(
        @PlainClient retrofit: Retrofit,
    ): AuthControllerApi = retrofit.create(AuthControllerApi::class.java)

    @Provides
    @Singleton
    fun provideCustomerControllerApi(
        @AuthenticatedClient retrofit: Retrofit,
    ): CustomerControllerApi = retrofit.create(CustomerControllerApi::class.java)

    @Provides
    @Singleton
    fun provideBookingControllerApi(
        @AuthenticatedClient retrofit: Retrofit,
    ): BookingControllerApi = retrofit.create(BookingControllerApi::class.java)

    // Public (the service-area list is needed before any booking) but harmless to authenticate.
    @Provides
    @Singleton
    fun provideCityControllerApi(
        @AuthenticatedClient retrofit: Retrofit,
    ): CityControllerApi = retrofit.create(CityControllerApi::class.java)

    // Public, no auth required (SystemController has no @PreAuthorize) - used for the STOMP clock-skew
    // sample before any connection exists, so it deliberately goes through the plain client, not the
    // authenticated one.
    @Provides
    @Singleton
    fun provideSystemControllerApi(
        @PlainClient retrofit: Retrofit,
    ): SystemControllerApi = retrofit.create(SystemControllerApi::class.java)
}
