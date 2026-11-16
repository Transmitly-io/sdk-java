# Transmitly Java SDK

Java 17+ client for the Transmitly API.

```bash
# from the platform repository
mvn -f sdk/java/pom.xml install
```

Then declare `io.transmitly:transmitly:1.0.0` in your project.

```java
import io.transmitly.EmailMessage;
import io.transmitly.TransmitlyClient;

var tx = new TransmitlyClient(System.getenv("TRANSMITLY_API_KEY"));

var accepted = tx.emails.send(new EmailMessage()
    .from("Notify <notify@mail.acme.com>")
    .to(List.of("ada@example.net"))
    .subject("Your workspace is ready")
    .html("<p>Welcome.</p>")
    .replyTo("Support <support@acme.com>")
    .tags(Map.of("flow", "onboarding")));
```

`send` generates an idempotency key. Use `send(message, idempotencyKey)` to
control it yourself; retries with the same key return the original message.

## Spring Boot

```java
@Configuration
class TransmitlyConfig {
    @Bean
    TransmitlyClient transmitlyClient(@Value("${transmitly.api-key}") String apiKey) {
        return new TransmitlyClient(apiKey);
    }
}
```

```java
@RestController
class WelcomeController {
    private final TransmitlyClient tx;

    WelcomeController(TransmitlyClient tx) { this.tx = tx; }

    @PostMapping("/welcome")
    ResponseEntity<Map<String, String>> welcome(@RequestParam String email) {
        var accepted = tx.emails.send(new EmailMessage()
            .from("Notify <notify@mail.acme.com>")
            .to(List.of(email))
            .subject("Welcome to Acme")
            .html("<p>Your workspace is ready.</p>"));
        return ResponseEntity.accepted().body(Map.of("id", accepted.id));
    }
}
```

## Resources

```java
tx.emails.get(messageId);
tx.messages.list(Map.of("status", "bounced", "limit", "20"));
tx.messages.deliveries(messageId);
tx.domains.list();
tx.domains.create("mail.acme.com");
tx.domains.verify(domainId);
tx.suppressions.list();
tx.suppressions.add(Map.of("email", "bounce@example.net", "reason", "manual"));
tx.webhooks.list();
tx.webhooks.create(Map.of("url", "https://api.acme.com/hooks", "events", List.of("email.delivered_to_mx")));
tx.inbound.routes();
tx.usage.get();
tx.events.list(50);
tx.health();
```

Resources other than `emails.send` return Jackson `JsonNode` so new response
fields do not require an SDK release.

## Attachments and scheduling

```java
var attachment = Attachment.fromFile(Path.of("invoice.pdf"), "application/pdf");

tx.emails.send(new EmailMessage()
    .from("Notify <notify@mail.acme.com>")
    .to(List.of("ada@example.net"))
    .subject("November invoice")
    .html("<p>Attached.</p>")
    .scheduledAt("in 2 hours")
    .attachments(List.of(attachment)));
```

## Webhooks

Verify the raw request body before parsing it:

```java
@PostMapping("/webhooks/transmitly")
ResponseEntity<Void> webhook(@RequestBody String raw,
                             @RequestHeader("Transmitly-Signature") String signature) {
    if (!TransmitlyClient.verifyWebhook(raw, signature, webhookSecret)) {
        return ResponseEntity.badRequest().build();
    }
    // Parse and handle only after the signature passes.
    return ResponseEntity.ok().build();
}
```

Non-2xx responses throw `TransmitlyException` with `status()`, `code()`,
`details()` and `payload()`. Domain, webhook and inbound management require an
API key with the `admin` scope.

```bash
mvn test
mvn package
```
