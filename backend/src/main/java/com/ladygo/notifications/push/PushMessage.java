package com.ladygo.notifications.push;

import java.util.Map;

/**
 * What a push carries. The title and body are generic on purpose: they show on a locked screen, so they never hold
 * an address, name, PIN or fare. The {@code data} map holds identifiers only; the app fetches details over the API.
 */
public record PushMessage(String eventType, String title, String body, Map<String, String> data) {
}
