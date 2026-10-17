package com.ladygo.customer.testing

import com.ladygo.customer.auth.TokenStore
import com.ladygo.customer.di.NetworkBindsModule
import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import javax.inject.Singleton

/**
 * Replaces the real [EncryptedTokenStore][com.ladygo.customer.auth.EncryptedTokenStore] binding for
 * every Hilt-based Robolectric test in this module - constructing the real one touches the Android
 * Keystore, which Robolectric's JVM has no provider for (`NoSuchAlgorithmException: AndroidKeyStore
 * KeyStore not available`). A `@HiltAndroidTest` class gets this automatically; nothing else needs
 * to reference it.
 */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [NetworkBindsModule::class])
object FakeTokenStoreModule {
    @Provides
    @Singleton
    fun provideTokenStore(): TokenStore = FakeTokenStore()
}
