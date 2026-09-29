package com.example.MigrosBackend.service.user.supply;

import com.example.MigrosBackend.dto.order.OrderDto;
import com.example.MigrosBackend.dto.order.OrderPageDto;
import com.example.MigrosBackend.dto.user.UserProfileTableDto;
import com.example.MigrosBackend.entity.user.OrderEntity;
import com.example.MigrosBackend.entity.user.OrderGroupEntity;
import com.example.MigrosBackend.entity.user.OrderStatus;
import com.example.MigrosBackend.entity.user.UserEntity;
import com.example.MigrosBackend.exception.admin.OrderNotFoundException;
import com.example.MigrosBackend.exception.admin.UserNotFoundException;
import com.example.MigrosBackend.repository.product.ProductEntityRepository;
import com.example.MigrosBackend.repository.user.OrderEntityRepository;
import com.example.MigrosBackend.repository.user.OrderGroupEntityRepository;
import com.example.MigrosBackend.repository.user.UserEntityRepository;
import com.example.MigrosBackend.service.global.TokenService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Service
public class UserOrderService {
    private final TokenService tokenService;
    private final UserEntityRepository userEntityRepository;
    private final OrderEntityRepository orderEntityRepository;
    private final OrderGroupEntityRepository orderGroupEntityRepository;
    private final OrderStockRestocker orderStockRestocker;

    public UserOrderService(TokenService tokenService,
                            UserEntityRepository userEntityRepository,
                            OrderEntityRepository orderEntityRepository,
                            OrderGroupEntityRepository orderGroupEntityRepository,
                            ProductEntityRepository productEntityRepository) {
        this.tokenService = tokenService;
        this.userEntityRepository = userEntityRepository;
        this.orderEntityRepository = orderEntityRepository;
        this.orderGroupEntityRepository = orderGroupEntityRepository;
        this.orderStockRestocker = new OrderStockRestocker(productEntityRepository);
    }

    public void clearUserCart(String userToken) {
        UserEntity user = getValidatedUser(userToken);
        user.setProductsIdsInCart(new ArrayList<>());
        userEntityRepository.save(user);
    }

    public OrderPageDto getAllOrders(int page, int productRange) {
        List<OrderDto> orderDtos = new ArrayList<>();

        List<OrderGroupEntity> groups = orderGroupEntityRepository.findAll();
        for (OrderGroupEntity group : groups) {
            List<OrderEntity> items = orderEntityRepository.findByOrderGroup_Id(group.getId());
            BigDecimal totalPrice = BigDecimal.ZERO;
            for (OrderEntity item : items) {
                if (item.getTotalPrice() != null) {
                    totalPrice = totalPrice.add(item.getTotalPrice());
                }
            }

            OrderDto dto = new OrderDto();
            dto.setOrderId(group.getId());
            dto.setOrderGroupId(group.getId());
            dto.setTotalPrice(totalPrice);
            dto.setStatus(group.getStatus());
            orderDtos.add(dto);
        }

        List<OrderEntity> legacyOrders = orderEntityRepository.findByOrderGroupIsNull();
        for (OrderEntity legacy : legacyOrders) {
            OrderDto dto = new OrderDto();
            dto.setOrderId(legacy.getId());
            dto.setOrderGroupId(legacy.getId());
            dto.setTotalPrice(legacy.getTotalPrice() != null ? legacy.getTotalPrice() : BigDecimal.ZERO);
            dto.setStatus(legacy.getStatus());
            orderDtos.add(dto);
        }

        orderDtos.sort((a, b) -> Long.compare(b.getOrderId(), a.getOrderId()));
        int total = orderDtos.size();
        int fromIndex = Math.max(0, page * productRange);
        OrderPageDto pageDto = new OrderPageDto();
        pageDto.setTotal(total);
        if (fromIndex >= total) {
            pageDto.setItems(new ArrayList<>());
            return pageDto;
        }
        int toIndex = Math.min(total, fromIndex + productRange);
        List<OrderDto> pageItems = new ArrayList<>(orderDtos.subList(fromIndex, toIndex));
        pageDto.setItems(pageItems);
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


    private UserEntity getValidatedUser(String userToken) {
        String userName = tokenService.validateAndExtractUser(userToken);
        UserEntity user = userEntityRepository.findByUserMail(userName);
        if (user == null) {
            throw new UserNotFoundException("User not found for active session");
        }
        return user;
    }
}

