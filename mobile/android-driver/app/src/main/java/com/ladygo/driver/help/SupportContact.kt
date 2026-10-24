package com.ladygo.driver.help

import com.ladygo.driver.api.generated.apis.SupportContactControllerApi
import com.ladygo.driver.api.generated.models.SupportContactResponse
import com.ladygo.driver.network.errorMessageOrDefault
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/** The support numbers from server config. Either may be absent; [whatsappDigits] has no `+`, spaces or dashes (wa.me wants digits only). */
data class SupportContact(
    val phone: String?,
    val whatsappDigits: String?,
) {
    val isEmpty: Boolean get() = phone == null && whatsappDigits == null
}

fun SupportContactResponse?.toContact(): SupportContact =
    SupportContact(
        phone = this?.phone?.trim()?.takeIf { it.isNotEmpty() },
        whatsappDigits = this?.whatsapp?.filter { it.isDigit() }?.takeIf { it.isNotEmpty() },
    )

sealed interface SupportOutcome {
    data class Success(
        val contact: SupportContact,
    ) : SupportOutcome

    data class Failed(
        val message: String,
    ) : SupportOutcome
}

/** `GET /api/v1/support/contact`. Nothing here is ever logged. */
@Singleton
class SupportRepository
    @Inject
    constructor(
        private val supportApi: SupportContactControllerApi,
    ) {
        suspend fun fetch(): SupportOutcome {
            val response =
                runCatching { supportApi.getSupportContact() }.getOrElse { e ->
                    if (e is CancellationException) throw e
                    return SupportOutcome.Failed("Could not reach the server. Check your connection and try again.")
                }
            if (!response.isSuccessful) return SupportOutcome.Failed(errorMessageOrDefault(response, "Could not load the support details."))
            return SupportOutcome.Success(response.body()?.data.toContact())
        }
    }
