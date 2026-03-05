package com.example.MigrosBackend.controller.user.profile;

import com.example.MigrosBackend.dto.user.UserProfileTableDto;
import com.example.MigrosBackend.entity.user.UserEntity;
import com.example.MigrosBackend.exception.shared.InvalidTokenException;
import com.example.MigrosBackend.repository.user.UserEntityRepository;
import com.example.MigrosBackend.service.global.TokenService;
import com.example.MigrosBackend.service.user.profile.UserProfileService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("user/profile")
public class UserProfileController {
    private final UserProfileService userProfileService;

    public UserProfileController(UserProfileService userProfileService) {
        this.userProfileService = userProfileService;
    }

    @PostMapping("uploadUserProfileTable")
    public ResponseEntity<Void> uploadUserProfileTable(@RequestParam("userFirstName") String userFirstName,
                                                       @RequestParam("userLastName") String userLastName,
                                                       @RequestParam("userAddress") String userAddress,
                                                       @RequestParam("userAddress2") String userAddress2,
                                                       @RequestParam("userTown") String userTown,
                                                       @RequestParam("userCountry") String userCountry,
                                                       @RequestParam("userPostalCode") String userPostalCode,
                                                       @RequestParam String token) {
        userProfileService.uploadUserProfileTable(
                userFirstName, userLastName,
                userAddress, userAddress2,
                userTown, userCountry,
                userPostalCode, token
        );
        return ResponseEntity.ok().build();
    }

    @GetMapping("getUserProfileTable")
    public ResponseEntity<UserProfileTableDto> getUserProfileTable(@RequestParam String token) {
        return ResponseEntity.ok(userProfileService.getUserProfileTable(token));
    }
}
