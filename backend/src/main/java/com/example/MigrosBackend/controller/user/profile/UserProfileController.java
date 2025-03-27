package com.example.MigrosBackend.controller.user.profile;

import com.example.MigrosBackend.dto.user.UserProfileTableDto;
import com.example.MigrosBackend.entity.user.UserEntity;
import com.example.MigrosBackend.repository.user.UserEntityRepository;
import com.example.MigrosBackend.service.global.TokenService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("user/profile")
public class UserProfileController {
    private final TokenService tokenService;
    private final UserEntityRepository userEntityRepository;

    public UserProfileController(UserEntityRepository userEntityRepository,
                                 TokenService tokenService) {
        this.userEntityRepository = userEntityRepository;
        this.tokenService = tokenService;
    }

    @PostMapping("uploadUserProfileTable")
    public String uploadUserProfileTable(@RequestParam("userFirstName") String userFirstName,
                                         @RequestParam("userLastName") String userLastName,
                                         @RequestParam("userAddress") String userAddress,
                                         @RequestParam("userAddress2") String userAddress2,
                                         @RequestParam("userTown") String userTown,
                                         @RequestParam("userCountry") String userCountry,
                                         @RequestParam("userPostalCode") String userPostalCode,
                                         @RequestParam String token) {
        String userName = tokenService.extractUsername(token);
        if(!tokenService.validateToken(token, userName))
            return "Invalid token";

        UserEntity user = userEntityRepository.findByUserMail(userName);
        user.setUserName(userFirstName);
        user.setUserLastName(userLastName);
        user.setUserAddress(userAddress);
        user.setUserAddress2(userAddress2);
        user.setUserTown(userTown);
        user.setUserCountry(userCountry);
        user.setUserPostalCode(userPostalCode);
        userEntityRepository.save(user);

        return "User profile table uploaded";
    }
}
