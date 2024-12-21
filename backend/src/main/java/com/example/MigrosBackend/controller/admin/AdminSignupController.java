package com.example.MigrosBackend.controller.admin;

import com.example.MigrosBackend.dto.AdminSignupDto;
import com.example.MigrosBackend.service.admin.AdminSignupService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("admin/signup")
public class AdminSignupController {
    private final AdminSignupService adminSupplyService;

    @Autowired
    public AdminSignupController(AdminSignupService adminSignupService) {
        this.adminSupplyService = adminSignupService;
    }

    @PostMapping
    private ResponseEntity<?> addItem(@RequestBody AdminSignupDto adminSignupDto) throws Exception {
        try{
            adminSupplyService.signup(adminSignupDto);
            return new ResponseEntity<>(HttpStatus.OK);
        } catch (Exception e) {
            return new ResponseEntity<>(e.getMessage(), HttpStatus.BAD_REQUEST);
        }
    }
}
