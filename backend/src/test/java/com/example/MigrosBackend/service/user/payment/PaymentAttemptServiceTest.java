package com.example.MigrosBackend.service.user.payment;

import com.example.MigrosBackend.dto.payment.CheckoutPaymentStart;
import com.example.MigrosBackend.dto.payment.PaymentClaim;
import com.example.MigrosBackend.dto.payment.PaymentClaimDecision;
import com.example.MigrosBackend.entity.checkout.CheckoutEntity;
import com.example.MigrosBackend.entity.checkout.CheckoutStatus;
import com.example.MigrosBackend.entity.payment.PaymentAttemptEntity;
import com.example.MigrosBackend.entity.payment.PaymentAttemptStatus;
import com.example.MigrosBackend.entity.user.UserEntity;
import com.example.MigrosBackend.exception.user.CheckoutNotFoundException;
import com.example.MigrosBackend.exception.user.PaymentStateException;
import com.example.MigrosBackend.repository.user.CheckoutEntityRepository;
import com.example.MigrosBackend.repository.user.PaymentAttemptEntityRepository;
import com.example.MigrosBackend.repository.user.UserEntityRepository;
import com.example.MigrosBackend.service.global.TokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentAttemptServiceTest {

    private static final String TOKEN = "user-token";
    private static final String EMAIL = "buyer@migros.com";
    private static final long USER_ID = 42L;

    @Mock
    private TokenService tokenService;
    @Mock
    private UserEntityRepository userEntityRepository;
    @Mock
    private CheckoutEntityRepository checkoutEntityRepository;
    @Mock
    private PaymentAttemptEntityRepository paymentAttemptEntityRepository;
    @Mock
    private CheckoutService checkoutService;

    private PaymentAttemptService paymentAttemptService;
    private UserEntity user;
    private UUID checkoutId;
    private String idempotencyKey;

    @BeforeEach
    void setUp() {
        paymentAttemptService = new PaymentAttemptService(
                tokenService, userEntityRepository, checkoutEntityRepository,
                paymentAttemptEntityRepository, checkoutService, 120);
        user = new UserEntity();
        user.setId(USER_ID);
        user.setUserMail(EMAIL);
        checkoutId = UUID.randomUUID();
        idempotencyKey = ChargeIdempotencyKeys.forCheckout(checkoutId);
    }

    private void stubAuth() {
        when(tokenService.validateAndExtractUser(TOKEN)).thenReturn(EMAIL);
        when(userEntityRepository.findByUserMail(EMAIL)).thenReturn(user);
    }

    private CheckoutEntity checkout(CheckoutStatus status, long amountMinor) {
        CheckoutEntity checkout = new CheckoutEntity();
        checkout.setId(checkoutId);
        checkout.setUserEntity(user);
        checkout.setStatus(status);
        checkout.setAmountMinor(amountMinor);
        checkout.setCurrency("try");
        checkout.setExpiresAt(LocalDateTime.now().plusMinutes(10));
        return checkout;
    }

    private PaymentAttemptEntity attempt(PaymentAttemptStatus status, long amountMinor) {
        PaymentAttemptEntity attempt = new PaymentAttemptEntity();
        attempt.setId(UUID.randomUUID());
        attempt.setCheckout(checkout(CheckoutStatus.PAYMENT_PROCESSING, amountMinor));
        attempt.setIdempotencyKey(idempotencyKey);
        attempt.setAmountMinor(amountMinor);
        attempt.setCurrency("try");
        attempt.setStatus(status);
        attempt.setCreatedAt(LocalDateTime.now());
        attempt.setUpdatedAt(LocalDateTime.now());
        if (status.hasDurableCharge()) {
            attempt.setStripeChargeId("ch_123");
        }
        return attempt;
    }

    @Test
    void claim_NewAttemptTransitionsToProcessingWithLeaseAndProceeds() {
        stubAuth();
        CheckoutEntity checkout = checkout(CheckoutStatus.PREPARED, 1000L);
        when(checkoutEntityRepository.findOwnedByIdForUpdate(checkoutId, USER_ID))
                .thenReturn(Optional.of(checkout));
        when(paymentAttemptEntityRepository.findByCheckoutIdForUpdate(checkoutId))
                .thenReturn(Optional.empty());
        when(checkoutService.beginPayment(TOKEN, checkoutId))
                .thenReturn(new CheckoutPaymentStart(checkoutId, 1000L, "try"));
        when(paymentAttemptEntityRepository.saveAndFlush(any(PaymentAttemptEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        PaymentClaim claim = paymentAttemptService.claim(TOKEN, checkoutId, idempotencyKey);

        assertEquals(PaymentClaimDecision.PROCEED, claim.decision());
        assertEquals(1000L, claim.amountMinor());
        assertEquals("try", claim.currency());
        assertEquals(idempotencyKey, claim.idempotencyKey());
        assertFalse(claim.leaseOwner() == null || claim.leaseOwner().isBlank());
        verify(paymentAttemptEntityRepository).saveAndFlush(any(PaymentAttemptEntity.class));
    }

    @Test
    void claim_AnotherUsersCheckoutIsNotFound() {
        stubAuth();
        when(checkoutEntityRepository.findOwnedByIdForUpdate(checkoutId, USER_ID))
                .thenReturn(Optional.empty());

        assertThrows(CheckoutNotFoundException.class,
                () -> paymentAttemptService.claim(TOKEN, checkoutId, idempotencyKey));
        verify(checkoutService, never()).beginPayment(any(), any());
    }

    @Test
    void claim_ValidLeaseReturnsPendingWithoutRecharging() {
        stubAuth();
        CheckoutEntity checkout = checkout(CheckoutStatus.PAYMENT_PROCESSING, 1000L);
        when(checkoutEntityRepository.findOwnedByIdForUpdate(checkoutId, USER_ID))
                .thenReturn(Optional.of(checkout));
        PaymentAttemptEntity processing = attempt(PaymentAttemptStatus.PROCESSING, 1000L);
        processing.setLeaseOwner("owner");
        processing.setLeaseExpiresAt(LocalDateTime.now().plusSeconds(60));
        when(paymentAttemptEntityRepository.findByCheckoutIdForUpdate(checkoutId))
                .thenReturn(Optional.of(processing));

        PaymentClaim claim = paymentAttemptService.claim(TOKEN, checkoutId, idempotencyKey);

        assertEquals(PaymentClaimDecision.PENDING, claim.decision());
        verify(checkoutService, never()).beginPayment(any(), any());
    }

    @Test
    void claim_ExpiredLeaseIsReclaimedWithTheSameIdempotencyKey() {
        stubAuth();
        CheckoutEntity checkout = checkout(CheckoutStatus.PAYMENT_PROCESSING, 1000L);
        when(checkoutEntityRepository.findOwnedByIdForUpdate(checkoutId, USER_ID))
                .thenReturn(Optional.of(checkout));
        PaymentAttemptEntity processing = attempt(PaymentAttemptStatus.PROCESSING, 1000L);
        processing.setLeaseOwner("stale-owner");
        processing.setLeaseExpiresAt(LocalDateTime.now().minusSeconds(5));
        when(paymentAttemptEntityRepository.findByCheckoutIdForUpdate(checkoutId))
                .thenReturn(Optional.of(processing));

        PaymentClaim claim = paymentAttemptService.claim(TOKEN, checkoutId, idempotencyKey);

        assertEquals(PaymentClaimDecision.PROCEED, claim.decision());
        assertFalse("stale-owner".equals(claim.leaseOwner()));
        assertEquals(idempotencyKey, claim.idempotencyKey());
    }

    @Test
    void claim_ChargeSucceededReturnsFinalizeDecision() {
        stubAuth();
        CheckoutEntity checkout = checkout(CheckoutStatus.PAYMENT_PROCESSING, 1000L);
        when(checkoutEntityRepository.findOwnedByIdForUpdate(checkoutId, USER_ID))
                .thenReturn(Optional.of(checkout));
        when(paymentAttemptEntityRepository.findByCheckoutIdForUpdate(checkoutId))
                .thenReturn(Optional.of(attempt(PaymentAttemptStatus.CHARGE_SUCCEEDED, 1000L)));

        PaymentClaim claim = paymentAttemptService.claim(TOKEN, checkoutId, idempotencyKey);

        assertEquals(PaymentClaimDecision.FINALIZE, claim.decision());
        assertEquals("ch_123", claim.chargeId());
    }

    @Test
    void claim_FinalizedAttemptReturnsStoredResult() {
        stubAuth();
        CheckoutEntity checkout = checkout(CheckoutStatus.CONSUMED, 1000L);
        when(checkoutEntityRepository.findOwnedByIdForUpdate(checkoutId, USER_ID))
                .thenReturn(Optional.of(checkout));
        when(paymentAttemptEntityRepository.findByCheckoutIdForUpdate(checkoutId))
                .thenReturn(Optional.of(attempt(PaymentAttemptStatus.ORDER_FINALIZED, 1000L)));

        PaymentClaim claim = paymentAttemptService.claim(TOKEN, checkoutId, idempotencyKey);

        assertEquals(PaymentClaimDecision.FINALIZED, claim.decision());
    }

    @Test
    void claim_DeclinedAttemptReturnsTerminalWithoutRecharging() {
        stubAuth();
        CheckoutEntity checkout = checkout(CheckoutStatus.CANCELLED, 1000L);
        when(checkoutEntityRepository.findOwnedByIdForUpdate(checkoutId, USER_ID))
                .thenReturn(Optional.of(checkout));
        when(paymentAttemptEntityRepository.findByCheckoutIdForUpdate(checkoutId))
                .thenReturn(Optional.of(attempt(PaymentAttemptStatus.FAILED_FINAL, 1000L)));

        PaymentClaim claim = paymentAttemptService.claim(TOKEN, checkoutId, idempotencyKey);

        assertEquals(PaymentClaimDecision.TERMINAL, claim.decision());
    }

    @Test
    void claim_MismatchedIdempotencyKeyIsRejected() {
        stubAuth();
        CheckoutEntity checkout = checkout(CheckoutStatus.PAYMENT_PROCESSING, 1000L);
        when(checkoutEntityRepository.findOwnedByIdForUpdate(checkoutId, USER_ID))
                .thenReturn(Optional.of(checkout));
        when(paymentAttemptEntityRepository.findByCheckoutIdForUpdate(checkoutId))
                .thenReturn(Optional.of(attempt(PaymentAttemptStatus.PROCESSING, 1000L)));

        assertThrows(PaymentStateException.class,
                () -> paymentAttemptService.claim(TOKEN, checkoutId, "checkout:other:charge-v1"));
    }

    @Test
    void recordChargeSuccess_StoresChargeFromProcessing() {
        PaymentAttemptEntity processing = attempt(PaymentAttemptStatus.PROCESSING, 1000L);
        processing.setStripeChargeId(null);
        when(paymentAttemptEntityRepository.findByIdForUpdate(processing.getId()))
                .thenReturn(Optional.of(processing));

        paymentAttemptService.recordChargeSuccess(processing.getId(), "owner", "ch_777");

        assertEquals(PaymentAttemptStatus.CHARGE_SUCCEEDED, processing.getStatus());
        assertEquals("ch_777", processing.getStripeChargeId());
    }

    @Test
    void recordChargeSuccess_IsIdempotentAfterSuccess() {
        PaymentAttemptEntity succeeded = attempt(PaymentAttemptStatus.CHARGE_SUCCEEDED, 1000L);
        when(paymentAttemptEntityRepository.findByIdForUpdate(succeeded.getId()))
                .thenReturn(Optional.of(succeeded));

        paymentAttemptService.recordChargeSuccess(succeeded.getId(), null, "ch_123");

        assertEquals(PaymentAttemptStatus.CHARGE_SUCCEEDED, succeeded.getStatus());
    }

    @Test
    void recordChargeSuccess_ConflictingChargeFailsClosed() {
        PaymentAttemptEntity succeeded = attempt(PaymentAttemptStatus.CHARGE_SUCCEEDED, 1000L);
        when(paymentAttemptEntityRepository.findByIdForUpdate(succeeded.getId()))
                .thenReturn(Optional.of(succeeded));

        assertThrows(PaymentStateException.class,
                () -> paymentAttemptService.recordChargeSuccess(succeeded.getId(), null, "ch_OTHER"));
        assertEquals(PaymentAttemptStatus.MANUAL_REVIEW, succeeded.getStatus());
    }

    @Test
    void recordDecline_CannotOverwriteADurableSuccess() {
        PaymentAttemptEntity succeeded = attempt(PaymentAttemptStatus.CHARGE_SUCCEEDED, 1000L);
        when(paymentAttemptEntityRepository.findByIdForUpdate(succeeded.getId()))
                .thenReturn(Optional.of(succeeded));

        assertThrows(PaymentStateException.class,
                () -> paymentAttemptService.recordDecline(succeeded.getId(), "card_declined"));
        assertEquals(PaymentAttemptStatus.CHARGE_SUCCEEDED, succeeded.getStatus());
    }

    @Test
    void markOrderFinalized_RequiresChargeSucceeded() {
        PaymentAttemptEntity processing = attempt(PaymentAttemptStatus.PROCESSING, 1000L);
        when(paymentAttemptEntityRepository.findByIdForUpdate(processing.getId()))
                .thenReturn(Optional.of(processing));

        assertThrows(PaymentStateException.class,
                () -> paymentAttemptService.markOrderFinalized(processing.getId(), null));
    }

    @Test
    void markRefundPending_RequiresACapturedCharge() {
        PaymentAttemptEntity processing = attempt(PaymentAttemptStatus.PROCESSING, 1000L);
        processing.setStripeChargeId(null);
        when(paymentAttemptEntityRepository.findByIdForUpdate(processing.getId()))
                .thenReturn(Optional.of(processing));

        assertThrows(PaymentStateException.class,
                () -> paymentAttemptService.markRefundPending(processing.getId(), "reason"));
    }

    @Test
    void stateMachine_RejectsBackwardAndIllegalTransitions() {
        assertTrue(PaymentAttemptStatus.CREATED.canTransitionTo(PaymentAttemptStatus.PROCESSING));
        assertTrue(PaymentAttemptStatus.PROCESSING.canTransitionTo(PaymentAttemptStatus.CHARGE_SUCCEEDED));
        assertTrue(PaymentAttemptStatus.ORDER_FINALIZED.canTransitionTo(PaymentAttemptStatus.REFUND_PENDING));

        assertFalse(PaymentAttemptStatus.ORDER_FINALIZED.canTransitionTo(PaymentAttemptStatus.CHARGE_SUCCEEDED));
        assertFalse(PaymentAttemptStatus.REFUNDED.canTransitionTo(PaymentAttemptStatus.PROCESSING));
        assertFalse(PaymentAttemptStatus.FAILED_FINAL.canTransitionTo(PaymentAttemptStatus.CHARGE_SUCCEEDED));
        assertTrue(PaymentAttemptStatus.CHARGE_SUCCEEDED.canTransitionTo(PaymentAttemptStatus.CHARGE_SUCCEEDED));
    }
}
