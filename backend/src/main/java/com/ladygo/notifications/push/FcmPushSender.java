package com.ladygo.notifications.push;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Firebase Cloud Messaging over the HTTP v1 API. Authenticates as a service account with a signed JWT exchanged for
 * a short-lived OAuth token (cached), so the only secret is the service-account JSON from the environment. Never logs
 * a device token, title or body.
 */
@Component
@ConditionalOnProperty(name = "ladygo.push.provider", havingValue = "fcm")
public class FcmPushSender implements PushSender {

    private static final Logger log = LoggerFactory.getLogger(FcmPushSender.class);
    private static final String SCOPE = "https://www.googleapis.com/auth/firebase.messaging";
    private static final Duration TOKEN_REFRESH_MARGIN = Duration.ofMinutes(5);

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String sendUrl;
    private final String clientEmail;
    private final String tokenUri;
    private final PrivateKey privateKey;

    private String accessToken;
    private Instant accessTokenExpiresAt = Instant.EPOCH;

    public FcmPushSender(PushProperties properties) throws Exception {
        JsonNode account = mapper.readTree(properties.fcmServiceAccountJson());
        this.clientEmail = account.path("client_email").asText();
        this.tokenUri = account.path("token_uri").asText("https://oauth2.googleapis.com/token");
        this.privateKey = parsePrivateKey(account.path("private_key").asText());
        this.sendUrl = "https://fcm.googleapis.com/v1/projects/" + properties.fcmProjectId() + "/messages:send";
    }

    @Override
    public PushDeliveryResult send(String deviceToken, PushMessage message) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(sendUrl))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + accessToken())
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody(deviceToken, message)))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            return interpret(response.statusCode(), response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return PushDeliveryResult.failed("INTERRUPTED");
        } catch (Exception e) {
            log.warn("operation=fcm_send outcome=error type={}", e.getClass().getSimpleName());
            return PushDeliveryResult.failed("IO_ERROR");
        }
    }

    /** The FCM v1 request body: high priority so a ride alert wakes a dozing phone. */
    String requestBody(String deviceToken, PushMessage message) throws Exception {
        Map<String, Object> notification = new LinkedHashMap<>();
        notification.put("title", message.title());
        notification.put("body", message.body());
        Map<String, Object> data = new LinkedHashMap<>(message.data());
        data.put("type", message.eventType());
        Map<String, Object> android = Map.of("priority", "HIGH");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("token", deviceToken);
        body.put("notification", notification);
        body.put("data", data);
        body.put("android", android);
        return mapper.writeValueAsString(Map.of("message", body));
    }

    /** 2xx is sent; 404 or an UNREGISTERED / INVALID_ARGUMENT token error means the token is dead. */
    PushDeliveryResult interpret(int status, String responseBody) {
        if (status / 100 == 2) {
            return PushDeliveryResult.sent(extractName(responseBody));
        }
        String errorStatus = errorStatus(responseBody);
        if (status == 404 || "UNREGISTERED".equals(errorStatus)) {
            return PushDeliveryResult.invalidToken();
        }
        return PushDeliveryResult.failed(errorStatus != null ? errorStatus : "HTTP_" + status);
    }

    private String extractName(String responseBody) {
        try {
            return mapper.readTree(responseBody).path("name").asText(null);
        } catch (Exception e) {
            return null;
        }
    }

    private String errorStatus(String responseBody) {
        try {
            return mapper.readTree(responseBody).path("error").path("status").asText(null);
        } catch (Exception e) {
            return null;
        }
    }

    private synchronized String accessToken() throws Exception {
        if (accessToken != null && Instant.now().isBefore(accessTokenExpiresAt.minus(TOKEN_REFRESH_MARGIN))) {
            return accessToken;
        }
        Instant now = Instant.now();
        String assertion = Jwts.builder()
                .issuer(clientEmail)
                .claim("scope", SCOPE)
                .audience().add(tokenUri).and()
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(3600)))
                .signWith(privateKey, Jwts.SIG.RS256)
                .compact();
        String form = "grant_type=" + java.net.URLEncoder.encode(
                "urn:ietf:params:oauth:grant-type:jwt-bearer", StandardCharsets.UTF_8) + "&assertion=" + assertion;
        HttpRequest request = HttpRequest.newBuilder(URI.create(tokenUri))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("FCM token exchange failed with HTTP " + response.statusCode());
        }
        JsonNode json = mapper.readTree(response.body());
        accessToken = json.path("access_token").asText();
        accessTokenExpiresAt = now.plusSeconds(json.path("expires_in").asLong(3600));
        return accessToken;
    }

    private static PrivateKey parsePrivateKey(String pem) throws Exception {
        String base64 = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        return KeyFactory.getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));
    }
}
