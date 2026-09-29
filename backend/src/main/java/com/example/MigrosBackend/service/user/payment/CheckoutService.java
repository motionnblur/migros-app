package com.example.MigrosBackend.service.user.payment;

import com.example.MigrosBackend.dto.payment.CheckoutPaymentStart;
import com.example.MigrosBackend.dto.payment.CheckoutResponseDto;
import com.example.MigrosBackend.dto.payment.CheckoutStatusDto;
import com.example.MigrosBackend.entity.checkout.CheckoutEntity;
import com.example.MigrosBackend.entity.checkout.CheckoutItemEntity;
import com.example.MigrosBackend.entity.checkout.CheckoutStatus;
import com.example.MigrosBackend.entity.payment.PaymentAttemptEntity;
import com.example.MigrosBackend.entity.payment.PaymentAttemptStatus;
import com.example.MigrosBackend.entity.product.ProductEntity;
import com.example.MigrosBackend.entity.user.OrderEntity;
import com.example.MigrosBackend.entity.user.OrderGroupEntity;
import com.example.MigrosBackend.entity.user.OrderStatus;
import com.example.MigrosBackend.entity.user.UserEntity;
import com.example.MigrosBackend.exception.admin.UserNotFoundException;
import com.example.MigrosBackend.exception.shared.GeneralException;
import com.example.MigrosBackend.exception.user.CheckoutConflictException;
import com.example.MigrosBackend.exception.user.CheckoutNotFoundException;
import com.example.MigrosBackend.exception.user.CheckoutStateException;
import com.example.MigrosBackend.repository.product.ProductEntityRepository;
import com.example.MigrosBackend.repository.user.CheckoutEntityRepository;
import com.example.MigrosBackend.repository.user.CheckoutItemEntityRepository;
import com.example.MigrosBackend.repository.user.OrderEntityRepository;
import com.example.MigrosBackend.repository.user.OrderGroupEntityRepository;
import com.example.MigrosBackend.repository.user.PaymentAttemptEntityRepository;
import com.example.MigrosBackend.repository.user.UserEntityRepository;
import com.example.MigrosBackend.service.global.TokenService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Owns the immutable checkout snapshot and the stock reservation that backs the
 * payment path. Amounts are always recalculated from server-side product rows,
 * never from client input.
 */
@Service
public class CheckoutService {

    private static final String ORDER_PENDING_STATUS = OrderStatus.PENDING;

    private final TokenService tokenService;
    private final UserEntityRepository userEntityRepository;
    private final ProductEntityRepository productEntityRepository;
    private final CheckoutEntityRepository checkoutEntityRepository;
    private final CheckoutItemEntityRepository checkoutItemEntityRepository;
    private final OrderGroupEntityRepository orderGroupEntityRepository;
    private final OrderEntityRepository orderEntityRepository;
    private final PaymentAttemptEntityRepository paymentAttemptEntityRepository;
    private final PaymentAmountConverter paymentAmountConverter;
    private final Clock clock;
    private final int checkoutTtlMinutes;

    public CheckoutService(TokenService tokenService,
                           UserEntityRepository userEntityRepository,
                           ProductEntityRepository productEntityRepository,
                           CheckoutEntityRepository checkoutEntityRepository,
                           CheckoutItemEntityRepository checkoutItemEntityRepository,
                           OrderGroupEntityRepository orderGroupEntityRepository,
                           OrderEntityRepository orderEntityRepository,
                           PaymentAttemptEntityRepository paymentAttemptEntityRepository,
                           PaymentAmountConverter paymentAmountConverter,
                           Clock clock,
                           @Value("${payment.checkout.ttl-minutes:15}") int checkoutTtlMinutes) {
        this.tokenService = tokenService;
        this.userEntityRepository = userEntityRepository;
        this.productEntityRepository = productEntityRepository;
        this.checkoutEntityRepository = checkoutEntityRepository;
        this.checkoutItemEntityRepository = checkoutItemEntityRepository;
        this.orderGroupEntityRepository = orderGroupEntityRepository;
        this.orderEntityRepository = orderEntityRepository;
        this.paymentAttemptEntityRepository = paymentAttemptEntityRepository;
        this.paymentAmountConverter = paymentAmountConverter;
        this.clock = clock;
        this.checkoutTtlMinutes = checkoutTtlMinutes;
    }

