package com.example.MigrosBackend.entity.user;

/**
 * Actor that authored a support message.
 *
 * <p>The {@code sender} database column is a string and the enum's
 * {@link #name()} is the stored value, so switching comparisons to this type
 * leaves the wire and persistence formats unchanged.
 */
public enum SupportMessageSender {
    USER,
    MANAGEMENT
}
