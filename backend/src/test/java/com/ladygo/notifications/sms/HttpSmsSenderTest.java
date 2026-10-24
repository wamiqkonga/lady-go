package com.ladygo.notifications.sms;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** Local stub HTTP server (JDK's own, no new test dependency) standing in for a real SMS provider. */
class HttpSmsSenderTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private HttpServer startServer(AtomicInteger requestCount, int statusCode, String responseBody, long delayMs)
            throws IOException {
        HttpServer httpServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        httpServer.createContext("/send", exchange -> {
            requestCount.incrementAndGet();
            if (delayMs > 0) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] bytes = responseBody.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(statusCode, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        // A multi-threaded executor, not the default single-threaded one - otherwise a slow handler
        // for one connection (the timeout test's deliberate delay) serialises every later connection
        // behind it, so a client-side retry's new connection just queues instead of being handled.
        httpServer.setExecutor(Executors.newCachedThreadPool());
        httpServer.start();
        return httpServer;
    }

    private SmsSenderProperties propertiesFor(HttpServer httpServer, int maxRetries, long readTimeoutMs) {
        String baseUrl = "http://localhost:" + httpServer.getAddress().getPort() + "/send";
        return new SmsSenderProperties(
                baseUrl, "Authorization", "secret-token", "LADYGO", "tmpl-1", "entity-1",
                "{\"to\":\"{{to}}\",\"sender\":\"{{senderId}}\",\"code\":\"{{var:code}}\"}",
                "messageId", "errorCode", 2000, readTimeoutMs, maxRetries, 10, 5, 30000);
    }

    private SmsSenderProperties whatsAppPropertiesFor(HttpServer httpServer) {
        String baseUrl = "http://localhost:" + httpServer.getAddress().getPort() + "/send";
        return new SmsSenderProperties(
                baseUrl, "Authorization", "Bearer secret-token", "", "", "",
                "{\"messaging_product\":\"whatsapp\",\"to\":\"{{to}}\",\"type\":\"template\","
                        + "\"template\":{\"name\":\"otp\",\"language\":{\"code\":\"en\"}}}",
                "messages.0.id", "error.code", 2000, 2000, 0, 10, 5, 30000);
    }

    @Test
    void extractsNestedWhatsAppStyleMessageIdAndErrorCode() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        server = startServer(requests, 200, "{\"messages\":[{\"id\":\"wamid.ABC\"}]}", 0);
        SmsDeliveryResult ok = new HttpSmsSender(whatsAppPropertiesFor(server), new ObjectMapper())
                .send("+919900000001", "otp_code", Map.of("code", "123456"));
        assertThat(ok.accepted()).isTrue();
        assertThat(ok.providerMessageId()).isEqualTo("wamid.ABC");
        server.stop(0);

        server = startServer(requests, 400, "{\"error\":{\"code\":131030,\"message\":\"not allowed\"}}", 0);
        SmsDeliveryResult failed = new HttpSmsSender(whatsAppPropertiesFor(server), new ObjectMapper())
                .send("+919900000001", "otp_code", Map.of("code", "123456"));
        assertThat(failed.accepted()).isFalse();
        assertThat(failed.errorCode()).isEqualTo("131030");
    }

    @Test
    void missingNestedPathYieldsNullNotAnException() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        server = startServer(requests, 200, "{\"messages\":[]}", 0);
        SmsDeliveryResult ok = new HttpSmsSender(whatsAppPropertiesFor(server), new ObjectMapper())
                .send("+919900000001", "otp_code", Map.of("code", "123456"));
        assertThat(ok.accepted()).isTrue();
        assertThat(ok.providerMessageId()).isNull();
    }

    @Test
    void anAbsurdlyLargeArrayIndexInTheConfiguredPathIsNotFoundNotAnException() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        server = startServer(requests, 200, "{\"messages\":[{\"id\":\"x\"}]}", 0);
        String baseUrl = "http://localhost:" + server.getAddress().getPort() + "/send";
        SmsSenderProperties props = new SmsSenderProperties(
                baseUrl, "Authorization", "Bearer t", "", "", "", "{\"to\":\"{{to}}\"}",
                "messages.99999999999.id", "error.code", 2000, 2000, 0, 10, 5, 30000);

        SmsDeliveryResult result = new HttpSmsSender(props, new ObjectMapper())
                .send("+919900000001", "otp_code", Map.of("code", "123456"));

        assertThat(result.accepted()).isTrue();
        assertThat(result.providerMessageId()).isNull();
    }

    @Test
    void acceptsOnA200AndExtractsTheProviderMessageId() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        server = startServer(requests, 200, "{\"messageId\":\"provider-msg-123\"}", 0);

        HttpSmsSender sender = new HttpSmsSender(propertiesFor(server, 2, 2000), new ObjectMapper());
        SmsDeliveryResult result = sender.send("+919900000001", "otp_code", Map.of("code", "123456"));

        assertThat(result.accepted()).isTrue();
        assertThat(result.providerMessageId()).isEqualTo("provider-msg-123");
        assertThat(requests.get()).isEqualTo(1);
    }

    @Test
    void doesNotRetryOnA4xx() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        server = startServer(requests, 400, "{\"errorCode\":\"BAD_NUMBER\"}", 0);

        HttpSmsSender sender = new HttpSmsSender(propertiesFor(server, 2, 2000), new ObjectMapper());
        SmsDeliveryResult result = sender.send("+919900000001", "otp_code", Map.of("code", "123456"));

        assertThat(result.accepted()).isFalse();
        assertThat(result.errorCode()).isEqualTo("BAD_NUMBER");
        assertThat(requests.get()).isEqualTo(1); // a 4xx means the request is wrong - retrying repeats the same failure.
    }

    @Test
    void retriesOnA5xxThenFailsAfterExhaustingTheBudget() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        server = startServer(requests, 503, "{\"errorCode\":\"PROVIDER_DOWN\"}", 0);

        HttpSmsSender sender = new HttpSmsSender(propertiesFor(server, 2, 2000), new ObjectMapper());
        SmsDeliveryResult result = sender.send("+919900000001", "otp_code", Map.of("code", "123456"));

        assertThat(result.accepted()).isFalse();
        assertThat(result.errorCode()).isEqualTo("PROVIDER_DOWN");
        assertThat(requests.get()).isEqualTo(3); // 1 initial + 2 retries (maxRetries=2)
    }

    @Test
    void retriesOnATimeoutThenFails() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        // Server deliberately takes longer than the sender's own read timeout before responding.
        server = startServer(requests, 200, "{\"messageId\":\"too-late\"}", 500);

        HttpSmsSender sender = new HttpSmsSender(propertiesFor(server, 1, 100), new ObjectMapper());
        SmsDeliveryResult result = sender.send("+919900000001", "otp_code", Map.of("code", "123456"));

        assertThat(result.accepted()).isFalse();
        assertThat(result.errorCode()).isEqualTo("TIMEOUT");
        // The sender gives up after its own 100 ms read timeout, which on a slow CI runner can be before the
        // server thread has even started handling the retry; wait for it instead of reading the count at once.
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
        while (requests.get() < 2 && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(requests.get()).isEqualTo(2); // 1 initial + 1 retry (maxRetries=1)
    }

    @Test
    void openCircuitFailsFastWithNoHttpAttempt() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        server = startServer(requests, 503, "{}", 0);
        SmsSenderProperties properties = propertiesFor(server, 0, 2000);
        var clock = Clock.systemUTC();
        HttpSmsSender sender = new HttpSmsSender(properties, new ObjectMapper(), clock);

        for (int i = 0; i < properties.circuitBreakerFailureThreshold(); i++) {
            sender.send("+919900000001", "otp_code", Map.of("code", "123456"));
        }
        int requestsBeforeOpen = requests.get();

        SmsDeliveryResult result = sender.send("+919900000001", "otp_code", Map.of("code", "123456"));

        assertThat(result.errorCode()).isEqualTo("CIRCUIT_OPEN");
        assertThat(requests.get()).isEqualTo(requestsBeforeOpen); // no new HTTP attempt at all
    }

    @Test
    void neverLogsTheRenderedBodyOrAFullPhoneNumber() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        server = startServer(requests, 200, "{\"messageId\":\"provider-msg-123\"}", 0);

        Logger logger = (Logger) LoggerFactory.getLogger(HttpSmsSender.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.ALL);

        try {
            HttpSmsSender sender = new HttpSmsSender(propertiesFor(server, 0, 2000), new ObjectMapper());
            sender.send("+919900012345", "otp_code", Map.of("code", "654321"));
        } finally {
            logger.detachAppender(appender);
        }

        String allLogText = appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                .reduce("", (a, b) -> a + "\n" + b);
        assertThat(allLogText).doesNotContain("654321"); // the OTP code
        assertThat(allLogText).doesNotContain("919900012345"); // the full phone number
        assertThat(allLogText).contains("2345"); // the last-4-digit suffix is fine
    }
}
