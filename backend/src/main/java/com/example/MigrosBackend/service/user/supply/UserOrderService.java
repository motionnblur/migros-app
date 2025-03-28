package com.example.MigrosBackend.service.user.supply;

import com.example.MigrosBackend.dto.order.OrderDto;
import com.example.MigrosBackend.dto.user.UserProfileTableDto;
import com.example.MigrosBackend.entity.product.ProductEntity;
import com.example.MigrosBackend.entity.user.OrderEntity;
import com.example.MigrosBackend.entity.user.UserEntity;
import com.example.MigrosBackend.repository.product.ProductEntityRepository;
import com.example.MigrosBackend.repository.user.OrderEntityRepository;
import com.example.MigrosBackend.repository.user.UserEntityRepository;
import com.example.MigrosBackend.service.global.TokenService;
import org.apache.catalina.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

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
    private final ProductEntityRepository productEntityRepository;
    public UserOrderService(TokenService tokenService,
                            UserEntityRepository userEntityRepository,
                            OrderEntityRepository orderEntityRepository,
                            ProductEntityRepository productEntityRepository) {
        this.tokenService = tokenService;
        this.userEntityRepository = userEntityRepository;
        this.orderEntityRepository = orderEntityRepository;
        this.productEntityRepository = productEntityRepository;
    }

    @Async
    public void clearUserCart(String userToken) {
        String userName = tokenService.extractUsername(userToken);
        UserEntity user = userEntityRepository.findByUserMail(userName);
        if(tokenService.validateToken(userToken, user.getUserMail()))
        {
            user.setProductsIdsInCart(new ArrayList<>());
            userEntityRepository.save(user);
        }
    }

    @Async
    public void createOrder(String userToken) {
        String userName = tokenService.extractUsername(userToken);
        UserEntity user = userEntityRepository.findByUserMail(userName);
        if(tokenService.validateToken(userToken, user.getUserMail()))
        {
            List<Long> productsInCart = user.getProductsIdsInCart();
            Map<Long, Integer> productCounts = productsInCart.stream()
                    .collect(Collectors.groupingBy(Function.identity(), Collectors.summingInt(e -> 1)));

            productCounts.forEach((productId, count) -> {
                OrderEntity order = new OrderEntity();
                order.setUserEntity(user);
                order.setUserId(user.getId());
                order.setItemId(productId);
                ProductEntity product = productEntityRepository.findById(productId).get();
                order.setPrice(product.getProductPrice());
                order.setCount(count);
                order.setTotalPrice(product.getProductPrice() * order.getCount());
                order.setStatus("Pending");
                orderEntityRepository.save(order);
            });

            clearUserCart(userToken);
        }
    }

    public float getOrderPrice(String userToken) {
        String userName = tokenService.extractUsername(userToken);
        UserEntity user = userEntityRepository.findByUserMail(userName);
        if(tokenService.validateToken(userToken, user.getUserMail()))
        {
            List<Long> productsInCart = user.getProductsIdsInCart();
            Map<Long, Integer> productCounts = productsInCart.stream()
                    .collect(Collectors.groupingBy(Function.identity(), Collectors.summingInt(e -> 1)));
            float total = 0;
            for (Map.Entry<Long, Integer> entry : productCounts.entrySet()) {
                ProductEntity product = productEntityRepository.findById(entry.getKey()).get();
                total += product.getProductPrice() * entry.getValue();
            }
            return total;
        }
        return 0;
    }

    public List<OrderDto> getAllOrders(int page, int productRange) {
        Pageable pageable = PageRequest.of(page, productRange);
        Page<OrderEntity> orders = orderEntityRepository.findAll(pageable);

        List<OrderDto> orderDtos = new ArrayList<>();
        for (OrderEntity order : orders) {
            OrderDto orderDto = new OrderDto();
            orderDto.setOrderId(order.getId());
            orderDto.setTotalPrice(order.getTotalPrice());
            orderDto.setStatus(order.getStatus());

            orderDtos.add(orderDto);
        }
        return orderDtos;
    }

    public UserProfileTableDto getUserProfileData(Long orderId) {
        OrderEntity orderEntity = orderEntityRepository.findById(orderId).get();
        UserEntity userEntity = userEntityRepository.findById(orderEntity.getUserId()).get();

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
        OrderEntity orderEntity = orderEntityRepository.findById(orderId).get();
        orderEntity.setStatus(status);
        orderEntityRepository.save(orderEntity);
    }
}
