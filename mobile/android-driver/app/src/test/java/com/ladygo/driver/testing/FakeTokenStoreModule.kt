package com.ladygo.driver.testing

import com.ladygo.driver.auth.TokenStore
import com.ladygo.driver.di.NetworkBindsModule
import com.ladygo.driver.realtime.StompSessionFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import javax.inject.Singleton

/**
 * Replaces the real [EncryptedTokenStore][com.ladygo.driver.auth.EncryptedTokenStore] binding for
 * every Hilt-based Robolectric test in this module - constructing the real one touches the Android
 * Keystore, which Robolectric's JVM has no provider for (confirmed by a real
 * `NoSuchAlgorithmException: AndroidKeyStore KeyStore not available` building
 * [com.ladygo.driver.location.LocationForegroundService], which pulls in `SessionManager`, which
 * reads a token at construction time). A `@HiltAndroidTest` class gets this automatically; nothing
 * else needs to reference it.
 *
 * Replacing [NetworkBindsModule] wholesale (not just overriding the one binding that needed it)
 * means every other binding that module carries has to be re-provided here too - [StompSessionFactory]
 * included, even though no `@HiltAndroidTest` in this module actually exercises it; Dagger validates
 * the whole graph regardless of which parts of it a given test touches.
 */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [NetworkBindsModule::class])
object FakeTokenStoreModule {
    @Provides
    @Singleton
    fun provideTokenStore(): TokenStore = FakeTokenStore()

    @Provides
    @Singleton
    fun provideStompSessionFactory(): StompSessionFactory = FakeStompSessionFactory()
}
