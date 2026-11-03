package com.ladygo.customer.safety

import com.ladygo.customer.api.generated.apis.BookingControllerApi
import com.ladygo.customer.api.generated.apis.CustomerControllerApi
import com.ladygo.customer.api.generated.apis.SosControllerApi
import com.ladygo.customer.api.generated.models.EmergencyContactRequest
import com.ladygo.customer.api.generated.models.SosRequest
import com.ladygo.customer.booking.GeoPoint
import com.ladygo.customer.network.errorMessageOrDefault
import kotlinx.coroutines.CancellationException
import retrofit2.Response
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class TrustedContact(
    val id: UUID,
    val name: String,
    val phone: String,
    val relationship: String?,
)

sealed interface SafetyResult<out T> {
    data class Ok<T>(
        val value: T,
    ) : SafetyResult<T>

    data class Failed(
        val message: String,
    ) : SafetyResult<Nothing>
}

/** Trusted contacts, SOS and trip sharing. An interface so the screens' logic is testable without a network. */
interface SafetySource {
    suspend fun contacts(): SafetyResult<List<TrustedContact>>

    suspend fun addContact(
        name: String,
        phone: String,
        relationship: String?,
    ): SafetyResult<TrustedContact>

    suspend fun deleteContact(id: UUID): SafetyResult<Unit>

    /** Tells Lady Go's safety team. Never fails silently: a [SafetyResult.Failed] must be shown to the rider. */
    suspend fun sos(
        at: GeoPoint,
        bookingId: UUID?,
    ): SafetyResult<Unit>

    /** Creates a trip-share link and returns its raw token (shown once, never stored). */
    suspend fun shareTrip(bookingId: UUID): SafetyResult<String>
}

@Singleton
class ServerSafetySource
    @Inject
    constructor(
        private val customers: CustomerControllerApi,
        private val sos: SosControllerApi,
        private val bookings: BookingControllerApi,
    ) : SafetySource {
        override suspend fun contacts(): SafetyResult<List<TrustedContact>> =
            call("Could not load your contacts.", { customers.listEmergencyContacts() }) { body ->
                body.data.orEmpty().mapNotNull { toContact(it.id, it.name, it.phone, it.relationship) }
            }

        override suspend fun addContact(
            name: String,
            phone: String,
            relationship: String?,
        ): SafetyResult<TrustedContact> =
            call(
                "Could not save this contact.",
                { customers.createEmergencyContact(EmergencyContactRequest(name, phone, relationship)) },
            ) { body ->
                val d = body.data
                toContact(d?.id, d?.name, d?.phone, d?.relationship) ?: error("empty contact")
            }

        override suspend fun deleteContact(id: UUID): SafetyResult<Unit> =
            call("Could not remove this contact.", { customers.deleteEmergencyContact(id) }) { }

        override suspend fun sos(
            at: GeoPoint,
            bookingId: UUID?,
        ): SafetyResult<Unit> = call("Could not reach Lady Go.", { sos.customerSos(SosRequest(at.latitude, at.longitude, bookingId)) }) { }

        override suspend fun shareTrip(bookingId: UUID): SafetyResult<String> =
            call("Could not create a share link.", { bookings.createTripShare(bookingId) }) { body ->
                body.data?.token ?: error("no token")
            }

        private fun toContact(
            id: UUID?,
            name: String?,
            phone: String?,
            relationship: String?,
        ): TrustedContact? = if (id != null && name != null && phone != null) TrustedContact(id, name, phone, relationship) else null

        private suspend fun <B, R> call(
            fallback: String,
            request: suspend () -> Response<B>,
            map: (B) -> R,
        ): SafetyResult<R> {
            val response =
                try {
                    request()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    return SafetyResult.Failed(fallback)
                }
            if (!response.isSuccessful) return SafetyResult.Failed(errorMessageOrDefault(response, fallback))
            return try {
                @Suppress("UNCHECKED_CAST")
                SafetyResult.Ok(map((response.body() ?: Unit) as B))
            } catch (_: IllegalStateException) {
                SafetyResult.Failed(fallback)
            }
        }
    }
