package com.example.MigrosBackend.service.user.payment;

import com.stripe.exception.EventDataObjectDeserializationException;
import com.stripe.model.Event;
import com.stripe.model.StripeObject;
import com.stripe.net.ApiResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Pure Stripe payload (de)serialization and sanitization, extracted verbatim
 * from {@link PaymentWebhookService}. It performs no effects and reads no
 * inbox state, so both the webhook service and the attempt state machine can
 * share the single {@link #sanitizeCode(String)} home.
 */
final class StripePayloadCodec {

    private static final Logger LOG = LoggerFactory.getLogger(StripePayloadCodec.class);

    private StripePayloadCodec() {
    }

    /**
     * Parses only the payload already verified and stored for the event, so a
     * worker can never act on content that bypassed signature verification.
     */
    static Event parseStoredPayload(String eventId, String payload) {
        try {
            Event event = StripeObject.deserializeStripeObject(
                    payload, Event.class, ApiResource.getGlobalResponseGetter());
            if (event == null || event.getId() == null || event.getType() == null) {
                throw new IllegalStateException("stored payload is missing its id or type");
            }
            return event;
        } catch (RuntimeException ex) {
            throw new IllegalStateException(
                    "Stored webhook payload for event " + eventId + " cannot be replayed", ex);
        }
    }

    static StripeObject deserialize(Event event) {
        StripeObject object = event.getDataObjectDeserializer().getObject().orElse(null);
        if (object != null) {
            return object;
        }
        try {
            return event.getDataObjectDeserializer().deserializeUnsafe();
        } catch (EventDataObjectDeserializationException ex) {
            LOG.warn("Could not deserialize Stripe event {}", event.getId());
            return null;
        }
    }

    static String sha256Hex(String payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required for webhook payload hashing", ex);
        }
    }

    static String sanitizeCode(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= 64 ? trimmed : trimmed.substring(0, 64);
    }
}
