package com.example.MigrosBackend.service.user.payment;

import com.example.MigrosBackend.dto.payment.CheckoutPaymentStart;
import com.example.MigrosBackend.dto.payment.CheckoutResponseDto;
import com.example.MigrosBackend.dto.payment.CheckoutStatusDto;
import com.example.MigrosBackend.entity.checkout.CheckoutEntity;
import com.example.MigrosBackend.entity.checkout.CheckoutItemEntity;
import com.example.MigrosBackend.entity.checkout.CheckoutStatus;
import com.example.MigrosBackend.entity.product.ProductEntity;
import com.example.MigrosBackend.entity.user.OrderEntity;
import com.example.MigrosBackend.entity.user.OrderGroupEntity;
import com.example.MigrosBackend.entity.user.UserEntity;
import com.example.MigrosBackend.exception.admin.UserNotFoundException;
import com.example.MigrosBackend.exception.shared.GeneralException;
import com.example.MigrosBackend.exception.user.CheckoutNotFoundException;
import com.example.MigrosBackend.exception.user.CheckoutStateException;
import com.example.MigrosBackend.repository.product.ProductEntityRepository;
import com.example.MigrosBackend.repository.user.CheckoutEntityRepository;
import com.example.MigrosBackend.repository.user.CheckoutItemEntityRepository;
import com.example.MigrosBackend.repository.user.OrderEntityRepository;
import com.example.MigrosBackend.repository.user.OrderGroupEntityRepository;
import com.example.MigrosBackend.repository.user.UserEntityRepository;
import com.example.MigrosBackend.service.global.TokenService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Owns the immutable checkout snapshot and the stock reservation that backs the
 * payment path. Amounts are always recalculated from server-side product rows,
 * never from client input.
 */
@Service
public class CheckoutService {

    private static final int MONEY_SCALE = 2;
    private static final String ORDER_PENDING_STATUS = "Pending";

    private final TokenService tokenService;
    private final UserEntityRepository userEntityRepository;
    private final ProductEntityRepository productEntityRepository;
    private final CheckoutEntityRepository checkoutEntityRepository;
    private final CheckoutItemEntityRepository checkoutItemEntityRepository;
    private final OrderGroupEntityRepository orderGroupEntityRepository;
    private final OrderEntityRepository orderEntityRepository;
    private final PaymentAmountConverter paymentAmountConverter;
    private final int checkoutTtlMinutes;

    public CheckoutService(TokenService tokenService,
                           UserEntityRepository userEntityRepository,
                           ProductEntityRepository productEntityRepository,
                           CheckoutEntityRepository checkoutEntityRepository,
                           CheckoutItemEntityRepository checkoutItemEntityRepository,
                           OrderGroupEntityRepository orderGroupEntityRepository,
                           OrderEntityRepository orderEntityRepository,
                           PaymentAmountConverter paymentAmountConverter,
                           @Value("${payment.checkout.ttl-minutes:15}") int checkoutTtlMinutes) {
        this.tokenService = tokenService;
        this.userEntityRepository = userEntityRepository;
        this.productEntityRepository = productEntityRepository;
        this.checkoutEntityRepository = checkoutEntityRepository;
        this.checkoutItemEntityRepository = checkoutItemEntityRepository;
        this.orderGroupEntityRepository = orderGroupEntityRepository;
        this.orderEntityRepository = orderEntityRepository;
        this.paymentAmountConverter = paymentAmountConverter;
        this.checkoutTtlMinutes = checkoutTtlMinutes;
    }

    @Transactional
    public CheckoutResponseDto prepareCheckout(String userToken) {
        UserEntity user = lockAuthenticatedUser(userToken);
        LocalDateTime now = LocalDateTime.now();

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

        Map<Long, Integer> requestedQuantities = groupCartQuantities(user);
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

            BigDecimal unitPrice = effectiveUnitPrice(product);
            BigDecimal lineTotal = unitPrice.multiply(BigDecimal.valueOf(quantity)).setScale(MONEY_SCALE, RoundingMode.HALF_UP);

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
        total = total.setScale(MONEY_SCALE, RoundingMode.HALF_UP);

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
        expireIfNeeded(checkout, LocalDateTime.now());
        return toStatus(checkout);
    }

