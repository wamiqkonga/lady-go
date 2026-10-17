package com.ladygo.driver.realtime

import org.junit.Assert.assertEquals
import org.junit.Test

class WsUrlTest {
    @Test
    fun mapsHttpToWs() {
        assertEquals("ws://10.0.2.2:8080/ws", wsUrlFromApiBaseUrl("http://10.0.2.2:8080"))
    }

    @Test
    fun mapsHttpsToWss() {
        assertEquals("wss://api.ladygo.example/ws", wsUrlFromApiBaseUrl("https://api.ladygo.example"))
    }

    @Test
    fun stripsATrailingSlashBeforeAppendingWs() {
        assertEquals("wss://api.ladygo.example/ws", wsUrlFromApiBaseUrl("https://api.ladygo.example/"))
    }
}
