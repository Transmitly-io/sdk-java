package io.transmitly;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Dependency-light Java client for the Transmitly email API.
 *
 * <pre>{@code
 * var tx = new TransmitlyClient(System.getenv("TRANSMITLY_API_KEY"));
 * var accepted = tx.emails.send(new EmailMessage()
 *     .from("Notify <notify@mail.acme.com>")
 *     .to(List.of("ada@example.net"))
 *     .subject("Welcome")
 *     .html("<p>Your workspace is ready.</p>"));
 * }</pre>
 */
public final class TransmitlyClient {
    public static final String DEFAULT_BASE_URL = "https://transmitly.io";
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(15);

    private final String apiKey;
    private final String baseUrl;
    private final Duration timeout;
    private final HttpClient http;
    private final ObjectMapper json;
    private final RequestExecutor executor;

    public final Emails emails;
    public final Messages messages;
    public final Domains domains;
    public final Suppressions suppressions;
    public final Webhooks webhooks;
    public final Inbound inbound;
    public final Usage usage;
    public final Events events;

    public TransmitlyClient(String apiKey) {
        this(apiKey, DEFAULT_BASE_URL, DEFAULT_TIMEOUT, null);
    }

    public TransmitlyClient(String apiKey, String baseUrl) {
        this(apiKey, baseUrl, DEFAULT_TIMEOUT, null);
    }

