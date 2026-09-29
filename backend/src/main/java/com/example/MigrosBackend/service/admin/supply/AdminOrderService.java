package com.example.MigrosBackend.service.admin.supply;

import com.example.MigrosBackend.dto.order.OrderDto;
import com.example.MigrosBackend.dto.order.OrderPageDto;
import com.example.MigrosBackend.dto.user.UserProfileTableDto;
import com.example.MigrosBackend.entity.user.OrderEntity;
import com.example.MigrosBackend.entity.user.OrderGroupEntity;
import com.example.MigrosBackend.entity.user.OrderStatus;
import com.example.MigrosBackend.entity.user.UserEntity;
import com.example.MigrosBackend.exception.admin.OrderNotFoundException;
import com.example.MigrosBackend.exception.admin.UserNotFoundException;
import com.example.MigrosBackend.repository.user.AdminOrderRow;
import com.example.MigrosBackend.repository.user.OrderEntityRepository;
import com.example.MigrosBackend.repository.user.OrderGroupEntityRepository;
import com.example.MigrosBackend.repository.user.UserEntityRepository;
import com.example.MigrosBackend.service.user.supply.OrderStockRestocker;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Admin-side order operations: the merged order listing, an order's owner, and
 * the status/delete write paths.
 *
 * <p>Moved out of {@code service/user/supply} because only
 * {@code AdminPanelController} serves these calls. The user-side cart clear it
 * used to carry lives with {@code UserCartService}.
 */
@Service
public class AdminOrderService {
    private final UserEntityRepository userEntityRepository;
    private final OrderEntityRepository orderEntityRepository;
    private final OrderGroupEntityRepository orderGroupEntityRepository;
    private final OrderStockRestocker orderStockRestocker;

    public AdminOrderService(UserEntityRepository userEntityRepository,
                             OrderEntityRepository orderEntityRepository,
                             OrderGroupEntityRepository orderGroupEntityRepository,
                             OrderStockRestocker orderStockRestocker) {
        this.userEntityRepository = userEntityRepository;
        this.orderEntityRepository = orderEntityRepository;
        this.orderGroupEntityRepository = orderGroupEntityRepository;
        this.orderStockRestocker = orderStockRestocker;
    }

    /**
     * Returns one page of the merged listing of grouped and legacy orders.
     *
     * <p>Ordering, the price sum, and the total are computed by the database so
     * an admin page view only ever loads its own window. The previous version
     * read every order group, ran one query per group for its lines, and sliced
     * the merged result in Java.
     */
    public OrderPageDto getAllOrders(int page, int productRange) {
        OrderPageDto pageDto = new OrderPageDto();

        if (productRange <= 0) {
            pageDto.setTotal(orderGroupEntityRepository.countAdminOrders());
            pageDto.setItems(new ArrayList<>());
            return pageDto;
        }

        Page<AdminOrderRow> rows = orderGroupEntityRepository.findAdminOrderPage(
                PageRequest.of(Math.max(0, page), productRange));

        List<OrderDto> items = new ArrayList<>(rows.getNumberOfElements());
        for (AdminOrderRow row : rows.getContent()) {
            OrderDto dto = new OrderDto();
            dto.setOrderId(row.getOrderId());
            dto.setOrderGroupId(row.getOrderGroupId());
            dto.setTotalPrice(row.getTotalPrice());
            dto.setStatus(row.getStatus());
            items.add(dto);
        }

        pageDto.setTotal(rows.getTotalElements());
        pageDto.setItems(items);
        return pageDto;
    }

