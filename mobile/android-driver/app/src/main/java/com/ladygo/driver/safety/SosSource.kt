package com.ladygo.driver.safety

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.ladygo.driver.api.generated.apis.SosControllerApi
import com.ladygo.driver.api.generated.models.SosRequest
import com.ladygo.driver.network.errorMessageOrDefault
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

sealed interface SosOutcome {
    data object Sent : SosOutcome

    data object PermissionDenied : SosOutcome

    data object NoLocation : SosOutcome

    data class Failed(
        val message: String,
    ) : SosOutcome
}

/** An interface so the SOS flow is testable without Play services or a network. */
interface SosSource {
    /** Gets a fresh fix and tells Lady Go's safety team. Never throws; every failure is an [SosOutcome]. */
    suspend fun send(bookingId: UUID?): SosOutcome
}

@Singleton
class ServerSosSource
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val api: SosControllerApi,
    ) : SosSource {
        override suspend fun send(bookingId: UUID?): SosOutcome {
            val granted =
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED
            if (!granted) return SosOutcome.PermissionDenied
            val here = currentFix() ?: return SosOutcome.NoLocation
            val response =
                try {
                    api.driverSos(SosRequest(here.first, here.second, bookingId))
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    return SosOutcome.Failed("Could not reach Lady Go.")
                }
            return if (response.isSuccessful) {
                SosOutcome.Sent
            } else {
                SosOutcome.Failed(errorMessageOrDefault(response, "Could not reach Lady Go."))
            }
        }

        private suspend fun currentFix(): Pair<Double, Double>? {
            val tokenSource = CancellationTokenSource()
            return suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { tokenSource.cancel() }
                @Suppress("MissingPermission") // checked by the caller
                LocationServices
                    .getFusedLocationProviderClient(context)
                    .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, tokenSource.token)
                    .addOnSuccessListener { location -> continuation.resume(location?.let { it.latitude to it.longitude }) }
                    .addOnFailureListener { continuation.resume(null) }
            }
        }
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class SafetyBindsModule {
    @Binds
    @Singleton
    abstract fun bindSosSource(impl: ServerSosSource): SosSource
}