    @Transactional
    public CheckoutResponseDto prepareCheckout(String userToken) {
        UserEntity user = lockAuthenticatedUser(userToken);
        LocalDateTime now = LocalDateTime.now(clock);

        List<CheckoutEntity> liveCheckouts =
                checkoutEntityRepository.findByUserIdAndStatusIn(user.getId(), CheckoutStatus.liveStatuses());
        CheckoutEntity reusable = null;
        for (CheckoutEntity live : liveCheckouts) {
            if (live.getExpiresAt().isAfter(now)) {
                if (live.getStatus() == CheckoutStatus.PAYMENT_PROCESSING) {
                    throw new CheckoutStateException("A checkout is already being processed");
                }
                reusable = live;
                break;
            }
            expireAndFlush(live, now);
        }
        if (reusable != null) {
            return toResponse(reusable);
        }

        Map<Long, Integer> requestedQuantities = CheckoutCalculations.groupCartQuantities(
                user.getProductsIdsInCart());
        if (requestedQuantities.isEmpty()) {
            throw new GeneralException("Cart is empty");
        }

        // Lock product rows one by one in ascending id order so overlapping carts
        // always acquire locks in the same sequence and cannot deadlock.
        Map<Long, ProductEntity> lockedProducts = new LinkedHashMap<>();
        for (Long productId : requestedQuantities.keySet()) {
            ProductEntity product = productEntityRepository.findByIdForUpdate(productId)
                    .orElseThrow(() -> new GeneralException("One or more products are no longer available."));
            lockedProducts.put(productId, product);
        }

        CheckoutEntity checkout = new CheckoutEntity();
        checkout.setId(UUID.randomUUID());
        checkout.setUserEntity(user);
        checkout.setStatus(CheckoutStatus.PREPARED);
        checkout.setCreatedAt(now);
        checkout.setExpiresAt(now.plusMinutes(checkoutTtlMinutes));
        checkout.setUpdatedAt(now);

        BigDecimal total = BigDecimal.ZERO;
        List<CheckoutItemEntity> items = new ArrayList<>(lockedProducts.size());
        for (Map.Entry<Long, ProductEntity> entry : lockedProducts.entrySet()) {
            ProductEntity product = entry.getValue();
            int quantity = requestedQuantities.get(entry.getKey());

            if (product.getProductCount() < quantity) {
                throw new GeneralException("Insufficient stock for product: " + product.getProductName());
            }

            BigDecimal unitPrice = CheckoutCalculations.effectiveUnitPrice(product);
            BigDecimal lineTotal = unitPrice.multiply(BigDecimal.valueOf(quantity))
                    .setScale(CheckoutCalculations.MONEY_SCALE, RoundingMode.HALF_UP);

            CheckoutItemEntity item = new CheckoutItemEntity();
            item.setCheckout(checkout);
            item.setProductId(product.getId());
            item.setProductName(product.getProductName());
            item.setQuantity(quantity);
            item.setUnitPrice(unitPrice);
            item.setLineTotal(lineTotal);
            items.add(item);

            total = total.add(lineTotal);
        }
        total = total.setScale(CheckoutCalculations.MONEY_SCALE, RoundingMode.HALF_UP);

        StripeAmount stripeAmount = paymentAmountConverter.toStripeAmount(total);
        checkout.setTotalAmount(total);
        checkout.setAmountMinor(stripeAmount.amountMinor());
        checkout.setCurrency(stripeAmount.currency());
        checkout.setItems(items);
        checkoutEntityRepository.save(checkout);

        for (Map.Entry<Long, ProductEntity> entry : lockedProducts.entrySet()) {
            ProductEntity product = entry.getValue();
            product.setProductCount(product.getProductCount() - requestedQuantities.get(entry.getKey()));
        }
        productEntityRepository.saveAll(lockedProducts.values());

        user.setProductsIdsInCart(new ArrayList<>());
        userEntityRepository.save(user);

        return toResponse(checkout);
    }

