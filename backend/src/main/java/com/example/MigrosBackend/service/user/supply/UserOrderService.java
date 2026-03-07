package com.example.MigrosBackend.service.user.supply;

import com.example.MigrosBackend.dto.order.OrderDto;
import com.example.MigrosBackend.dto.user.UserProfileTableDto;
import com.example.MigrosBackend.entity.product.ProductEntity;
import com.example.MigrosBackend.entity.user.OrderEntity;
import com.example.MigrosBackend.entity.user.OrderGroupEntity;
import com.example.MigrosBackend.entity.user.UserEntity;
import com.example.MigrosBackend.exception.admin.OrderNotFoundException;
import com.example.MigrosBackend.exception.admin.UserNotFoundException;
import com.example.MigrosBackend.repository.product.ProductEntityRepository;
import com.example.MigrosBackend.repository.user.OrderEntityRepository;
import com.example.MigrosBackend.repository.user.OrderGroupEntityRepository;
import com.example.MigrosBackend.repository.user.UserEntityRepository;
import com.example.MigrosBackend.service.global.TokenService;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class UserOrderService {
    private final TokenService tokenService;
    private final UserEntityRepository userEntityRepository;
    private final OrderEntityRepository orderEntityRepository;
    private final OrderGroupEntityRepository orderGroupEntityRepository;
    private final ProductEntityRepository productEntityRepository;

    public UserOrderService(TokenService tokenService,
                            UserEntityRepository userEntityRepository,
                            OrderEntityRepository orderEntityRepository,
                            OrderGroupEntityRepository orderGroupEntityRepository,
                            ProductEntityRepository productEntityRepository) {
        this.tokenService = tokenService;
        this.userEntityRepository = userEntityRepository;
        this.orderEntityRepository = orderEntityRepository;
        this.orderGroupEntityRepository = orderGroupEntityRepository;
        this.productEntityRepository = productEntityRepository;
    }

    @Async
    public void clearUserCart(String userToken) {
        String userName = tokenService.extractUsername(userToken);
        UserEntity user = userEntityRepository.findByUserMail(userName);
        if (tokenService.validateToken(userToken, user.getUserMail())) {
            user.setProductsIdsInCart(new ArrayList<>());
            userEntityRepository.save(user);
        }
    }

    @Async
    public void createOrder(String userToken) {
        String userName = tokenService.extractUsername(userToken);
        UserEntity user = userEntityRepository.findByUserMail(userName);

        if (!tokenService.validateToken(userToken, user.getUserMail())) {
            return;
        }

        List<Long> productsInCart = user.getProductsIdsInCart();
        if (productsInCart == null || productsInCart.isEmpty()) {
            return;
        }

        Map<Long, Integer> productCounts = productsInCart.stream()
                .collect(Collectors.groupingBy(Function.identity(), Collectors.summingInt(e -> 1)));

        OrderGroupEntity orderGroup = new OrderGroupEntity();
        orderGroup.setUserEntity(user);
        orderGroup.setUserId(user.getId());
        orderGroup.setCreatedAt(LocalDateTime.now());
        orderGroup.setStatus("Pending");
        orderGroup = orderGroupEntityRepository.save(orderGroup);

        for (Map.Entry<Long, Integer> entry : productCounts.entrySet()) {
            Long productId = entry.getKey();
            Integer count = entry.getValue();

            ProductEntity product = productEntityRepository.findById(productId)
                    .orElseThrow(() -> new OrderNotFoundException(productId.toString()));

            OrderEntity order = new OrderEntity();
            order.setUserEntity(user);
            order.setOrderGroup(orderGroup);
            order.setUserId(user.getId());
            order.setItemId(productId);
            order.setPrice(product.getProductPrice());
            order.setCount(count);
            order.setTotalPrice(product.getProductPrice() * count);
            order.setStatus(orderGroup.getStatus());
            orderEntityRepository.save(order);
        }

        clearUserCart(userToken);
    }

    public float getOrderPrice(String userToken) {
        String userName = tokenService.extractUsername(userToken);
        UserEntity user = userEntityRepository.findByUserMail(userName);
        if (tokenService.validateToken(userToken, user.getUserMail())) {
            List<Long> productsInCart = user.getProductsIdsInCart();
            if (productsInCart == null || productsInCart.isEmpty()) {
                return 0;
            }

            Map<Long, Integer> productCounts = productsInCart.stream()
                    .collect(Collectors.groupingBy(Function.identity(), Collectors.summingInt(e -> 1)));

            float total = 0;
            for (Map.Entry<Long, Integer> entry : productCounts.entrySet()) {
                ProductEntity product = productEntityRepository.findById(entry.getKey())
                        .orElseThrow(() -> new OrderNotFoundException(entry.getKey().toString()));
                total += product.getProductPrice() * entry.getValue();
            }
            return total;
        }
        return 0;
    }

    public List<OrderDto> getAllOrders(int page, int productRange) {
        List<OrderDto> orderDtos = new ArrayList<>();

        List<OrderGroupEntity> groups = orderGroupEntityRepository.findAll();
        for (OrderGroupEntity group : groups) {
            List<OrderEntity> items = orderEntityRepository.findByOrderGroup_Id(group.getId());
            float totalPrice = 0;
            for (OrderEntity item : items) {
                if (item.getTotalPrice() != null) {
                    totalPrice += item.getTotalPrice();
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
            dto.setTotalPrice(legacy.getTotalPrice() != null ? legacy.getTotalPrice() : 0);
            dto.setStatus(legacy.getStatus());
            orderDtos.add(dto);
        }

        orderDtos.sort((a, b) -> Long.compare(b.getOrderId(), a.getOrderId()));
        int fromIndex = Math.max(0, page * productRange);
        if (fromIndex >= orderDtos.size()) {
            return new ArrayList<>();
        }
        int toIndex = Math.min(orderDtos.size(), fromIndex + productRange);
        return new ArrayList<>(orderDtos.subList(fromIndex, toIndex));
    }

    public UserProfileTableDto getUserProfileData(Long orderId) {
        UserEntity userEntity;

        OrderGroupEntity orderGroup = orderGroupEntityRepository.findById(orderId).orElse(null);
        if (orderGroup != null) {
            userEntity = userEntityRepository.findById(orderGroup.getUserId())
                    .orElseThrow(() -> new UserNotFoundException(orderGroup.getUserId().toString()));
        } else {
            OrderEntity legacyOrder = orderEntityRepository.findById(orderId)
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

    public void updateOrderStatus(Long orderId, String status) {
        OrderGroupEntity orderGroup = orderGroupEntityRepository.findById(orderId).orElse(null);
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

        OrderEntity legacyOrder = orderEntityRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId.toString()));
        legacyOrder.setStatus(status);
        orderEntityRepository.save(legacyOrder);
    }

    public void deleteOrder(Long orderId) {
        OrderGroupEntity orderGroup = orderGroupEntityRepository.findById(orderId).orElse(null);
        if (orderGroup != null) {
            List<OrderEntity> items = orderEntityRepository.findByOrderGroup_Id(orderGroup.getId());
            orderEntityRepository.deleteAll(items);
            orderGroupEntityRepository.delete(orderGroup);
            return;
        }

        OrderEntity legacyOrder = orderEntityRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId.toString()));
        orderEntityRepository.delete(legacyOrder);
    }
}
