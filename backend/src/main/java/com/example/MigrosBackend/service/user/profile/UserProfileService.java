package com.example.MigrosBackend.service.user.profile;

import com.example.MigrosBackend.dto.user.UserProfileTableDto;
import com.example.MigrosBackend.entity.user.UserEntity;
import com.example.MigrosBackend.repository.user.UserEntityRepository;
import org.springframework.stereotype.Service;

@Service
public class UserProfileService {
    private final UserEntityRepository userEntityRepository;

    public UserProfileService(UserEntityRepository userEntityRepository) {
        this.userEntityRepository = userEntityRepository;
    }

    public void uploadUserProfileTable(String userFirstName, String userLastName, String userAddress, String userAddress2,
                                       String userTown, String userCountry, String userPostalCode, String userMail) {
        UserEntity user = userEntityRepository.findByUserMail(userMail);
        user.setUserName(userFirstName);
        user.setUserLastName(userLastName);
        user.setUserAddress(userAddress);
        user.setUserAddress2(userAddress2);
        user.setUserTown(userTown);
        user.setUserCountry(userCountry);
        user.setUserPostalCode(userPostalCode);

        userEntityRepository.save(user);
    }

    public UserProfileTableDto getUserProfileTable(String userMail) {
        UserEntity user = userEntityRepository.findByUserMail(userMail);
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
