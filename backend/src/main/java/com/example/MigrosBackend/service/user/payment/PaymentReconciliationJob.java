package com.example.MigrosBackend.service.user.payment;

import com.example.MigrosBackend.entity.payment.PaymentAttemptStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Bounded, periodic reconciliation for attempts stuck in a recoverable state.
 * Logs use only internal attempt/checkout/charge identifiers; they never
 * contain card tokens, session tokens, API keys, or webhook secrets.
 */
@Component
public class PaymentReconciliationJob {

    private static final Logger LOG = LoggerFactory.getLogger(PaymentReconciliationJob.class);

    private static final List<PaymentAttemptStatus> RECOVERABLE = List.of(
            PaymentAttemptStatus.PROCESSING,
            PaymentAttemptStatus.CHARGE_SUCCEEDED,
            PaymentAttemptStatus.REFUND_PENDING);

    private final PaymentAttemptService paymentAttemptService;
    private final PaymentRecoveryService paymentRecoveryService;
    private final Clock clock;
    private final int staleAfterSeconds;

    public PaymentReconciliationJob(PaymentAttemptService paymentAttemptService,
                                    PaymentRecoveryService paymentRecoveryService,
                                    Clock clock,
                                    @Value("${payment.recovery.stale-after-seconds:300}") int staleAfterSeconds) {
        this.paymentAttemptService = paymentAttemptService;
        this.paymentRecoveryService = paymentRecoveryService;
        this.clock = clock;
        this.staleAfterSeconds = staleAfterSeconds;
    }

    @Scheduled(
            fixedDelayString = "${payment.recovery.scan-ms:60000}",
            initialDelayString = "${payment.recovery.initial-delay-ms:60000}")
    public void reconcileStuckAttempts() {
        LocalDateTime threshold = LocalDateTime.now(clock).minusSeconds(staleAfterSeconds);
        List<UUID> staleIds = paymentAttemptService.findStaleAttemptIds(RECOVERABLE, threshold);
        for (UUID attemptId : staleIds) {
            try {
                paymentRecoveryService.recover(attemptId);
            } catch (RuntimeException ex) {
                LOG.error("Recovery failed for attempt {}: {}", attemptId, ex.getClass().getSimpleName());
            }
        }
    }
}
