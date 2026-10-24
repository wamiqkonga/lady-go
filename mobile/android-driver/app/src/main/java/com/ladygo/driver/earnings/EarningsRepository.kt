package com.ladygo.driver.earnings

import com.ladygo.driver.api.generated.apis.DriverEarningsControllerApi
import com.ladygo.driver.api.generated.models.DriverEarningsResponse
import com.ladygo.driver.network.errorMessageOrDefault
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

sealed interface EarningsOutcome {
    data class Success(
        val earnings: DriverEarningsResponse,
    ) : EarningsOutcome

    /** [message] is the server's own message when it sent one, otherwise a plain default. */
    data class Failed(
        val message: String,
    ) : EarningsOutcome
}

/** `GET /api/v1/drivers/me/earnings?from&to` - the signed-in driver's own earnings; dates are ISO local dates. */
@Singleton
class EarningsRepository
    @Inject
    constructor(
        private val earningsApi: DriverEarningsControllerApi,
    ) {
        suspend fun fetch(range: DateRange): EarningsOutcome {
            val response =
                runCatching { earningsApi.getMyEarnings(from = range.from.toString(), to = range.to.toString()) }
                    .getOrElse { e ->
                        if (e is CancellationException) throw e
                        return EarningsOutcome.Failed(NETWORK_ERROR_MESSAGE)
                    }
            if (!response.isSuccessful) {
                return EarningsOutcome.Failed(errorMessageOrDefault(response, "Could not load your earnings."))
            }
            val earnings = response.body()?.data ?: return EarningsOutcome.Failed("Unexpected response from the server.")
            return EarningsOutcome.Success(earnings)
        }

        private companion object {
            const val NETWORK_ERROR_MESSAGE = "Could not reach the server. Check your connection and try again."
        }
    }
