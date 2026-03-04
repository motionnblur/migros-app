package com.example.MigrosBackend.controller.user.sign;

import com.example.MigrosBackend.dto.user.sign.UserSignDto;
import com.example.MigrosBackend.entity.product.ProductEntity;
import com.example.MigrosBackend.service.user.sign.UserSignupService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("user")
public class UserSignController {
    private final UserSignupService userSignupService;

    @Autowired
    public UserSignController(UserSignupService userSignupService) {
        this.userSignupService = userSignupService;
    }

    @PostMapping("signup")
    private ResponseEntity<Void> addItem(@RequestBody UserSignDto userSignDto) {
        userSignupService.signup(userSignDto);
        return ResponseEntity.ok().build();
    }

    @GetMapping("signup/confirm")
    private ResponseEntity<String> confirm(@RequestParam String token) {
        userSignupService.confirm(token);
        return ResponseEntity.ok("Your account has been created successfully, you can close this page now.");
    }

    @PostMapping("login")
    private ResponseEntity<String> login(@RequestBody UserSignDto userSignDto) {
        return ResponseEntity.ok(userSignupService.login(userSignDto));
    }
}