    @Transactional
    public CheckoutStatusDto getCheckout(String userToken, UUID checkoutId) {
        UserEntity user = authenticatedUser(userToken);
        CheckoutEntity checkout = checkoutEntityRepository.findOwnedByIdForUpdate(checkoutId, user.getId())
                .orElseThrow(CheckoutNotFoundException::new);
        expireIfNeeded(checkout, LocalDateTime.now(clock));
        return toStatus(checkout);
    }

    @Transactional
    public CheckoutStatusDto cancelCheckout(String userToken, UUID checkoutId) {
        UserEntity user = authenticatedUser(userToken);
        CheckoutEntity checkout = checkoutEntityRepository.findOwnedByIdForUpdate(checkoutId, user.getId())
                .orElseThrow(CheckoutNotFoundException::new);

        LocalDateTime now = LocalDateTime.now(clock);
        expireIfNeeded(checkout, now);

        if (checkout.getStatus() == CheckoutStatus.PAID || checkout.getStatus() == CheckoutStatus.CONSUMED) {
            throw CheckoutConflictException.notCancellable(
                    checkout.getId(), "A paid checkout cannot be cancelled");
        }
        if (checkout.getStatus() == CheckoutStatus.PAYMENT_PROCESSING) {
            // Money may be moving: the reservation must be kept until the
            // provider outcome is durably resolved via the payment status path.
            throw CheckoutConflictException.reconciliationPending(
                    checkout.getId(),
                    "Payment is still processing and cannot be cancelled; check payment status");
        }
        if (checkout.getStatus().isUserCancellable()) {
            releaseReservation(checkout);
            checkout.setStatus(CheckoutStatus.CANCELLED);
            checkout.setUpdatedAt(now);
        }
        return toStatus(checkout);
    }

    @Transactional
    public CheckoutPaymentStart beginPayment(String userToken, UUID checkoutId) {
        UserEntity user = authenticatedUser(userToken);
        CheckoutEntity checkout = checkoutEntityRepository.findOwnedByIdForUpdate(checkoutId, user.getId())
                .orElseThrow(CheckoutNotFoundException::new);

        LocalDateTime now = LocalDateTime.now(clock);
        expireIfNeeded(checkout, now);

        if (checkout.getStatus() == CheckoutStatus.PAYMENT_PROCESSING) {
            // Idempotent resume: a durable payment attempt owns the retry/lease
            // semantics, so a repeated claim of the same checkout is allowed and
            // returns the same stored amount and currency.
            return new CheckoutPaymentStart(checkout.getId(), checkout.getAmountMinor(), checkout.getCurrency());
        }
        if (checkout.getStatus() != CheckoutStatus.PREPARED) {
            throw new CheckoutStateException("Checkout is not payable");
        }

        checkout.setStatus(CheckoutStatus.PAYMENT_PROCESSING);
        checkout.setUpdatedAt(now);
        return new CheckoutPaymentStart(checkout.getId(), checkout.getAmountMinor(), checkout.getCurrency());
    }

