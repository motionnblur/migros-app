package com.example.MigrosBackend.controller.user.profile;

import com.example.MigrosBackend.config.security.AuthCookies;
import com.example.MigrosBackend.dto.user.UserProfileTableDto;
import com.example.MigrosBackend.helper.AuthTokenResolver;
import com.example.MigrosBackend.service.user.profile.UserProfileService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("user/profile")
public class UserProfileController {
    private final UserProfileService userProfileService;
    private final AuthTokenResolver authTokenResolver;

    public UserProfileController(UserProfileService userProfileService, AuthTokenResolver authTokenResolver) {
        this.userProfileService = userProfileService;
        this.authTokenResolver = authTokenResolver;
    }

    @PostMapping("uploadUserProfileTable")
    public ResponseEntity<Void> uploadUserProfileTable(@RequestParam("userFirstName") String userFirstName,
                                                       @RequestParam("userLastName") String userLastName,
                                                       @RequestParam("userAddress") String userAddress,
                                                       @RequestParam("userAddress2") String userAddress2,
                                                       @RequestParam("userTown") String userTown,
                                                       @RequestParam("userCountry") String userCountry,
                                                       @RequestParam("userPostalCode") String userPostalCode,
                                                       @CookieValue(name = AuthCookies.SESSION_COOKIE_NAME, required = false) String token) {
        userProfileService.uploadUserProfileTable(
                userFirstName, userLastName,
                userAddress, userAddress2,
                userTown, userCountry,
                userPostalCode, authTokenResolver.requireToken(token)
        );
        return ResponseEntity.ok().build();
    }

    @GetMapping("getUserProfileTable")
    public ResponseEntity<UserProfileTableDto> getUserProfileTable(
            @CookieValue(name = AuthCookies.SESSION_COOKIE_NAME, required = false) String token) {
        return ResponseEntity.ok(userProfileService.getUserProfileTable(authTokenResolver.requireToken(token)));
    }
}
