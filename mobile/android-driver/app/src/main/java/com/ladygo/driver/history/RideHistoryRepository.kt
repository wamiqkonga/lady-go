package com.ladygo.driver.history

import com.ladygo.driver.api.generated.apis.DriverEarningsControllerApi
import com.ladygo.driver.network.errorMessageOrDefault
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

sealed interface RideHistoryOutcome {
    data class Success(
        val items: List<RideHistoryItem>,
        val hasMore: Boolean,
    ) : RideHistoryOutcome

    /** [message] is the server's own message when it sent one, otherwise a plain default. */
    data class Failed(
        val message: String,
    ) : RideHistoryOutcome
}

/** `GET /api/v1/drivers/me/rides?page&size` - the signed-in driver's own rides, newest first. */
@Singleton
class RideHistoryRepository
    @Inject
    constructor(
        private val ridesApi: DriverEarningsControllerApi,
    ) {
        suspend fun fetchPage(
            page: Int,
            size: Int = PAGE_SIZE,
        ): RideHistoryOutcome {
            val response =
                runCatching { ridesApi.listMyRides(page = page, size = size) }
                    .getOrElse { e ->
                        if (e is CancellationException) throw e
                        return RideHistoryOutcome.Failed("Could not reach the server. Check your connection and try again.")
                    }
            if (!response.isSuccessful) {
                return RideHistoryOutcome.Failed(errorMessageOrDefault(response, "Could not load your rides."))
            }
            val body = response.body()?.data ?: return RideHistoryOutcome.Failed("Unexpected response from the server.")
            val rows = body.content.orEmpty()
            val totalPages = body.totalPages
            // totalPages is the server's own answer; without it, a full page is the only hint there may be more.
            val hasMore = if (totalPages != null) page + 1 < totalPages else rows.size >= size
            return RideHistoryOutcome.Success(rows.mapNotNull { it.toItem() }, hasMore)
        }

        companion object {
            const val PAGE_SIZE = 20
        }
    }