    @Transactional
    public CheckoutStatusDto completePayment(UUID checkoutId, String chargeId) {
        CheckoutEntity checkout = checkoutEntityRepository.findByIdForUpdate(checkoutId)
                .orElseThrow(CheckoutNotFoundException::new);

        if (checkout.getStatus() == CheckoutStatus.CONSUMED) {
            return toStatus(checkout);
        }
        if (checkout.getStatus() == CheckoutStatus.PAID) {
            finalizeOrder(checkout);
            return toStatus(checkout);
        }
        if (checkout.getStatus() != CheckoutStatus.PAYMENT_PROCESSING) {
            throw new CheckoutStateException("Checkout cannot be completed from state " + checkout.getStatus());
        }

        checkout.setStatus(CheckoutStatus.PAID);
        checkout.setStripeChargeId(chargeId);
        checkout.setUpdatedAt(LocalDateTime.now(clock));
        finalizeOrder(checkout);
        return toStatus(checkout);
    }

    @Transactional
    public CheckoutStatusDto createOrderFromCheckout(UUID checkoutId) {
        CheckoutEntity checkout = checkoutEntityRepository.findByIdForUpdate(checkoutId)
                .orElseThrow(CheckoutNotFoundException::new);

        if (checkout.getStatus() != CheckoutStatus.PAID && checkout.getStatus() != CheckoutStatus.CONSUMED) {
            throw new CheckoutStateException("Checkout has no successful payment");
        }
        finalizeOrder(checkout);
        return toStatus(checkout);
    }

    /**
     * Internal payment-workflow transition only. Releases the reservation for a
     * processing checkout when, and only when, the linked payment attempt proves
     * that no charge occurred (terminal {@code FAILED_FINAL} with no stored
     * charge id). Ambiguous outcomes, stale leases, and missing attempts fail
     * closed and keep the reservation for reconciliation.
     */
    @Transactional
    public void releaseIfDefinitelyUncharged(UUID checkoutId) {
        CheckoutEntity checkout = checkoutEntityRepository.findByIdForUpdate(checkoutId).orElse(null);
        if (checkout == null || checkout.getStatus() != CheckoutStatus.PAYMENT_PROCESSING) {
            return;
        }
        // Non-locking read on purpose: the decline caller already holds the
        // attempt row lock in the same transaction, and this guard must fail
        // closed without introducing a new checkout->attempt lock ordering.
        PaymentAttemptEntity attempt = paymentAttemptEntityRepository.findByCheckoutId(checkoutId)
                .orElse(null);
        if (attempt == null || attempt.getStatus() != PaymentAttemptStatus.FAILED_FINAL) {
            return;
        }
        if (attempt.getStripeChargeId() != null || attempt.getStatus().hasDurableCharge()) {
            return;
        }
        releaseReservation(checkout);
        checkout.setStatus(CheckoutStatus.CANCELLED);
        checkout.setUpdatedAt(LocalDateTime.now(clock));
    }

    @Transactional(readOnly = true)
    public List<UUID> findExpiredCheckoutIds() {
        return checkoutEntityRepository.findExpiredIds(CheckoutStatus.expirableStatuses(), LocalDateTime.now(clock));
    }

    @Transactional
    public boolean expireCheckout(UUID checkoutId) {
        CheckoutEntity checkout = checkoutEntityRepository.findByIdForUpdate(checkoutId).orElse(null);
        if (checkout == null) {
            return false;
        }
        return expireIfNeeded(checkout, LocalDateTime.now(clock));
    }

