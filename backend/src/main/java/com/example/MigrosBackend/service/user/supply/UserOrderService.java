package com.example.MigrosBackend.service.user.supply;

import com.example.MigrosBackend.entity.user.OrderEntity;
import com.example.MigrosBackend.entity.user.UserEntity;
import com.example.MigrosBackend.repository.user.OrderEntityRepository;
import com.example.MigrosBackend.repository.user.UserEntityRepository;
import com.example.MigrosBackend.service.global.TokenService;
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
}
