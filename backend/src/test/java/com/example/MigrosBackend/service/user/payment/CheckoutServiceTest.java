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
import com.example.MigrosBackend.exception.user.PaymentAmountException;
import com.example.MigrosBackend.repository.product.ProductEntityRepository;
import com.example.MigrosBackend.repository.user.CheckoutEntityRepository;
import com.example.MigrosBackend.repository.user.CheckoutItemEntityRepository;
import com.example.MigrosBackend.repository.user.OrderEntityRepository;
import com.example.MigrosBackend.repository.user.OrderGroupEntityRepository;
import com.example.MigrosBackend.repository.user.UserEntityRepository;
import com.example.MigrosBackend.service.global.TokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CheckoutServiceTest {

    private static final String TOKEN = "user-token";
    private static final String EMAIL = "buyer@migros.com";

    @Mock
    private TokenService tokenService;
    @Mock
    private UserEntityRepository userEntityRepository;
    @Mock
    private ProductEntityRepository productEntityRepository;
    @Mock
    private CheckoutEntityRepository checkoutEntityRepository;
    @Mock
    private CheckoutItemEntityRepository checkoutItemEntityRepository;
    @Mock
    private OrderGroupEntityRepository orderGroupEntityRepository;
    @Mock
    private OrderEntityRepository orderEntityRepository;

    private final PaymentAmountConverter converter = new PaymentAmountConverter("try");

    private CheckoutService checkoutService;
    private UserEntity user;

    @BeforeEach
    void setUp() {
        checkoutService = new CheckoutService(
                tokenService,
                userEntityRepository,
                productEntityRepository,
                checkoutEntityRepository,
                checkoutItemEntityRepository,
                orderGroupEntityRepository,
                orderEntityRepository,
                converter,
                15);

        user = new UserEntity();
        user.setId(42L);
        user.setUserMail(EMAIL);
    }

    private void stubAuthenticatedUser() {
        when(tokenService.validateAndExtractUser(TOKEN)).thenReturn(EMAIL);
        when(userEntityRepository.findByUserMail(EMAIL)).thenReturn(user);
    }

    private void stubLockedUser() {
        stubAuthenticatedUser();
        when(userEntityRepository.findByIdForUpdate(42L)).thenReturn(Optional.of(user));
    }

    private ProductEntity product(long id, String name, String price, String discount, int stock) {
        ProductEntity product = new ProductEntity();
        product.setId(id);
        product.setProductName(name);
        product.setProductPrice(price == null ? null : new BigDecimal(price));
        product.setProductDiscount(discount == null ? null : new BigDecimal(discount));
        product.setProductCount(stock);
        return product;
    }

    private CheckoutEntity checkout(CheckoutStatus status, String total) {
        CheckoutEntity checkout = new CheckoutEntity();
        checkout.setId(UUID.randomUUID());
        checkout.setUserEntity(user);
        checkout.setStatus(status);
        checkout.setTotalAmount(new BigDecimal(total));
        checkout.setAmountMinor(new BigDecimal(total).movePointRight(2).longValueExact());
        checkout.setCurrency("try");
        checkout.setCreatedAt(LocalDateTime.now().minusMinutes(1));
        checkout.setExpiresAt(LocalDateTime.now().plusMinutes(10));
        checkout.setUpdatedAt(LocalDateTime.now());
        return checkout;
    }

    private CheckoutItemEntity item(CheckoutEntity checkout, long productId, int quantity,
                                    String unitPrice, String lineTotal) {
        CheckoutItemEntity item = new CheckoutItemEntity();
        item.setId(productId);
        item.setCheckout(checkout);
        item.setProductId(productId);
        item.setProductName("P" + productId);
        item.setQuantity(quantity);
        item.setUnitPrice(new BigDecimal(unitPrice));
        item.setLineTotal(new BigDecimal(lineTotal));
        return item;
    }

    @Test
    void prepareCheckout_SnapshotsDiscountedPricesGroupedQuantitiesAndTryAmount() {
        stubLockedUser();
        user.setProductsIdsInCart(new ArrayList<>(List.of(101L, 101L, 102L)));
        ProductEntity apple = product(101L, "Apple", "10.00", "20.00", 5);
        ProductEntity milk = product(102L, "Milk", "5.00", "0.00", 5);
        when(checkoutEntityRepository.findByUserIdAndStatusIn(eq(42L), any())).thenReturn(List.of());
        when(productEntityRepository.findByIdForUpdate(101L)).thenReturn(Optional.of(apple));
        when(productEntityRepository.findByIdForUpdate(102L)).thenReturn(Optional.of(milk));

        CheckoutResponseDto response = checkoutService.prepareCheckout(TOKEN);

        assertEquals("PREPARED", response.status());
        assertEquals(0, new BigDecimal("21.00").compareTo(response.totalAmount()));
        assertEquals(2100L, response.amountMinor());
        assertEquals("try", response.currency());

        ArgumentCaptor<CheckoutEntity> savedCheckout = ArgumentCaptor.forClass(CheckoutEntity.class);
        verify(checkoutEntityRepository).save(savedCheckout.capture());
        List<CheckoutItemEntity> items = savedCheckout.getValue().getItems();
        assertEquals(2, items.size());

        CheckoutItemEntity appleItem = items.stream().filter(i -> i.getProductId().equals(101L)).findFirst().orElseThrow();
        assertEquals(2, appleItem.getQuantity());
        assertEquals(0, new BigDecimal("8.00").compareTo(appleItem.getUnitPrice()));
        assertEquals(0, new BigDecimal("16.00").compareTo(appleItem.getLineTotal()));

        CheckoutItemEntity milkItem = items.stream().filter(i -> i.getProductId().equals(102L)).findFirst().orElseThrow();
        assertEquals(1, milkItem.getQuantity());
        assertEquals(0, new BigDecimal("5.00").compareTo(milkItem.getUnitPrice()));

        assertEquals(3, apple.getProductCount());
        assertEquals(4, milk.getProductCount());
        assertTrue(user.getProductsIdsInCart().isEmpty(), "the reserved cart must be cleared");
    }

    @Test
    void prepareCheckout_RejectsEmptyCart() {
        stubLockedUser();
        user.setProductsIdsInCart(new ArrayList<>());
        when(checkoutEntityRepository.findByUserIdAndStatusIn(eq(42L), any())).thenReturn(List.of());

        assertThrows(GeneralException.class, () -> checkoutService.prepareCheckout(TOKEN));

        verify(checkoutEntityRepository, never()).save(any());
    }

    @Test
    void prepareCheckout_RejectsMissingProduct() {
        stubLockedUser();
        user.setProductsIdsInCart(new ArrayList<>(List.of(101L)));
        when(checkoutEntityRepository.findByUserIdAndStatusIn(eq(42L), any())).thenReturn(List.of());
        when(productEntityRepository.findByIdForUpdate(101L)).thenReturn(Optional.empty());

        assertThrows(GeneralException.class, () -> checkoutService.prepareCheckout(TOKEN));

        verify(checkoutEntityRepository, never()).save(any());
    }

    @Test
    void prepareCheckout_RejectsInsufficientStock() {
        stubLockedUser();
        user.setProductsIdsInCart(new ArrayList<>(List.of(101L, 101L)));
        ProductEntity apple = product(101L, "Apple", "10.00", "0.00", 1);
        when(checkoutEntityRepository.findByUserIdAndStatusIn(eq(42L), any())).thenReturn(List.of());
        when(productEntityRepository.findByIdForUpdate(101L)).thenReturn(Optional.of(apple));

        assertThrows(GeneralException.class, () -> checkoutService.prepareCheckout(TOKEN));

        verify(checkoutEntityRepository, never()).save(any());
        assertEquals(1, apple.getProductCount(), "stock must not be reserved on failure");
    }

    @Test
    void prepareCheckout_RejectsInvalidPrice() {
        stubLockedUser();
        user.setProductsIdsInCart(new ArrayList<>(List.of(101L)));
        ProductEntity apple = product(101L, "Apple", "-1.00", "0.00", 5);
        when(checkoutEntityRepository.findByUserIdAndStatusIn(eq(42L), any())).thenReturn(List.of());
        when(productEntityRepository.findByIdForUpdate(101L)).thenReturn(Optional.of(apple));

        assertThrows(GeneralException.class, () -> checkoutService.prepareCheckout(TOKEN));
        verify(checkoutEntityRepository, never()).save(any());
    }

    @Test
    void prepareCheckout_RejectsOverPrecisePrice() {
        stubLockedUser();
        user.setProductsIdsInCart(new ArrayList<>(List.of(101L)));
        ProductEntity apple = product(101L, "Apple", "10.001", "0.00", 5);
        when(checkoutEntityRepository.findByUserIdAndStatusIn(eq(42L), any())).thenReturn(List.of());
        when(productEntityRepository.findByIdForUpdate(101L)).thenReturn(Optional.of(apple));

        assertThrows(GeneralException.class, () -> checkoutService.prepareCheckout(TOKEN));
        verify(checkoutEntityRepository, never()).save(any());
    }

    @Test
    void prepareCheckout_RejectsInvalidDiscount() {
        stubLockedUser();
        user.setProductsIdsInCart(new ArrayList<>(List.of(101L)));
        ProductEntity apple = product(101L, "Apple", "10.00", "150.00", 5);
        when(checkoutEntityRepository.findByUserIdAndStatusIn(eq(42L), any())).thenReturn(List.of());
        when(productEntityRepository.findByIdForUpdate(101L)).thenReturn(Optional.of(apple));

        assertThrows(GeneralException.class, () -> checkoutService.prepareCheckout(TOKEN));
        verify(checkoutEntityRepository, never()).save(any());
    }

    @Test
    void prepareCheckout_RejectsOversizedTotalBeforePersisting() {
        stubLockedUser();
        user.setProductsIdsInCart(new ArrayList<>(List.of(101L)));
        ProductEntity expensive = product(101L, "Boat", "1000000.00", "0.00", 5);
        when(checkoutEntityRepository.findByUserIdAndStatusIn(eq(42L), any())).thenReturn(List.of());
        when(productEntityRepository.findByIdForUpdate(101L)).thenReturn(Optional.of(expensive));

        assertThrows(PaymentAmountException.class, () -> checkoutService.prepareCheckout(TOKEN));
        verify(checkoutEntityRepository, never()).save(any());
    }

    @Test
    void prepareCheckout_ReturnsExistingUnpaidCheckoutInsteadOfReservingTwice() {
        stubLockedUser();
        user.setProductsIdsInCart(new ArrayList<>(List.of(101L)));
        CheckoutEntity existing = checkout(CheckoutStatus.PREPARED, "10.00");
        when(checkoutEntityRepository.findByUserIdAndStatusIn(eq(42L), any())).thenReturn(List.of(existing));

        CheckoutResponseDto response = checkoutService.prepareCheckout(TOKEN);

        assertEquals(existing.getId().toString(), response.checkoutId());
        verify(checkoutEntityRepository, never()).save(any());
        verify(productEntityRepository, never()).findByIdForUpdate(anyLong());
    }

    @Test
    void prepareCheckout_ConflictsWhenAnotherCheckoutIsProcessing() {
        stubLockedUser();
        user.setProductsIdsInCart(new ArrayList<>(List.of(101L)));
        CheckoutEntity processing = checkout(CheckoutStatus.PAYMENT_PROCESSING, "10.00");
        when(checkoutEntityRepository.findByUserIdAndStatusIn(eq(42L), any())).thenReturn(List.of(processing));

        assertThrows(CheckoutStateException.class, () -> checkoutService.prepareCheckout(TOKEN));
    }

    @Test
    void getCheckout_ByAnotherUserIsNotFound() {
        stubAuthenticatedUser();
        when(checkoutEntityRepository.findOwnedByIdForUpdate(any(), eq(42L))).thenReturn(Optional.empty());

        assertThrows(CheckoutNotFoundException.class,
                () -> checkoutService.getCheckout(TOKEN, UUID.randomUUID()));
    }

    @Test
    void getCheckout_LazilyExpiresAndReleasesReservation() {
        stubAuthenticatedUser();
        CheckoutEntity expired = checkout(CheckoutStatus.PREPARED, "10.00");
        expired.setExpiresAt(LocalDateTime.now().minusSeconds(1));
        when(checkoutEntityRepository.findOwnedByIdForUpdate(expired.getId(), 42L)).thenReturn(Optional.of(expired));
        when(checkoutItemEntityRepository.findByCheckout_IdOrderByProductIdAsc(expired.getId()))
                .thenReturn(List.of(item(expired, 101L, 2, "5.00", "10.00")));

        CheckoutStatusDto status = checkoutService.getCheckout(TOKEN, expired.getId());

        assertEquals("EXPIRED", status.status());
        verify(productEntityRepository).incrementStock(101L, 2);
    }

    @Test
    void cancelCheckout_ReleasesReservationExactlyOnce() {
        stubAuthenticatedUser();
        CheckoutEntity prepared = checkout(CheckoutStatus.PREPARED, "10.00");
        when(checkoutEntityRepository.findOwnedByIdForUpdate(prepared.getId(), 42L)).thenReturn(Optional.of(prepared));
        when(checkoutItemEntityRepository.findByCheckout_IdOrderByProductIdAsc(prepared.getId()))
                .thenReturn(List.of(item(prepared, 101L, 2, "5.00", "10.00")));

        CheckoutStatusDto first = checkoutService.cancelCheckout(TOKEN, prepared.getId());

        assertEquals("CANCELLED", first.status());
        verify(productEntityRepository, times(1)).incrementStock(101L, 2);
    }

    @Test
    void cancelCheckout_IsIdempotentForTerminalCheckout() {
        stubAuthenticatedUser();
        CheckoutEntity cancelled = checkout(CheckoutStatus.CANCELLED, "10.00");
        when(checkoutEntityRepository.findOwnedByIdForUpdate(cancelled.getId(), 42L)).thenReturn(Optional.of(cancelled));

        CheckoutStatusDto status = checkoutService.cancelCheckout(TOKEN, cancelled.getId());

        assertEquals("CANCELLED", status.status());
        verify(productEntityRepository, never()).incrementStock(anyLong(), anyInt());
    }

    @Test
    void cancelCheckout_RefusesPaidCheckout() {
        stubAuthenticatedUser();
        CheckoutEntity paid = checkout(CheckoutStatus.PAID, "10.00");
        when(checkoutEntityRepository.findOwnedByIdForUpdate(paid.getId(), 42L)).thenReturn(Optional.of(paid));

        assertThrows(CheckoutStateException.class, () -> checkoutService.cancelCheckout(TOKEN, paid.getId()));
        verify(productEntityRepository, never()).incrementStock(anyLong(), anyInt());
    }

    @Test
    void completePayment_BuildsOrdersFromSnapshotWithoutTouchingProductPrices() {
        CheckoutEntity paid = checkout(CheckoutStatus.PAYMENT_PROCESSING, "16.00");
        CheckoutItemEntity apple = item(paid, 101L, 2, "8.00", "16.00");
        when(checkoutEntityRepository.findByIdForUpdate(paid.getId())).thenReturn(Optional.of(paid));
        when(checkoutItemEntityRepository.findByCheckout_IdOrderByProductIdAsc(paid.getId()))
                .thenReturn(List.of(apple));
        when(orderGroupEntityRepository.save(any(OrderGroupEntity.class))).thenAnswer(invocation -> {
            OrderGroupEntity group = invocation.getArgument(0);
            group.setId(500L);
            return group;
        });

        CheckoutStatusDto status = checkoutService.completePayment(paid.getId(), "ch_1");

        assertEquals("CONSUMED", status.status());
        assertEquals(500L, status.orderGroupId());

        ArgumentCaptor<OrderEntity> orderCaptor = ArgumentCaptor.forClass(OrderEntity.class);
        verify(orderEntityRepository).save(orderCaptor.capture());
        OrderEntity order = orderCaptor.getValue();
        assertEquals(101L, order.getItemId());
        assertEquals(2, order.getCount());
        assertEquals(0, new BigDecimal("8.00").compareTo(order.getPrice()));
        assertEquals(0, new BigDecimal("16.00").compareTo(order.getTotalPrice()));

        verify(productEntityRepository, never()).saveAll(any());
        verify(productEntityRepository, never()).incrementStock(anyLong(), anyInt());
    }

    @Test
    void completePayment_FailsClosedWhenSnapshotTotalDoesNotMatch() {
        CheckoutEntity processing = checkout(CheckoutStatus.PAYMENT_PROCESSING, "99.00");
        when(checkoutEntityRepository.findByIdForUpdate(processing.getId())).thenReturn(Optional.of(processing));
        when(checkoutItemEntityRepository.findByCheckout_IdOrderByProductIdAsc(processing.getId()))
                .thenReturn(List.of(item(processing, 101L, 1, "5.00", "5.00")));

        assertThrows(GeneralException.class, () -> checkoutService.completePayment(processing.getId(), "ch_1"));
        verify(orderEntityRepository, never()).save(any());
    }

    @Test
    void completePayment_IsIdempotentForConsumedCheckout() {
        CheckoutEntity consumed = checkout(CheckoutStatus.CONSUMED, "16.00");
        consumed.setOrderGroupEntityId(500L);
        when(checkoutEntityRepository.findByIdForUpdate(consumed.getId())).thenReturn(Optional.of(consumed));

        CheckoutStatusDto status = checkoutService.completePayment(consumed.getId(), "ch_1");

        assertEquals("CONSUMED", status.status());
        verify(orderGroupEntityRepository, never()).save(any());
        verify(checkoutItemEntityRepository, never()).findByCheckout_IdOrderByProductIdAsc(any());
    }

    @Test
    void createOrderFromCheckout_RequiresSuccessfulPaymentState() {
        CheckoutEntity prepared = checkout(CheckoutStatus.PREPARED, "10.00");
        when(checkoutEntityRepository.findByIdForUpdate(prepared.getId())).thenReturn(Optional.of(prepared));

        assertThrows(CheckoutStateException.class,
                () -> checkoutService.createOrderFromCheckout(prepared.getId()));
    }

    @Test
    void beginPayment_RejectsExpiredCheckoutAndReleasesStock() {
        stubAuthenticatedUser();
        CheckoutEntity expired = checkout(CheckoutStatus.PREPARED, "10.00");
        expired.setExpiresAt(LocalDateTime.now().minusSeconds(1));
        when(checkoutEntityRepository.findOwnedByIdForUpdate(expired.getId(), 42L)).thenReturn(Optional.of(expired));
        when(checkoutItemEntityRepository.findByCheckout_IdOrderByProductIdAsc(expired.getId()))
                .thenReturn(List.of(item(expired, 101L, 1, "10.00", "10.00")));

        assertThrows(CheckoutStateException.class,
                () -> checkoutService.beginPayment(TOKEN, expired.getId()));
        verify(productEntityRepository).incrementStock(101L, 1);
    }

    @Test
    void beginPayment_TransitionsPreparedToProcessing() {
        stubAuthenticatedUser();
        CheckoutEntity prepared = checkout(CheckoutStatus.PREPARED, "21.00");
        when(checkoutEntityRepository.findOwnedByIdForUpdate(prepared.getId(), 42L)).thenReturn(Optional.of(prepared));

        CheckoutPaymentStart start = checkoutService.beginPayment(TOKEN, prepared.getId());

        assertEquals(prepared.getId(), start.checkoutId());
        assertEquals(2100L, start.amountMinor());
        assertEquals("try", start.currency());
        assertEquals(CheckoutStatus.PAYMENT_PROCESSING, prepared.getStatus());
    }

    @Test
    void missingUserIsRejected() {
        when(tokenService.validateAndExtractUser(TOKEN)).thenReturn(EMAIL);
        when(userEntityRepository.findByUserMail(EMAIL)).thenReturn(null);

        assertThrows(UserNotFoundException.class, () -> checkoutService.prepareCheckout(TOKEN));
    }

    @Test
    void completePayment_RejectsCancelledCheckout() {
        CheckoutEntity cancelled = checkout(CheckoutStatus.CANCELLED, "10.00");
        when(checkoutEntityRepository.findByIdForUpdate(cancelled.getId())).thenReturn(Optional.of(cancelled));

        assertThrows(CheckoutStateException.class,
                () -> checkoutService.completePayment(cancelled.getId(), "ch_1"));
        verify(orderEntityRepository, never()).save(any());
    }

    @Test
    void prepareCheckout_LocksProductsInAscendingIdOrder() {
        stubLockedUser();
        user.setProductsIdsInCart(new ArrayList<>(List.of(205L, 101L, 150L)));
        when(checkoutEntityRepository.findByUserIdAndStatusIn(eq(42L), any())).thenReturn(List.of());
        when(productEntityRepository.findByIdForUpdate(101L)).thenReturn(Optional.of(product(101L, "A", "1.00", "0", 5)));
        when(productEntityRepository.findByIdForUpdate(150L)).thenReturn(Optional.of(product(150L, "B", "1.00", "0", 5)));
        when(productEntityRepository.findByIdForUpdate(205L)).thenReturn(Optional.of(product(205L, "C", "1.00", "0", 5)));

        checkoutService.prepareCheckout(TOKEN);

        var inOrder = org.mockito.Mockito.inOrder(productEntityRepository);
        inOrder.verify(productEntityRepository).findByIdForUpdate(101L);
        inOrder.verify(productEntityRepository).findByIdForUpdate(150L);
        inOrder.verify(productEntityRepository).findByIdForUpdate(205L);
    }
}
