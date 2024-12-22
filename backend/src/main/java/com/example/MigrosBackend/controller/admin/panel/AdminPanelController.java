package com.example.MigrosBackend.controller.admin.panel;

import com.example.MigrosBackend.dto.admin.panel.AdminAddItemDto;
import com.example.MigrosBackend.service.admin.AdminSupplyService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;

@RestController
@RequestMapping("admin/panel")
public class AdminPanelController {
    private final AdminSupplyService adminSupplyService;

    @Autowired
    public AdminPanelController(AdminSupplyService adminSupplyService) {
        this.adminSupplyService = adminSupplyService;
    }

    @PostMapping("addItem")
    private ResponseEntity<?> addItem(@RequestBody AdminAddItemDto adminAddItemDto) throws Exception {
        try{
            adminSupplyService.addItem(adminAddItemDto);
            return new ResponseEntity<>(HttpStatus.OK);
        } catch (Exception e) {
            return new ResponseEntity<>(e.getMessage(), HttpStatus.BAD_REQUEST);
        }
    }
    @PostMapping("uploadImage")
    private ResponseEntity<String> uploadImage(@RequestParam("file") MultipartFile file) {
        // Save the file to your desired location
        try {
            if (!Objects.equals(file.getContentType(), "image/png")) {
                return new ResponseEntity<>("Only PNG files are allowed", HttpStatus.BAD_REQUEST);
            }
            byte[] bytes = file.getBytes();
            String fileName = "image_" + System.currentTimeMillis() + ".png";

            Path directory = Paths.get("UploadFolder");
//            if (!Files.exists(directory)) {
//                Files.createDirectories(directory);  // Creates the directory if it doesn't exist
//            }
            Path filePath = directory.resolve(fileName);
            Files.write(filePath, bytes);

            return ResponseEntity.ok("File uploaded successfully");
        } catch (IOException e) {
            return new ResponseEntity<>(e.getMessage(), HttpStatus.BAD_REQUEST);
        }
    }
}
