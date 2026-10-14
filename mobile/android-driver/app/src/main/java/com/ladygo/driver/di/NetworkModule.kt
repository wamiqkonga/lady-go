package com.ladygo.driver.di

import com.ladygo.driver.BuildConfig
import com.ladygo.driver.api.generated.apis.AuthControllerApi
import com.ladygo.driver.api.generated.apis.DriverControllerApi
import com.ladygo.driver.api.generated.infrastructure.Serializer
import com.ladygo.driver.auth.AuthInterceptor
import com.ladygo.driver.auth.EncryptedTokenStore
import com.ladygo.driver.auth.TokenAuthenticator
import com.ladygo.driver.auth.TokenStore
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
            // phone numbers and bearer tokens).
            builder.addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BODY })
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
    fun provideDriverControllerApi(
        @AuthenticatedClient retrofit: Retrofit,
    ): DriverControllerApi = retrofit.create(DriverControllerApi::class.java)
}
