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
    private ResponseEntity<?> addItem(@RequestBody UserSignDto userSignDto) throws Exception {
        try{
            userSignupService.signup(userSignDto);
            return new ResponseEntity<>(HttpStatus.OK);
        } catch (Exception e) {
            return new ResponseEntity<>(e.getMessage(), HttpStatus.BAD_REQUEST);
        }
    }
    @GetMapping("signup/confirm")
    private ResponseEntity<?> confirm(@RequestParam String token) throws Exception {
        try{
            userSignupService.confirm(token);
            return new ResponseEntity<>("Your account has been created successfully, you can close this page now.",HttpStatus.OK);
        } catch (Exception e) {
            return new ResponseEntity<>(e.getMessage(), HttpStatus.BAD_REQUEST);
        }
    }
    @PostMapping("login")
    private ResponseEntity<?> login(@RequestBody UserSignDto userSignDto) throws Exception {
        try{
            String token = userSignupService.login(userSignDto);
            return ResponseEntity.ok(token);
        } catch (Exception e) {
            return new ResponseEntity<>(e.getMessage(), HttpStatus.BAD_REQUEST);
        }
    }
    @PostMapping("addProductToInventory")
    private ResponseEntity<?> addItem(@RequestParam String token, @RequestBody ProductEntity productDto) throws Exception {
        try{
            userSignupService.addProductToInventory(token, productDto);
            return new ResponseEntity<>(HttpStatus.OK);
        } catch (Exception e) {
            return new ResponseEntity<>(e.getMessage(), HttpStatus.BAD_REQUEST);
        }
    }
}
