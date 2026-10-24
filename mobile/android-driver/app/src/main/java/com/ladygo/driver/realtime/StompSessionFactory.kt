package com.ladygo.driver.realtime

import com.ladygo.driver.di.StompWebSocketEngine
import org.hildan.krossbow.stomp.StompSession
import org.hildan.krossbow.stomp.config.HeartBeat
import org.hildan.krossbow.stomp.config.StompConfig
import org.hildan.krossbow.stomp.stomp
import org.hildan.krossbow.websocket.WebSocketClient
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds

/**
 * The seam between [StompConnectionManager] and the real Krossbow/OkHttp machinery - lets tests
 * substitute a hand-written fake [StompSession] (design doc §5: "a fake STOMP client... a simple
 * Flow<StompEvent> source the test pushes into") instead of having to simulate the real STOMP wire
 * protocol over a fake [org.hildan.krossbow.websocket.WebSocketConnection].
 */
interface StompSessionFactory {
    /** @throws org.hildan.krossbow.websocket.WebSocketConnectionException if the handshake itself is
     *  rejected (e.g. 401 - see [StompConnectionManager]'s refresh-then-retry handling of exactly
     *  that). */
    suspend fun connect(
        url: String,
        headers: Map<String, String>,
    ): StompSession
}

@Singleton
class KrossbowStompSessionFactory
    @Inject
    constructor(
        @StompWebSocketEngine private val webSocketClient: WebSocketClient,
    ) : StompSessionFactory {
        override suspend fun connect(
            url: String,
            headers: Map<String, String>,
        ): StompSession =
            webSocketClient
                .connect(url, headers = headers)
                .stomp(StompConfig().apply { heartBeat = HeartBeat(HEARTBEAT, HEARTBEAT) })

        private companion object {
            val HEARTBEAT = 10.seconds
        }
    }
