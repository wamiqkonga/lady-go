package com.ladygo.driver.testing

import com.ladygo.driver.realtime.StompSessionFactory
import org.hildan.krossbow.stomp.StompSession

/** Records every [connect] call (url + headers, so a test can assert on the bearer token used) and
 *  replays a queue of configured results - a session to hand back, or an exception to throw, exactly
 *  like a real handshake failure would. */
class FakeStompSessionFactory : StompSessionFactory {
    val connectCalls = mutableListOf<Pair<String, Map<String, String>>>()
    private val queuedResults = ArrayDeque<() -> StompSession>()

    fun enqueueSession(session: FakeStompSession = FakeStompSession()): FakeStompSession {
        queuedResults.addLast { session.asStompSession }
        return session
    }

    fun enqueueFailure(exception: Throwable) {
        queuedResults.addLast { throw exception }
    }

    override suspend fun connect(
        url: String,
        headers: Map<String, String>,
    ): StompSession {
        connectCalls.add(url to headers)
        val next = queuedResults.removeFirstOrNull() ?: error("FakeStompSessionFactory: no more queued connect() results")
        return next()
    }
}
