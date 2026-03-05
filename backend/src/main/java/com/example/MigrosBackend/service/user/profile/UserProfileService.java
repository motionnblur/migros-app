package com.example.MigrosBackend.service.user.profile;

import com.example.MigrosBackend.dto.user.UserProfileTableDto;
import com.example.MigrosBackend.entity.user.UserEntity;
import com.example.MigrosBackend.exception.shared.InvalidTokenException;
import com.example.MigrosBackend.repository.user.UserEntityRepository;
import com.example.MigrosBackend.service.global.TokenService;
import org.springframework.stereotype.Service;

@Service
public class UserProfileService {
    private final UserEntityRepository userEntityRepository;
    private final TokenService tokenService;

    public UserProfileService(UserEntityRepository userEntityRepository,
                              TokenService tokenService) {
        this.userEntityRepository = userEntityRepository;
        this.tokenService = tokenService;
    }

    public void uploadUserProfileTable(String userFirstName, String userLastName, String userAddress, String userAddress2,
                                       String userTown, String userCountry, String userPostalCode, String token) {
        String userName = tokenService.extractUsername(token);
        if (!tokenService.validateToken(token, userName))
            throw new InvalidTokenException();

        UserEntity user = userEntityRepository.findByUserMail(userName);
        user.setUserName(userFirstName);
        user.setUserLastName(userLastName);
        user.setUserAddress(userAddress);
        user.setUserAddress2(userAddress2);
        user.setUserTown(userTown);
        user.setUserCountry(userCountry);
        user.setUserPostalCode(userPostalCode);

        userEntityRepository.save(user);
    }

    public UserProfileTableDto getUserProfileTable(String token) {
        String userName = tokenService.extractUsername(token);
        if (!tokenService.validateToken(token, userName))
            throw new InvalidTokenException();

        UserEntity user = userEntityRepository.findByUserMail(userName);
        UserProfileTableDto userProfileTableDto = new UserProfileTableDto();
        userProfileTableDto.setUserFirstName(user.getUserName());
        userProfileTableDto.setUserLastName(user.getUserLastName());
        userProfileTableDto.setUserAddress(user.getUserAddress());
        userProfileTableDto.setUserAddress2(user.getUserAddress2());
        userProfileTableDto.setUserTown(user.getUserTown());
        userProfileTableDto.setUserCountry(user.getUserCountry());
        userProfileTableDto.setUserPostalCode(user.getUserPostalCode());

        return userProfileTableDto;
    }
}
