package com.ladygo.notifications.push;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The request shape and the response handling; the network and Google's token endpoint are not exercised. */
class FcmPushSenderTest {

    private static FcmPushSender sender;

    @BeforeAll
    static void build() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(pair.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
        String json = new ObjectMapper().writeValueAsString(Map.of(
                "client_email", "svc@example.iam.gserviceaccount.com", "private_key", pem,
                "token_uri", "https://oauth2.googleapis.com/token"));
        sender = new FcmPushSender(new PushProperties("fcm", "demo-project", json));
    }

    @Test
    void theRequestBodyCarriesTheNotificationHighPriorityAndOnlyIdentifiersAsData() throws Exception {
        PushMessage message = new PushMessage("RATE_RIDE", "How was your ride?", "Tell us.", Map.of("bookingId", "b-1"));

        JsonNode body = new ObjectMapper().readTree(sender.requestBody("device-token", message)).path("message");

        assertThat(body.path("token").asText()).isEqualTo("device-token");
        assertThat(body.path("notification").path("title").asText()).isEqualTo("How was your ride?");
        assertThat(body.path("data").path("bookingId").asText()).isEqualTo("b-1");
        assertThat(body.path("data").path("type").asText()).isEqualTo("RATE_RIDE");
        assertThat(body.path("android").path("priority").asText()).isEqualTo("HIGH");
    }

    @Test
    void successDeadTokenAndOtherFailuresAreTold() {
        assertThat(sender.interpret(200, "{\"name\":\"projects/p/messages/1\"}").status())
                .isEqualTo(PushDeliveryResult.Status.SENT);
        assertThat(sender.interpret(404, "{\"error\":{\"status\":\"NOT_FOUND\"}}").status())
                .isEqualTo(PushDeliveryResult.Status.INVALID_TOKEN);
        assertThat(sender.interpret(400, "{\"error\":{\"status\":\"UNREGISTERED\"}}").status())
                .isEqualTo(PushDeliveryResult.Status.INVALID_TOKEN);
        PushDeliveryResult failed = sender.interpret(503, "not json");
        assertThat(failed.status()).isEqualTo(PushDeliveryResult.Status.FAILED);
        assertThat(failed.errorCode()).isEqualTo("HTTP_503");
    }
}
