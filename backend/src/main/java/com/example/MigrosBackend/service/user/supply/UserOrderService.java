package com.example.MigrosBackend.service.user.supply;

import com.example.MigrosBackend.dto.order.OrderDto;
import com.example.MigrosBackend.dto.order.OrderPageDto;
import com.example.MigrosBackend.dto.user.UserProfileTableDto;
import com.example.MigrosBackend.entity.product.ProductEntity;
import com.example.MigrosBackend.entity.user.OrderEntity;
import com.example.MigrosBackend.entity.user.OrderGroupEntity;
import com.example.MigrosBackend.entity.user.UserEntity;
import com.example.MigrosBackend.exception.admin.OrderNotFoundException;
import com.example.MigrosBackend.exception.admin.UserNotFoundException;
import com.example.MigrosBackend.exception.shared.GeneralException;
import com.example.MigrosBackend.repository.product.ProductEntityRepository;
import com.example.MigrosBackend.repository.user.OrderEntityRepository;
import com.example.MigrosBackend.repository.user.OrderGroupEntityRepository;
import com.example.MigrosBackend.repository.user.UserEntityRepository;
import com.example.MigrosBackend.service.global.TokenService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    public void clearUserCart(String userToken) {
        UserEntity user = getValidatedUser(userToken);
        user.setProductsIdsInCart(new ArrayList<>());
        userEntityRepository.save(user);
    }

    @Transactional
    public void createOrder(String userToken) {
        UserEntity user = getValidatedUser(userToken);

        List<Long> productsInCart = user.getProductsIdsInCart();
        if (productsInCart == null || productsInCart.isEmpty()) {
            return;
        }

        Map<Long, Integer> productCounts = productsInCart.stream()
                .collect(Collectors.groupingBy(Function.identity(), Collectors.summingInt(e -> 1)));

        Map<Long, ProductEntity> productMap = productEntityRepository.findAllById(productCounts.keySet())
                .stream()
                .collect(Collectors.toMap(ProductEntity::getId, Function.identity()));

        for (Map.Entry<Long, Integer> entry : productCounts.entrySet()) {
            ProductEntity product = productMap.get(entry.getKey());
            if (product == null) {
                throw new GeneralException("One or more products are no longer available.");
            }
            if (product.getProductCount() < entry.getValue()) {
                throw new GeneralException("Insufficient stock for product: " + product.getProductName());
            }
        }

        OrderGroupEntity orderGroup = new OrderGroupEntity();
        orderGroup.setUserEntity(user);
        orderGroup.setUserId(user.getId());
        orderGroup.setCreatedAt(LocalDateTime.now());
        orderGroup.setStatus("Pending");
        orderGroup = orderGroupEntityRepository.save(orderGroup);

        for (Map.Entry<Long, Integer> entry : productCounts.entrySet()) {
            Long productId = entry.getKey();
            Integer count = entry.getValue();

            ProductEntity product = productMap.get(productId);

            OrderEntity order = new OrderEntity();
            order.setUserEntity(user);
            order.setOrderGroup(orderGroup);
            order.setUserId(user.getId());
            order.setItemId(productId);
            float effectivePrice = getEffectivePrice(product);
            order.setPrice(effectivePrice);
            order.setCount(count);
            order.setTotalPrice(effectivePrice * count);
            order.setStatus(orderGroup.getStatus());
            orderEntityRepository.save(order);

            int remainingStock = product.getProductCount() - count;
            product.setProductCount(remainingStock);
        }

        productEntityRepository.saveAll(productMap.values());

        user.setProductsIdsInCart(new ArrayList<>());
        userEntityRepository.save(user);
    }

    public float getOrderPrice(String userToken) {
        UserEntity user = getValidatedUser(userToken);

        List<Long> productsInCart = user.getProductsIdsInCart();
        if (productsInCart == null || productsInCart.isEmpty()) {
            return 0;
        }

        Map<Long, Integer> productCounts = productsInCart.stream()
                .collect(Collectors.groupingBy(Function.identity(), Collectors.summingInt(e -> 1)));

        Map<Long, ProductEntity> productMap = productEntityRepository.findAllById(productCounts.keySet())
                .stream()
                .collect(Collectors.toMap(ProductEntity::getId, Function.identity()));

        float total = 0;
        for (Map.Entry<Long, Integer> entry : productCounts.entrySet()) {
            ProductEntity product = productMap.get(entry.getKey());
            if (product == null) {
                throw new GeneralException("One or more products are no longer available.");
            }
            if (product.getProductCount() < entry.getValue()) {
                throw new GeneralException("Insufficient stock for product: " + product.getProductName());
            }
            total += getEffectivePrice(product) * entry.getValue();
        }
        return total;
    }

    public OrderPageDto getAllOrders(int page, int productRange) {
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

    @Transactional
    public void deleteOrder(Long orderId) {
        OrderGroupEntity orderGroup = orderGroupEntityRepository.findById(orderId).orElse(null);
        if (orderGroup != null) {
            List<OrderEntity> items = orderEntityRepository.findByOrderGroup_Id(orderGroup.getId());
            if ("Pending".equalsIgnoreCase(orderGroup.getStatus())) {
                restockOrderItems(items);
            }
            orderEntityRepository.deleteAll(items);
            orderGroupEntityRepository.delete(orderGroup);
            return;
        }

        OrderEntity legacyOrder = orderEntityRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId.toString()));
        if ("Pending".equalsIgnoreCase(legacyOrder.getStatus())) {
            ProductEntity product = productEntityRepository.findById(legacyOrder.getItemId()).orElse(null);
            if (product != null) {
                product.setProductCount(product.getProductCount() + legacyOrder.getCount());
                productEntityRepository.save(product);
            }
        }
        orderEntityRepository.delete(legacyOrder);
    }


    private float getEffectivePrice(ProductEntity product) {
        float discount = product.getProductDiscount();
        float price = product.getProductPrice();
        if (discount <= 0) {
            return price;
        }
        return price - (price * discount / 100);
    }

    private void restockOrderItems(List<OrderEntity> orderItems) {
        for (OrderEntity orderItem : orderItems) {
            ProductEntity product = productEntityRepository.findById(orderItem.getItemId()).orElse(null);
            if (product == null) {
                continue;
            }
            product.setProductCount(product.getProductCount() + orderItem.getCount());
            productEntityRepository.save(product);
        }
    }
    private UserEntity getValidatedUser(String userToken) {
        String userName = tokenService.extractUsername(userToken);
        UserEntity user = userEntityRepository.findByUserMail(userName);
        if (user == null || !tokenService.validateToken(userToken, user.getUserMail())) {
            throw new UserNotFoundException("User not found for active session");
        }
        return user;
    }
}

