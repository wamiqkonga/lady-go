package com.ladygo.driver.testing

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import org.hildan.krossbow.stomp.StompReceipt
import org.hildan.krossbow.stomp.StompSession
import org.hildan.krossbow.stomp.UnsafeStompSessionApi
import org.hildan.krossbow.stomp.frame.FrameBody
import org.hildan.krossbow.stomp.frame.StompFrame
import org.hildan.krossbow.stomp.headers.StompMessageHeaders
import org.hildan.krossbow.stomp.headers.StompSendHeaders
import org.hildan.krossbow.stomp.headers.StompSubscribeHeaders
import java.io.IOException

/**
 * Hand-written fake [StompSession] (design doc §5: "a fake STOMP client... a simple Flow<StompEvent>
 * source the test pushes into") - lets [com.ladygo.driver.realtime.StompConnectionManagerTest] push
 * offer/booking frames and simulate a forced disconnect without simulating the real STOMP wire
 * protocol over a fake WebSocket connection.
 */
class FakeStompSession {
    private val messageFlows = mutableMapOf<String, MutableSharedFlow<StompFrame.Message>>()
    private val disconnectSignal = CompletableDeferred<Throwable>()
    var disconnectCallCount: Int = 0
        private set

    private fun flowFor(destination: String) = messageFlows.getOrPut(destination) { MutableSharedFlow(extraBufferCapacity = 16) }

    suspend fun pushText(
        destination: String,
        body: String,
    ) {
        flowFor(destination).emit(
            StompFrame.Message(
                headers =
                    StompMessageHeaders(
                        destination = destination,
                        messageId = "fake-message-id",
                        subscription = "fake-subscription-id",
                    ),
                body = FrameBody.Text(body),
            ),
        )
    }

    /** Simulates the server closing the connection (e.g. the token-expiry sweep, or any other drop) -
     *  every active [subscribe] flow throws, matching [StompSession]'s own documented behavior for an
     *  "unexpected web socket closure." */
    fun simulateDisconnect(cause: Throwable = IOException("fake connection lost")) {
        disconnectSignal.complete(cause)
    }

    val asStompSession: StompSession =
        object : StompSession {
            override suspend fun send(
                headers: StompSendHeaders,
                body: FrameBody?,
            ): StompReceipt? = null

            // `emit` must be called from the same coroutine running this flow{} builder (Flow's
            // context-preservation contract) - a naive `launch { ... collect { emit(it) } }` forwarder
            // violates that (emits from a *child* coroutine) and throws "Flow invariant is violated"
            // the moment anything is pushed. The disconnect-watcher is the one that needs its own
            // child coroutine instead: it only ever throws (never emits), and being a sibling of the
            // direct `collect` below, its throw cancels that collect and propagates out correctly.
            override suspend fun subscribe(headers: StompSubscribeHeaders): Flow<StompFrame.Message> =
                flow {
                    coroutineScope {
                        launch { throw disconnectSignal.await() }
                        flowFor(headers.destination).collect { emit(it) }
                    }
                }

            @OptIn(UnsafeStompSessionApi::class)
            override suspend fun sendRawFrameAndMaybeAwaitReceipt(frame: StompFrame): StompReceipt? = null

            override suspend fun disconnect() {
                disconnectCallCount++
            }
        }
}