    @Transactional
    public CheckoutStatusDto cancelCheckout(String userToken, UUID checkoutId) {
        UserEntity user = authenticatedUser(userToken);
        CheckoutEntity checkout = checkoutEntityRepository.findOwnedByIdForUpdate(checkoutId, user.getId())
                .orElseThrow(CheckoutNotFoundException::new);

        LocalDateTime now = LocalDateTime.now();
        expireIfNeeded(checkout, now);

        if (checkout.getStatus() == CheckoutStatus.PAID || checkout.getStatus() == CheckoutStatus.CONSUMED) {
            throw new CheckoutStateException("A paid checkout cannot be cancelled");
        }
        if (checkout.getStatus().isLive()) {
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

        LocalDateTime now = LocalDateTime.now();
        expireIfNeeded(checkout, now);

        if (checkout.getStatus() == CheckoutStatus.PAYMENT_PROCESSING) {
            throw new CheckoutStateException("Checkout is already being processed");
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
        checkout.setUpdatedAt(LocalDateTime.now());
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

    @Transactional
    public void failPayment(UUID checkoutId) {
        CheckoutEntity checkout = checkoutEntityRepository.findByIdForUpdate(checkoutId).orElse(null);
        if (checkout == null || checkout.getStatus() != CheckoutStatus.PAYMENT_PROCESSING) {
            return;
        }
        releaseReservation(checkout);
        checkout.setStatus(CheckoutStatus.CANCELLED);
        checkout.setUpdatedAt(LocalDateTime.now());
    }

    @Transactional(readOnly = true)
    public List<UUID> findExpiredCheckoutIds() {
        return checkoutEntityRepository.findExpiredIds(CheckoutStatus.liveStatuses(), LocalDateTime.now());
    }

    @Transactional
    public boolean expireCheckout(UUID checkoutId) {
        CheckoutEntity checkout = checkoutEntityRepository.findByIdForUpdate(checkoutId).orElse(null);
        if (checkout == null) {
            return false;
        }
        return expireIfNeeded(checkout, LocalDateTime.now());
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
        snapshotTotal = snapshotTotal.setScale(MONEY_SCALE, RoundingMode.HALF_UP);

        if (snapshotTotal.compareTo(checkout.getTotalAmount()) != 0) {
            throw new GeneralException("Checkout total does not match its items");
        }

        long computedMinor;
        try {
            computedMinor = snapshotTotal.movePointRight(MONEY_SCALE).longValueExact();
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
        group.setCreatedAt(LocalDateTime.now());
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
        checkout.setUpdatedAt(LocalDateTime.now());
        checkoutEntityRepository.saveAndFlush(checkout);
    }

    private boolean expireIfNeeded(CheckoutEntity checkout, LocalDateTime now) {
        if (checkout.getStatus().isLive() && !checkout.getExpiresAt().isAfter(now)) {
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

    private Map<Long, Integer> groupCartQuantities(UserEntity user) {
        List<Long> cart = user.getProductsIdsInCart();
        Map<Long, Integer> counts = new TreeMap<>();
        if (cart == null) {
            return counts;
        }
        for (Long productId : cart) {
            if (productId != null) {
                counts.merge(productId, 1, Integer::sum);
            }
        }
        return counts;
    }

    private BigDecimal effectiveUnitPrice(ProductEntity product) {
        BigDecimal price = product.getProductPrice();
        if (price == null || price.signum() < 0 || price.stripTrailingZeros().scale() > MONEY_SCALE) {
            throw new GeneralException("Product has an invalid price: " + product.getProductName());
        }
        BigDecimal discount = product.getProductDiscount();
        if (discount == null) {
            discount = BigDecimal.ZERO;
        }
        if (discount.signum() < 0 || discount.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new GeneralException("Product has an invalid discount: " + product.getProductName());
        }

        BigDecimal normalized = price.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        if (discount.signum() == 0) {
            return normalized;
        }
        BigDecimal factor = BigDecimal.ONE.subtract(
                discount.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP));
        return normalized.multiply(factor).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private UserEntity lockAuthenticatedUser(String userToken) {
        UserEntity user = authenticatedUser(userToken);
        return userEntityRepository.findByIdForUpdate(user.getId())
                .orElseThrow(() -> new UserNotFoundException(user.getUserMail()));
    }

    private UserEntity authenticatedUser(String userToken) {
        String userMail = tokenService.validateAndExtractUser(userToken);
        UserEntity user = userEntityRepository.findByUserMail(userMail);
        if (user == null) {
            throw new UserNotFoundException(userMail);
        }
        return user;
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
