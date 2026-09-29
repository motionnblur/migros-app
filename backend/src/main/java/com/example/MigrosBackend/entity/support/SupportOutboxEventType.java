package com.example.MigrosBackend.entity.support;

/**
 * The support-service event kinds this backend publishes.
 *
 * <p>The wire path for each kind is fixed by this enum rather than stored
 * redundantly in the row, so a stored record can never disagree with the route
 * the dispatcher posts to.
 */
public enum SupportOutboxEventType {

    CUSTOMER_MESSAGE_CREATED("/internal/events/customer-message-created"),
    SUPPORT_MESSAGE_EDITED("/internal/events/support-message-edited"),
    SUPPORT_MESSAGE_DELETED("/internal/events/support-message-deleted");

    private final String path;

    SupportOutboxEventType(String path) {
        this.path = path;
    }

    public String path() {
        return path;
    }
}
