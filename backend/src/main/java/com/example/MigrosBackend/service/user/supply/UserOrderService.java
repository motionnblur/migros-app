package com.example.MigrosBackend.service.user.supply;

import com.example.MigrosBackend.dto.order.OrderDto;
import com.example.MigrosBackend.entity.user.OrderEntity;
import com.example.MigrosBackend.entity.user.UserEntity;
import com.example.MigrosBackend.repository.user.OrderEntityRepository;
import com.example.MigrosBackend.repository.user.UserEntityRepository;
import com.example.MigrosBackend.service.global.TokenService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class UserOrderService {
    private final TokenService tokenService;
    private final UserEntityRepository userEntityRepository;
    private final OrderEntityRepository orderEntityRepository;
    public UserOrderService(TokenService tokenService,
                            UserEntityRepository userEntityRepository,
                            OrderEntityRepository orderEntityRepository) {
        this.tokenService = tokenService;
        this.userEntityRepository = userEntityRepository;
        this.orderEntityRepository = orderEntityRepository;
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

            OrderEntity order = new OrderEntity();
            order.setAdminId(user.getId());
            order.setOrderIds(productsInCart);

            orderEntityRepository.save(order);
        }
    }

    public List<OrderDto> getAllOrders(String userToken, int page, int productRange) {
        String userName = tokenService.extractUsername(userToken);
        UserEntity user = userEntityRepository.findByUserMail(userName);
        if(tokenService.validateToken(userToken, user.getUserMail()))
        {
            Pageable pageable = PageRequest.of(page, productRange);
            Page<OrderEntity> orders = orderEntityRepository.findByAdminId(user.getId(), pageable);

            List<OrderDto> orderDtos = new ArrayList<>();
            for (OrderEntity order : orders) {
                OrderDto orderDto = new OrderDto();
                orderDto.setOrderId(order.getId());
                orderDto.setUserName(user.getUserMail());
                orderDtos.add(orderDto);
            }
            return orderDtos;
        }
        return null;
    }
}
