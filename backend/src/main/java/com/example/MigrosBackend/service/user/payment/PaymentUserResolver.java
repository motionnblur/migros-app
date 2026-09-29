package com.example.MigrosBackend.service.user.payment;

import com.example.MigrosBackend.entity.user.UserEntity;
import com.example.MigrosBackend.exception.admin.UserNotFoundException;
import com.example.MigrosBackend.repository.user.UserEntityRepository;
import com.example.MigrosBackend.service.global.TokenService;
import org.springframework.stereotype.Component;

/**
 * Single home for the payment package's token-to-user resolution. Owned by the
 * user-identity unification in the controller layer; until that lands, the
 * payment services resolve through this shared component instead of keeping
 * their own private copy.
 */
@Component
class PaymentUserResolver {

    private final TokenService tokenService;
    private final UserEntityRepository userEntityRepository;

    PaymentUserResolver(TokenService tokenService, UserEntityRepository userEntityRepository) {
        this.tokenService = tokenService;
        this.userEntityRepository = userEntityRepository;
    }

    UserEntity requireUser(String userToken) {
        String userMail = tokenService.validateAndExtractUser(userToken);
        UserEntity user = userEntityRepository.findByUserMail(userMail);
        if (user == null) {
            throw new UserNotFoundException(userMail);
        }
        return user;
    }
}
