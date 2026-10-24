package com.ladygo.customer.booking

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class BookingBindsModule {
    @Binds
    @Singleton
    abstract fun bindLocationProvider(impl: FusedLocationProvider): LocationProvider

    @Binds
    @Singleton
    abstract fun bindAddressResolver(impl: PlatformAddressResolver): AddressResolver
}

@Module
@InstallIn(SingletonComponent::class)
object ClockModule {
    @dagger.Provides
    @Singleton
    fun provideClock(): java.time.Clock = java.time.Clock.systemUTC()
}
