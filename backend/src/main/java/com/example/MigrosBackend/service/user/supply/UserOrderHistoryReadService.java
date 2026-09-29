package com.example.MigrosBackend.service.user.supply;

import com.example.MigrosBackend.dto.user.order.UserOrderDetailDto;
import com.example.MigrosBackend.dto.user.order.UserOrderGroupDto;
import com.example.MigrosBackend.entity.product.ProductEntity;
import com.example.MigrosBackend.entity.user.OrderEntity;
import com.example.MigrosBackend.entity.user.OrderGroupEntity;
import com.example.MigrosBackend.entity.user.UserEntity;
import com.example.MigrosBackend.exception.admin.UserNotFoundException;
import com.example.MigrosBackend.exception.shared.GeneralException;
import com.example.MigrosBackend.repository.product.ProductEntityRepository;
import com.example.MigrosBackend.repository.user.OrderEntityRepository;
import com.example.MigrosBackend.repository.user.OrderGroupEntityRepository;
import com.example.MigrosBackend.repository.user.UserEntityRepository;
import com.example.MigrosBackend.service.global.TokenService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

final class UserOrderHistoryReadService {
    private final UserEntityRepository userEntityRepository;
    private final TokenService tokenService;
    private final OrderEntityRepository orderEntityRepository;
    private final OrderGroupEntityRepository orderGroupEntityRepository;
    private final ProductEntityRepository productEntityRepository;

    UserOrderHistoryReadService(
            UserEntityRepository userEntityRepository,
            TokenService tokenService,
            OrderEntityRepository orderEntityRepository,
            OrderGroupEntityRepository orderGroupEntityRepository,
            ProductEntityRepository productEntityRepository
    ) {
        this.userEntityRepository = userEntityRepository;
        this.tokenService = tokenService;
        this.orderEntityRepository = orderEntityRepository;
        this.orderGroupEntityRepository = orderGroupEntityRepository;
        this.productEntityRepository = productEntityRepository;
    }

    List<Long> getAllOrderIds(String token) {
        UserEntity user = getValidatedUserFromToken(token);

        List<Long> ids = new ArrayList<>();
        ids.addAll(orderGroupEntityRepository.findByUserId(user.getId()).stream().map(OrderGroupEntity::getId).toList());
        ids.addAll(orderEntityRepository.findByUserIdAndOrderGroupIsNull(user.getId()).stream().map(OrderEntity::getId).toList());
        return ids.stream().distinct().toList();
    }

    String getOrderStatusByOrderId(Long orderId, String token) {
        UserEntity user = getValidatedUserFromToken(token);

        OrderGroupEntity orderGroup = orderGroupEntityRepository.findByIdAndUserId(orderId, user.getId()).orElse(null);
        if (orderGroup != null) {
            return orderGroup.getStatus();
        }

        OrderEntity legacyOrder = orderEntityRepository.findByIdAndUserId(orderId, user.getId())
                .orElseThrow(() -> new GeneralException("Order not found"));
        return legacyOrder.getStatus();
    }

    List<UserOrderDetailDto> getUserOrderDetails(String token) {
        UserEntity user = getValidatedUserFromToken(token);

        List<UserOrderDetailDto> result = new ArrayList<>();

        List<OrderGroupEntity> groups = orderGroupEntityRepository.findByUserId(user.getId());
        List<OrderEntity> legacyOrders = orderEntityRepository.findByUserIdAndOrderGroupIsNull(user.getId());

        List<Long> productIds = getOrderProductIds(groups, legacyOrders);

        if (productIds.isEmpty()) {
            return result;
        }

        Map<Long, ProductEntity> productMap = getProductsByIds(productIds);

        for (OrderGroupEntity group : groups) {
            for (OrderEntity order : group.getOrderItems()) {
                result.add(toOrderDetailDto(order, productMap.get(order.getItemId()), group.getStatus()));
            }
        }

        for (OrderEntity legacy : legacyOrders) {
            result.add(toOrderDetailDto(legacy, productMap.get(legacy.getItemId()), legacy.getStatus()));
        }

        return result;
    }

    List<UserOrderGroupDto> getUserOrderGroups(String token) {
        UserEntity user = getValidatedUserFromToken(token);

        List<UserOrderGroupDto> result = new ArrayList<>();

        List<OrderGroupEntity> groups = orderGroupEntityRepository.findByUserId(user.getId());
        List<OrderEntity> legacyOrders = orderEntityRepository.findByUserIdAndOrderGroupIsNull(user.getId());

        List<Long> productIds = getOrderProductIds(groups, legacyOrders);

        if (productIds.isEmpty()) {
            return result;
        }

        Map<Long, ProductEntity> productMap = getProductsByIds(productIds);

        for (OrderGroupEntity group : groups) {
            UserOrderGroupDto groupDto = new UserOrderGroupDto();
            groupDto.setOrderGroupId(group.getId());
            groupDto.setCreatedAt(group.getCreatedAt());

            List<UserOrderDetailDto> items = new ArrayList<>();
            for (OrderEntity order : group.getOrderItems()) {
                items.add(toOrderDetailDto(order, productMap.get(order.getItemId()), group.getStatus()));
            }
            groupDto.setItems(items);
            result.add(groupDto);
        }

        for (OrderEntity legacy : legacyOrders) {
            UserOrderGroupDto groupDto = new UserOrderGroupDto();
            groupDto.setOrderGroupId(legacy.getId());
            groupDto.setCreatedAt(null);

            groupDto.setItems(List.of(toOrderDetailDto(legacy, productMap.get(legacy.getItemId()), legacy.getStatus())));

            result.add(groupDto);
        }

        result.sort((a, b) -> Long.compare(b.getOrderGroupId(), a.getOrderGroupId()));
        return result;
    }

    private List<Long> getOrderProductIds(List<OrderGroupEntity> groups, List<OrderEntity> legacyOrders) {
        List<Long> productIds = new ArrayList<>();
        productIds.addAll(groups.stream().flatMap(group -> group.getOrderItems().stream()).map(OrderEntity::getItemId).toList());
        productIds.addAll(legacyOrders.stream().map(OrderEntity::getItemId).toList());
        return productIds;
    }

    private Map<Long, ProductEntity> getProductsByIds(List<Long> productIds) {
        return productEntityRepository.findAllById(productIds.stream().distinct().toList())
                .stream()
                .collect(Collectors.toMap(ProductEntity::getId, Function.identity()));
    }

    private UserOrderDetailDto toOrderDetailDto(OrderEntity order, ProductEntity product, String status) {
        UserOrderDetailDto dto = new UserOrderDetailDto();
        dto.setOrderId(order.getId());
        dto.setProductId(order.getItemId());
        dto.setProductName(product != null ? product.getProductName() : "");
        dto.setCount(order.getCount());
        dto.setPrice(order.getPrice());
        dto.setTotalPrice(order.getTotalPrice());
        dto.setStatus(status);
        return dto;
    }

    private UserEntity getValidatedUserFromToken(String token) {
        String userName = tokenService.validateAndExtractUser(token);

        UserEntity user = userEntityRepository.findByUserMail(userName);
        if (user == null) {
            throw new UserNotFoundException(userName);
        }

        return user;
    }
}
