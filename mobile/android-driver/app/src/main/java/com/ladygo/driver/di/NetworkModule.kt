package com.ladygo.driver.di

import com.ladygo.driver.BuildConfig
import com.ladygo.driver.api.generated.apis.AuthControllerApi
import com.ladygo.driver.api.generated.apis.DriverBookingControllerApi
import com.ladygo.driver.api.generated.apis.DriverControllerApi
import com.ladygo.driver.api.generated.apis.DriverOfferControllerApi
import com.ladygo.driver.api.generated.apis.RideLifecycleControllerApi
import com.ladygo.driver.api.generated.apis.SystemControllerApi
import com.ladygo.driver.api.generated.infrastructure.Serializer
import com.ladygo.driver.auth.AuthInterceptor
import com.ladygo.driver.auth.EncryptedTokenStore
import com.ladygo.driver.auth.TokenAuthenticator
import com.ladygo.driver.auth.TokenStore
import com.ladygo.driver.realtime.KrossbowStompSessionFactory
import com.ladygo.driver.realtime.StompSessionFactory
import com.squareup.moshi.Moshi
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.hildan.krossbow.websocket.WebSocketClient
import org.hildan.krossbow.websocket.okhttp.OkHttpWebSocketClient
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

/** The [WebSocketClient]/[OkHttpClient] used only for the one long-lived STOMP connection - a
 *  separate instance from [PlainClient]/[AuthenticatedClient] because those are tuned for short
 *  REST calls (15s read timeout), which would silently kill a WebSocket that's expected to stay open
 *  for the driver's entire online session. A real OkHttp WebSocket [OkHttpClient.Builder.pingInterval]
 *  is configured (not just relying on STOMP's own heartbeat negotiation): confirmed against the
 *  backend's `WebSocketConfig` that it never configures broker heart-beat values, so the server
 *  always answers CONNECTED with `heart-beat:0,0` - STOMP-level heartbeats are never actually active
 *  on this connection today, making the WS-level ping the only real liveness signal (fresh-context
 *  review finding, CRITICAL - a half-open TCP connection would otherwise never be detected). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class StompWebSocketEngine

/** A shared, app-process-lifetime background scope for singletons that must keep running regardless
 *  of any Activity/ViewModel's own lifecycle - [StompConnectionManager] and
 *  [com.ladygo.driver.offer.OfferCoordinator] both use this. A real Hilt binding (not just a default
 *  parameter value) specifically so a test can substitute a [kotlinx.coroutines.test.TestScope]
 *  sharing its virtual-time scheduler, since Dagger/Hilt constructor injection ignores Kotlin default
 *  parameter values entirely (it always supplies every parameter explicitly from the graph). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AppScope

@Module
@InstallIn(SingletonComponent::class)
abstract class NetworkBindsModule {
    @Binds
    @Singleton
    abstract fun bindTokenStore(impl: EncryptedTokenStore): TokenStore

    @Binds
    @Singleton
    abstract fun bindStompSessionFactory(impl: KrossbowStompSessionFactory): StompSessionFactory
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
            // interceptor, but the STOMP WebSocket client (below) has no such interceptor at all and
            // sets its Authorization header directly on the handshake request, so without this redact
            // it would be the one client that actually logs the bearer token (fresh-context review
            // finding).
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
    fun provideDriverControllerApi(
        @AuthenticatedClient retrofit: Retrofit,
    ): DriverControllerApi = retrofit.create(DriverControllerApi::class.java)

    @Provides
    @Singleton
    fun provideDriverOfferControllerApi(
        @AuthenticatedClient retrofit: Retrofit,
    ): DriverOfferControllerApi = retrofit.create(DriverOfferControllerApi::class.java)

    @Provides
    @Singleton
    fun provideRideLifecycleControllerApi(
        @AuthenticatedClient retrofit: Retrofit,
    ): RideLifecycleControllerApi = retrofit.create(RideLifecycleControllerApi::class.java)

    @Provides
    @Singleton
    fun provideDriverBookingControllerApi(
        @AuthenticatedClient retrofit: Retrofit,
    ): DriverBookingControllerApi = retrofit.create(DriverBookingControllerApi::class.java)

    // Public, no auth required (SystemController has no @PreAuthorize) - used for the STOMP clock-skew
    // sample before any connection exists, so it deliberately goes through the plain client, not the
    // authenticated one.
    @Provides
    @Singleton
    fun provideSystemControllerApi(
        @PlainClient retrofit: Retrofit,
    ): SystemControllerApi = retrofit.create(SystemControllerApi::class.java)

    @Provides
    @StompWebSocketEngine
    @Singleton
    fun provideStompOkHttpClient(): OkHttpClient =
        baseClientBuilder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(STOMP_WS_PING_INTERVAL_SECONDS, TimeUnit.SECONDS)
            .build()

    @Provides
    @StompWebSocketEngine
    @Singleton
    fun provideStompWebSocketClient(
        @StompWebSocketEngine client: OkHttpClient,
    ): WebSocketClient = OkHttpWebSocketClient(client)

    @Provides
    @AppScope
    @Singleton
    fun provideAppScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private const val STOMP_WS_PING_INTERVAL_SECONDS = 20L
}
