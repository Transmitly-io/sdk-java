package io.transmitly;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class TransmitlyClientTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void verifyWebhookChecksSignatureAndTimestamp() throws Exception {
        String secret = "whsec_test";
        String body = "{\"type\":\"email.delivered_to_mx\"}";
        long timestamp = Instant.now().getEpochSecond();
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = HexFormat.of().formatHex(
            mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8)));
        String header = "t=" + timestamp + ",v1=" + signature;

        assertTrue(TransmitlyClient.verifyWebhook(body, header, secret));
        assertFalse(TransmitlyClient.verifyWebhook(body, header, "wrong"));
        assertFalse(TransmitlyClient.verifyWebhook(body, "t=" + (timestamp - 400) + ",v1=" + signature, secret));
        assertFalse(TransmitlyClient.verifyWebhook(body, "garbage", secret));
    }

    @Test
    void sendPostsPayloadAndIdempotencyKey() throws Exception {
        AtomicReference<String> requestLine = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> idempotency = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();

        try (TestServer server = new TestServer(202,
            "{\"id\":\"msg_123\",\"status\":\"accepted\",\"recipients\":1}", exchange -> {
                requestLine.set(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
                authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                idempotency.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
                requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            })) {
            TransmitlyClient tx = new TransmitlyClient("txk_test", server.baseUrl());
            EmailAccepted accepted = tx.emails.send(new EmailMessage()
                .from("Notify <notify@acme.com>")
                .to(List.of("ada@example.net"))
                .subject("Welcome")
                .html("<p>Hi</p>")
                .tags(Map.of("flow", "onboarding")), "welcome-1");

            assertEquals("msg_123", accepted.id);
            assertEquals("accepted", accepted.status);
            assertEquals("POST /v1/emails", requestLine.get());
            assertEquals("Bearer txk_test", authorization.get());
            assertEquals("welcome-1", idempotency.get());

            JsonNode payload = json.readTree(requestBody.get());
            assertEquals("Notify <notify@acme.com>", payload.path("from").asText());
            assertEquals("ada@example.net", payload.path("to").get(0).asText());
            assertEquals("onboarding", payload.path("tags").path("flow").asText());
        }
    }

    @Test
    void errorsExposeApiDetails() throws Exception {
        try (TestServer server = new TestServer(409,
            "{\"error\":{\"code\":\"idempotency_conflict\",\"message\":\"Already used\"}}", exchange -> { })) {
            TransmitlyClient tx = new TransmitlyClient("txk_test", server.baseUrl());
            TransmitlyException error = assertThrows(TransmitlyException.class, () -> tx.emails.send(
                new EmailMessage()
                    .from("a@acme.com")
                    .to(List.of("b@example.net"))
                    .subject("s")
                    .text("t")));
            assertEquals(409, error.status());
            assertEquals("idempotency_conflict", error.code());
            assertEquals("Already used", error.getMessage());
        }
    }

    @Test
    void validationHappensBeforeNetwork() {
        TransmitlyClient tx = new TransmitlyClient("txk_test");
        assertThrows(IllegalArgumentException.class, () -> tx.emails.send(
            new EmailMessage().from("a@acme.com").to(List.of()).subject("s").text("t")));
        assertThrows(IllegalArgumentException.class, () -> tx.emails.send(
            new EmailMessage().from("a@acme.com").to(List.of("b@example.net")).subject("s")));
    }

    @Test
    void liveSmokeWhenConfigured() throws Exception {
        String apiKey = System.getenv("TRANSMITLY_API_KEY");
        org.junit.jupiter.api.Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank());
        String baseUrl = System.getenv().getOrDefault("TRANSMITLY_BASE_URL", "https://transmitly.io");
        TransmitlyClient tx = new TransmitlyClient(apiKey, baseUrl);
        EmailAccepted accepted = tx.emails.send(new EmailMessage()
            .from("Notify <notify@transmitly.io>")
            .to(List.of("sdk-java@transmitly.test"))
            .subject("Java SDK smoke")
            .text("hello")
            .testMode(true));
        assertEquals("accepted", accepted.status);
        assertEquals(Boolean.TRUE, accepted.testMode);
    }

    @FunctionalInterface
    interface Capture {
        void accept(com.sun.net.httpserver.HttpExchange exchange) throws java.io.IOException;
    }

    static final class TestServer implements AutoCloseable {
        private final HttpServer server;

        TestServer(int status, String response, Capture capture) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                try {
                    capture.accept(exchange);
                } catch (Exception ignored) {
                    // The assertions inspect the captured request; the response still needs to be written.
                }
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
