package com.ladygo.driver.network

/**
 * The accumulated pages of an infinite list - a tiny stand-in for the Paging 3 library, which this app does not use.
 * [append] drops any row whose key is already present (a ride finishing while the driver scrolls shifts the
 * server's pages, so the same row can come back on the next page) and keeps the first-seen order.
 */
data class PagedList<T, K>(
    val items: List<T> = emptyList(),
    /** The next page index to request; the first page is 0. */
    val nextPage: Int = 0,
    val hasMore: Boolean = true,
    private val keyOf: (T) -> K,
) {
    fun append(
        pageItems: List<T>,
        hasMore: Boolean,
    ): PagedList<T, K> {
        val seen = items.mapTo(HashSet()) { keyOf(it) }
        val fresh = pageItems.filter { seen.add(keyOf(it)) }
        return copy(items = items + fresh, nextPage = nextPage + 1, hasMore = hasMore)
    }

    /** A fresh list holding only [pageItems] as page 0 (pull to refresh). */
    fun restart(
        pageItems: List<T>,
        hasMore: Boolean,
    ): PagedList<T, K> = PagedList(keyOf = keyOf).append(pageItems, hasMore)
}
