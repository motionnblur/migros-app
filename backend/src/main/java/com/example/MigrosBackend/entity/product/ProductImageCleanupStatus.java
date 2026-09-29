package com.example.MigrosBackend.entity.product;

/**
 * Lifecycle of one durable "this file is obsolete, remove it" obligation.
 *
 * <p>Only {@link #COMPLETED} means the file is gone. Every other state is work
 * that is still owed and is reclaimable: a worker that died mid-deletion leaves
 * {@code PROCESSING} with an expired lease, a failed attempt leaves
 * {@code PENDING} with a future {@code next_attempt_at}, and a file that is
 * still referenced by some product leaves {@code PENDING} after a deferral.
 *
 * <p>There is deliberately no "given up" state, and that is a stronger
 * constraint here than it is for the support outbox. A support event stops
 * being needed once the receiver has it; a file that could not be deleted is
 * still an unreferenced file occupying the upload directory forever. Parking
 * the row terminally would make the leak permanent and invisible, so the row
 * stays owed, the attempt counter keeps rising, and the log is raised to ERROR
 * for operator attention.
 *
 * <p>These are file-lifecycle states. They are not, and must not become, the
 * support outbox's event statuses: there is no payload, no delivery, no
 * ordering and no receiver that deduplicates. Reusing those contracts would tie
 * an idempotent local filesystem effect to an at-least-once remote delivery
 * protocol that solves a different problem.
 */
public enum ProductImageCleanupStatus {
    PENDING,
    PROCESSING,
    COMPLETED
}
