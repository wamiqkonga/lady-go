package com.ladygo.driver.realtime

/** `http(s)://host[:port]` -> `ws(s)://host[:port]/ws` - the backend's one STOMP endpoint
 *  (`WebSocketConfig.registerStompEndpoints`: `registry.addEndpoint("/ws")`). Pure string mapping,
 *  no Android dependency, so it's directly unit-testable. */
fun wsUrlFromApiBaseUrl(apiBaseUrl: String): String =
    apiBaseUrl
        .replaceFirst("https://", "wss://")
        .replaceFirst("http://", "ws://")
        .trimEnd('/') + "/ws"
