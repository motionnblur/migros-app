package com.example.MigrosBackend.controller.admin.sign;

import com.example.MigrosBackend.dto.admin.sign.AdminSignDto;
import com.example.MigrosBackend.service.admin.sign.AdminSignupService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("admin")
public class AdminSignController {
    private final AdminSignupService adminSupplyService;

    @Autowired
    public AdminSignController(AdminSignupService adminSignupService) {
        this.adminSupplyService = adminSignupService;
    }
    
    @PostMapping("login")
    private ResponseEntity<?> login(@RequestBody AdminSignDto adminSignDto, HttpServletRequest request) throws Exception {
        try{
            adminSupplyService.login(adminSignDto, request);
            return new ResponseEntity<>(HttpStatus.OK);
        } catch (Exception e) {
            return new ResponseEntity<>(e.getMessage(), HttpStatus.BAD_REQUEST);
        }
    }
}