    /**
     * Resolves the owner of an order named by a single numeric id.
     *
     * <p>The same id may name an order group or a legacy order line, so the
     * group is looked up first. When it is absent the line fallback is
     * restricted to lines that have no group at all: group ids and line ids
     * come from independent sequences, so an unrestricted fallback could
     * resolve to another customer's order line and disclose their profile.
     */
    public UserProfileTableDto getUserProfileData(Long orderId) {
        UserEntity userEntity;

        OrderGroupEntity orderGroup = orderGroupEntityRepository.findById(orderId).orElse(null);
        if (orderGroup != null) {
            userEntity = userEntityRepository.findById(orderGroup.getUserId())
                    .orElseThrow(() -> new UserNotFoundException(orderGroup.getUserId().toString()));
        } else {
            OrderEntity legacyOrder = orderEntityRepository.findByIdAndOrderGroupIsNull(orderId)
                    .orElseThrow(() -> new OrderNotFoundException(orderId.toString()));
            userEntity = userEntityRepository.findById(legacyOrder.getUserId())
                    .orElseThrow(() -> new UserNotFoundException(legacyOrder.getUserId().toString()));
        }

        UserProfileTableDto order = new UserProfileTableDto();
        order.setUserFirstName(userEntity.getUserName());
        order.setUserLastName(userEntity.getUserLastName());
        order.setUserAddress(userEntity.getUserAddress());
        order.setUserAddress2(userEntity.getUserAddress2());
        order.setUserTown(userEntity.getUserTown());
        order.setUserCountry(userEntity.getUserCountry());
        order.setUserPostalCode(userEntity.getUserPostalCode());

        return order;
    }

    /**
     * Applies an administrator status change to a whole order in one
     * transaction.
     *
     * <p>Previously the group status and its line statuses were written by
     * separate repository calls with no surrounding transaction, so a failure
     * between them left an order whose header and lines disagreed about their
     * status. The group row is locked for the whole change so a concurrent user
     * cancellation cannot interleave between the status check and the line
     * updates.
     *
     * <p>Status values are passed through unchanged, so existing API and status
     * string compatibility is preserved.
     *
     * <p>The legacy fallback is restricted to lines with no order group. Group
     * ids and line ids are independent sequences, so falling back to "the order
     * line with this id" without that restriction would apply an administrator's
     * status change - and possibly a restock - to a line belonging to a
     * different order.
     */
    @Transactional
    public void updateOrderStatus(Long orderId, String status) {
        OrderGroupEntity orderGroup = orderGroupEntityRepository.findByIdForUpdate(orderId).orElse(null);
        if (orderGroup != null) {
            orderGroup.setStatus(status);
            orderGroupEntityRepository.save(orderGroup);

            List<OrderEntity> items = orderEntityRepository.findByOrderGroup_Id(orderGroup.getId());
            for (OrderEntity item : items) {
                item.setStatus(status);
            }
            orderEntityRepository.saveAll(items);
            return;
        }

        OrderEntity legacyOrder = orderEntityRepository.findByIdAndOrderGroupIsNullForUpdate(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId.toString()));
        legacyOrder.setStatus(status);
        orderEntityRepository.save(legacyOrder);
    }

    /**
     * Deletes an order, returning its reserved units to live stock when it is
     * still {@code Pending}.
     *
     * <p>The order row is locked before the status is read, so a concurrent
     * cancellation of the same order cannot both observe {@code Pending} and
     * both restock. The status read, the restock, and the delete share one
     * transaction, so a failure anywhere in the sequence leaves the order and
     * its stock untouched.
     *
     * <p>The legacy fallback only considers lines that have no order group:
     * group ids and line ids are independent sequences, so an unrestricted
     * fallback could delete - and restock - a line from an unrelated order
     * group.
     */
    @Transactional
    public void deleteOrder(Long orderId) {
        OrderGroupEntity orderGroup = orderGroupEntityRepository.findByIdForUpdate(orderId).orElse(null);
        if (orderGroup != null) {
            List<OrderEntity> items = orderEntityRepository.findByOrderGroup_Id(orderGroup.getId());
            if (OrderStatus.isPending(orderGroup.getStatus())) {
                orderStockRestocker.restore(items);
            }
            orderEntityRepository.deleteAll(items);
            orderGroupEntityRepository.delete(orderGroup);
            return;
        }

        OrderEntity legacyOrder = orderEntityRepository.findByIdAndOrderGroupIsNullForUpdate(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId.toString()));
        if (OrderStatus.isPending(legacyOrder.getStatus())) {
            orderStockRestocker.restore(legacyOrder.getItemId(),
                    legacyOrder.getCount() == null ? 0 : legacyOrder.getCount());
        }
        orderEntityRepository.delete(legacyOrder);
    }
}