    private void finalizeOrder(CheckoutEntity checkout) {
        if (checkout.getOrderGroupEntityId() != null) {
            return;
        }

        List<CheckoutItemEntity> items =
                checkoutItemEntityRepository.findByCheckout_IdOrderByProductIdAsc(checkout.getId());
        if (items.isEmpty()) {
            throw new GeneralException("Checkout has no items");
        }

        BigDecimal snapshotTotal = BigDecimal.ZERO;
        for (CheckoutItemEntity item : items) {
            snapshotTotal = snapshotTotal.add(item.getLineTotal());
        }
        snapshotTotal = snapshotTotal.setScale(CheckoutCalculations.MONEY_SCALE, RoundingMode.HALF_UP);

        if (snapshotTotal.compareTo(checkout.getTotalAmount()) != 0) {
            throw new GeneralException("Checkout total does not match its items");
        }

        long computedMinor;
        try {
            computedMinor = snapshotTotal.movePointRight(CheckoutCalculations.MONEY_SCALE).longValueExact();
        } catch (ArithmeticException ex) {
            throw new GeneralException("Checkout total is out of range");
        }
        if (computedMinor != checkout.getAmountMinor()) {
            throw new GeneralException("Checkout amount does not match its items");
        }

        UserEntity user = checkout.getUserEntity();
        OrderGroupEntity group = new OrderGroupEntity();
        group.setUserEntity(user);
        group.setUserId(user.getId());
        group.setCreatedAt(LocalDateTime.now(clock));
        group.setStatus(ORDER_PENDING_STATUS);
        group = orderGroupEntityRepository.save(group);

        for (CheckoutItemEntity item : items) {
            OrderEntity order = new OrderEntity();
            order.setUserEntity(user);
            order.setOrderGroup(group);
            order.setUserId(user.getId());
            order.setItemId(item.getProductId());
            order.setPrice(item.getUnitPrice());
            order.setCount(item.getQuantity());
            order.setTotalPrice(item.getLineTotal());
            order.setStatus(group.getStatus());
            orderEntityRepository.save(order);
        }

        checkout.setOrderGroupEntityId(group.getId());
        checkout.setStatus(CheckoutStatus.CONSUMED);
        checkout.setUpdatedAt(LocalDateTime.now(clock));
        checkoutEntityRepository.saveAndFlush(checkout);
    }

    private boolean expireIfNeeded(CheckoutEntity checkout, LocalDateTime now) {
        // Only a PREPARED checkout may be expired and have its reservation
        // released. A PAYMENT_PROCESSING checkout may have a charge in flight,
        // so it is resolved by the payment-attempt recovery path instead.
        if (checkout.getStatus() == CheckoutStatus.PREPARED && !checkout.getExpiresAt().isAfter(now)) {
            releaseReservation(checkout);
            checkout.setStatus(CheckoutStatus.EXPIRED);
            checkout.setUpdatedAt(now);
            return true;
        }
        return false;
    }

    private void expireAndFlush(CheckoutEntity checkout, LocalDateTime now) {
        if (expireIfNeeded(checkout, now)) {
            checkoutEntityRepository.saveAndFlush(checkout);
        }
    }

    private void releaseReservation(CheckoutEntity checkout) {
        List<CheckoutItemEntity> items =
                checkoutItemEntityRepository.findByCheckout_IdOrderByProductIdAsc(checkout.getId());
        for (CheckoutItemEntity item : items) {
            productEntityRepository.incrementStock(item.getProductId(), item.getQuantity());
        }
    }

    private UserEntity lockAuthenticatedUser(String userToken) {
        UserEntity user = authenticatedUser(userToken);
        return userEntityRepository.findByIdForUpdate(user.getId())
                .orElseThrow(() -> new UserNotFoundException(user.getUserMail()));
    }

    private UserEntity authenticatedUser(String userToken) {
        return PaymentUserResolver.resolve(tokenService, userEntityRepository, userToken);
    }

    private CheckoutResponseDto toResponse(CheckoutEntity checkout) {
        return new CheckoutResponseDto(
                checkout.getId().toString(),
                checkout.getStatus().name(),
                checkout.getTotalAmount(),
                checkout.getAmountMinor(),
                checkout.getCurrency(),
                checkout.getCreatedAt(),
                checkout.getExpiresAt());
    }

    private CheckoutStatusDto toStatus(CheckoutEntity checkout) {
        return new CheckoutStatusDto(
                checkout.getId().toString(),
                checkout.getStatus().name(),
                checkout.getTotalAmount(),
                checkout.getAmountMinor(),
                checkout.getCurrency(),
                checkout.getCreatedAt(),
                checkout.getExpiresAt(),
                checkout.getOrderGroupEntityId(),
                checkout.getStripeChargeId());
    }
}