    public TransmitlyClient(String apiKey, String baseUrl, Duration timeout, HttpClient httpClient) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("apiKey is required");
        }
        this.apiKey = apiKey;
        this.baseUrl = (baseUrl == null ? DEFAULT_BASE_URL : baseUrl).replaceAll("/+$", "");
        this.timeout = timeout == null ? DEFAULT_TIMEOUT : timeout;
        this.http = httpClient == null
            ? HttpClient.newBuilder().connectTimeout(this.timeout).build()
            : httpClient;
        this.json = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.executor = new RequestExecutor();
        this.emails = new Emails();
        this.messages = new Messages();
        this.domains = new Domains();
        this.suppressions = new Suppressions();
        this.webhooks = new Webhooks();
        this.inbound = new Inbound();
        this.usage = new Usage();
        this.events = new Events();
    }

    public JsonNode health() {
        return executor.call("GET", "/v1/health", null, Map.of(), Map.of(), false);
    }

    /**
     * Verify a webhook delivery. Pass the raw request body (not a parsed object),
     * the Transmitly-Signature header and the endpoint signing secret.
     */
    public static boolean verifyWebhook(String rawBody, String signatureHeader, String secret) {
        return verifyWebhook(rawBody, signatureHeader, secret, 300);
    }

    public static boolean verifyWebhook(String rawBody, String signatureHeader, String secret, int toleranceSeconds) {
        if (signatureHeader == null || signatureHeader.isEmpty()) {
            return false;
        }
        Long timestamp = null;
        String signature = null;
        for (String part : signatureHeader.split(",")) {
            String[] pair = part.trim().split("=", 2);
            if (pair.length != 2) {
                continue;
            }
            if (pair[0].equals("t")) {
                try {
                    timestamp = Long.parseLong(pair[1]);
                } catch (NumberFormatException ignored) {
                    return false;
                }
            } else if (pair[0].equals("v1")) {
                signature = pair[1];
            }
        }
        if (timestamp == null || signature == null) {
            return false;
        }
        if (toleranceSeconds > 0
            && Math.abs(Instant.now().getEpochSecond() - timestamp) > toleranceSeconds) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String expected = HexFormat.of().formatHex(
                mac.doFinal((timestamp + "." + rawBody).getBytes(StandardCharsets.UTF_8)));
            return MessageDigest.isEqual(
                signature.getBytes(StandardCharsets.US_ASCII),
                expected.getBytes(StandardCharsets.US_ASCII));
        } catch (Exception error) {
            return false;
        }
    }

    private URI uri(String path, Map<String, String> query) {
        StringBuilder url = new StringBuilder(baseUrl).append(path);
        if (query != null && !query.isEmpty()) {
            StringBuilder qs = new StringBuilder();
            for (Map.Entry<String, String> pair : query.entrySet()) {
                if (pair.getValue() == null || pair.getValue().isEmpty()) {
                    continue;
                }
                if (!qs.isEmpty()) {
                    qs.append('&');
                }
                qs.append(URLEncoder.encode(pair.getKey(), StandardCharsets.UTF_8))
                  .append('=')
                  .append(URLEncoder.encode(pair.getValue(), StandardCharsets.UTF_8));
            }
            if (!qs.isEmpty()) {
                url.append('?').append(qs);
            }
        }
        return URI.create(url.toString());
    }

    private final class RequestExecutor {
        JsonNode call(String method, String path, Object body, Map<String, String> query,
                      Map<String, String> headers, boolean auth) {
            try {
                HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path, query))
                    .timeout(timeout)
                    .header("Accept", "application/json");
                if (auth) {
                    builder.header("Authorization", "Bearer " + apiKey);
                }
                for (Map.Entry<String, String> header : headers.entrySet()) {
                    builder.header(header.getKey(), header.getValue());
                }
                if (body == null) {
                    builder.method(method, HttpRequest.BodyPublishers.noBody());
                } else {
                    builder.header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
                }

                HttpResponse<String> response = http.send(
                    builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                String raw = response.body();
                JsonNode payload = raw == null || raw.isBlank() ? null : json.readTree(raw);
                int status = response.statusCode();
                if (status >= 200 && status < 300) {
                    return payload;
                }

                JsonNode error = payload == null ? null : payload.path("error");
                String code = error == null ? "" : error.path("code").asText("");
                String message = error == null || error.path("message").asText("").isBlank()
                    ? "Request failed (" + status + ")"
                    : error.path("message").asText();
                throw new TransmitlyException(status, code, message,
                    error == null ? null : error.get("details"), raw);
            } catch (TransmitlyException error) {
                throw error;
            } catch (java.net.http.HttpTimeoutException error) {
                throw new TransmitlyException(0, "timeout", "Request timed out", null, null);
            } catch (IOException error) {
                throw new TransmitlyException(0, "network_error", error.getMessage(), null, null);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new TransmitlyException(0, "interrupted", "Request interrupted", null, null);
            }
        }
    }

    public final class Emails {
        public EmailAccepted send(EmailMessage message) {
            return send(message, null);
        }

        public EmailAccepted send(EmailMessage message, String idempotencyKey) {
            if (message == null) {
                throw new IllegalArgumentException("message is required");
            }
            if (message.from == null || message.from.isBlank()) {
                throw new IllegalArgumentException("from is required");
            }
            if (message.to == null || message.to.isEmpty()) {
                throw new IllegalArgumentException("to is required");
            }
            if (message.subject == null || message.subject.isBlank()) {
                throw new IllegalArgumentException("subject is required");
            }
            if (message.html == null && message.text == null && message.templateId == null) {
                throw new IllegalArgumentException("html, text or templateId is required");
            }
            String key = idempotencyKey == null ? UUID.randomUUID().toString() : idempotencyKey;
            JsonNode payload = executor.call("POST", "/v1/emails", message, Map.of(),
                Map.of("Idempotency-Key", key), true);
            try {
                return json.treeToValue(payload, EmailAccepted.class);
            } catch (IOException error) {
                throw new TransmitlyException(0, "invalid_response", "Could not decode accepted message", null, null);
            }
        }

        public JsonNode get(String id) {
            return executor.call("GET", "/v1/emails/" + encode(id), null, Map.of(), Map.of(), true);
        }
    }

    public final class Messages {
        public JsonNode list(Map<String, String> params) {
            return executor.call("GET", "/v1/messages", null, params, Map.of(), true);
        }

        public JsonNode get(String id) {
            return executor.call("GET", "/v1/messages/" + encode(id), null, Map.of(), Map.of(), true);
        }

        public JsonNode deliveries(String id) {
            return executor.call("GET", "/v1/messages/" + encode(id) + "/deliveries", null, Map.of(), Map.of(), true);
        }
    }

    public final class Domains {
        public JsonNode list() {
            return executor.call("GET", "/v1/domains", null, Map.of(), Map.of(), true);
        }

        public JsonNode create(String domain) {
            return executor.call("POST", "/v1/domains", Map.of("domain", domain), Map.of(), Map.of(), true);
        }

        public JsonNode verify(String id) {
            return executor.call("POST", "/v1/domains/" + encode(id) + "/verify", Map.of(), Map.of(), Map.of(), true);
        }

        public JsonNode remove(String id) {
            return executor.call("DELETE", "/v1/domains/" + encode(id), null, Map.of(), Map.of(), true);
        }
    }

    public final class Suppressions {
        public JsonNode list() {
            return executor.call("GET", "/v1/suppressions", null, Map.of(), Map.of(), true);
        }

        public JsonNode add(Map<String, String> input) {
            return executor.call("POST", "/v1/suppressions", input, Map.of(), Map.of(), true);
        }

        public JsonNode remove(String id) {
            return executor.call("DELETE", "/v1/suppressions/" + encode(id), null, Map.of(), Map.of(), true);
        }
    }

    public final class Webhooks {
        public JsonNode list() {
            return executor.call("GET", "/v1/webhooks", null, Map.of(), Map.of(), true);
        }

        public JsonNode create(Map<String, Object> input) {
            return executor.call("POST", "/v1/webhooks", input, Map.of(), Map.of(), true);
        }

        public JsonNode remove(String id) {
            return executor.call("DELETE", "/v1/webhooks/" + encode(id), null, Map.of(), Map.of(), true);
        }
    }

    public final class Inbound {
        public JsonNode routes() {
            return executor.call("GET", "/v1/inbound/routes", null, Map.of(), Map.of(), true);
        }

        public JsonNode addRoute(Map<String, Object> input) {
            return executor.call("POST", "/v1/inbound/routes", input, Map.of(), Map.of(), true);
        }

        public JsonNode removeRoute(String id) {
            return executor.call("DELETE", "/v1/inbound/routes/" + encode(id), null, Map.of(), Map.of(), true);
        }
    }

    public final class Usage {
        public JsonNode get() {
            return executor.call("GET", "/v1/usage", null, Map.of(), Map.of(), true);
        }
    }

    public final class Events {
        public JsonNode list() {
            return executor.call("GET", "/v1/events", null, Map.of(), Map.of(), true);
        }

        public JsonNode list(int limit) {
            return executor.call("GET", "/v1/events", null,
                Map.of("limit", Integer.toString(limit)), Map.of(), true);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
