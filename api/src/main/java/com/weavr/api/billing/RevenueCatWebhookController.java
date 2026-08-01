package com.weavr.api.billing;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * RevenueCat's webhook — the only way {@code subscriptions} is ever written.
 *
 * <p>Permitted without a JWT in {@code SecurityConfig} (it is RevenueCat
 * calling, not a user) and authenticated instead by the shared secret they send
 * in the {@code Authorization} header.
 *
 * <h2>What the status codes mean here</h2>
 * <p>RevenueCat retries any non-2xx. That makes the choice of code load-bearing
 * in a way it usually is not:
 * <ul>
 *   <li><b>401</b> — bad or missing secret. Worth retrying: it is what a
 *       misconfigured dashboard looks like, and we would rather see repeats than
 *       lose the events.</li>
 *   <li><b>200</b> — <em>everything else</em>, including events we deliberately
 *       do nothing with. An event for an anonymous purchaser, a duplicate
 *       delivery, or one that arrived out of order will never succeed on a
 *       retry, so a 4xx would only buy an infinite redelivery loop. They are
 *       recorded in {@code billing_events} with {@code applied = false} instead,
 *       which is both the audit trail and the answer to "why is this user not
 *       Pro?".</li>
 *   <li><b>500</b> — only a genuine fault, where a retry is exactly right.</li>
 * </ul>
 */
@RestController
class RevenueCatWebhookController {

    private static final Logger log = LoggerFactory.getLogger(RevenueCatWebhookController.class);

    private final BillingService billing;
    private final BillingProperties props;
    private final ObjectMapper objectMapper;

    RevenueCatWebhookController(BillingService billing, BillingProperties props,
                                ObjectMapper objectMapper) {
        this.billing = billing;
        this.props = props;
        this.objectMapper = objectMapper;
    }

    /**
     * Takes the body as a raw {@link String} rather than a bound record on
     * purpose: the same text is both parsed and stored verbatim in
     * {@code billing_events.payload}, so a field we misread today is
     * diagnosable tomorrow without asking RevenueCat to redeliver.
     */
    @PostMapping("/v1/webhooks/revenuecat")
    ResponseEntity<Map<String, String>> receive(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody String body) {

        if (!authorized(authorization)) {
            // Deliberately vague. The one thing this endpoint must not do is
            // help someone work out what the secret looks like.
            log.warn("Rejected RevenueCat webhook with bad or missing Authorization header");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "unauthorized"));
        }

        JsonNode event;
        try {
            event = objectMapper.readTree(body).path("event");
        } catch (RuntimeException e) {
            log.warn("Unparseable RevenueCat webhook body: {}", e.toString());
            // Not retryable — the same bytes will not parse next time either.
            return ResponseEntity.ok(Map.of("status", "ignored", "reason", "unparseable"));
        }

        if (event.isMissingNode() || event.path("id").asString(null) == null) {
            return ResponseEntity.ok(Map.of("status", "ignored", "reason", "no_event"));
        }

        BillingService.Outcome outcome = billing.apply(event, body);
        log.info("RevenueCat event {} type={} → {}",
                event.path("id").asString(""), event.path("type").asString(""), outcome.reason());
        return ResponseEntity.ok(Map.of("status", outcome.applied() ? "applied" : "ignored",
                "reason", outcome.reason()));
    }

    /**
     * Constant-time comparison, and <strong>fails closed</strong>: an unset
     * secret disables the endpoint rather than opening it. Getting that
     * backwards on the one route that decides who has paid would be the worst
     * default in the codebase, and "it works in dev" is exactly how it happens.
     */
    private boolean authorized(String header) {
        String expected = props.webhookSecret();
        if (expected == null || expected.isBlank()) {
            log.error("weavr.billing.webhook-secret is not set — refusing every RevenueCat webhook. "
                    + "Set WEAVR_REVENUECAT_WEBHOOK_SECRET to the value configured in the RevenueCat dashboard.");
            return false;
        }
        if (header == null) {
            return false;
        }
        return MessageDigest.isEqual(
                header.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8));
    }
}
