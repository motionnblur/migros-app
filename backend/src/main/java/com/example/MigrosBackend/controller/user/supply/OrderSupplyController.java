package com.example.MigrosBackend.controller.user.supply;

import com.example.MigrosBackend.entity.user.OrderEntity;
import com.example.MigrosBackend.entity.user.UserEntity;
import com.example.MigrosBackend.repository.user.OrderEntityRepository;
import com.example.MigrosBackend.repository.user.UserEntityRepository;
import com.example.MigrosBackend.service.global.TokenService;
import com.example.MigrosBackend.service.user.supply.UserOrderService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("user/orders")
public class OrderSupplyController {
    private final UserOrderService userOrderService;
    private final TokenService tokenService;
    private final OrderEntityRepository orderEntityRepository;
    private final UserEntityRepository userEntityRepository;

    public OrderSupplyController(UserOrderService userOrderService,
                                 TokenService tokenService,
                                 OrderEntityRepository orderEntityRepository,
                                 UserEntityRepository userEntityRepository) {
        this.userOrderService = userOrderService;
        this.tokenService = tokenService;
        this.orderEntityRepository = orderEntityRepository;
        this.userEntityRepository = userEntityRepository;
    }

    @GetMapping("getOrders") //For testing purposes
    public ResponseEntity<?> getOrder(@RequestParam String userToken) {
        try {
            String userName = tokenService.extractUsername(userToken);
            UserEntity user = userEntityRepository.findByUserMail(userName);
            if(tokenService.validateToken(userToken, user.getUserMail()))
            {
                OrderEntity orders = orderEntityRepository.findByAdminId(user.getId());
                return ResponseEntity.ok(orders.getOrderIds());
            }
            return new ResponseEntity<>("Order created", HttpStatus.OK);
        } catch (Exception e) {
            return new ResponseEntity<>(e.getMessage(), HttpStatus.BAD_REQUEST);
        }
    }
}
