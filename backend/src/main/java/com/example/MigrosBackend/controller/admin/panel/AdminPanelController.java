package com.example.MigrosBackend.controller.admin.panel;

import com.example.MigrosBackend.dto.ItemPreviewDto;
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
import java.util.List;
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
    @GetMapping("getAllAdminProducts")
    private ResponseEntity<?> getAllAdminProducts(@RequestParam Long adminId, @RequestParam int page, @RequestParam int itemRange) throws Exception {
        // Process the product data here
        try{
            List<ItemPreviewDto> allAdminProducts = adminSupplyService.getAllAdminProducts(adminId, page, itemRange);
            return ResponseEntity.ok(allAdminProducts);
        } catch (Exception e) {
            return new ResponseEntity<>(e.getMessage(), HttpStatus.BAD_REQUEST);
        }
    }
    @PostMapping("uploadProduct")
    private ResponseEntity<String> uploadImage(@RequestParam("adminId") Long adminId,
                                               @RequestParam("productName") String productName,
                                               @RequestParam("price") float price,
                                               @RequestParam("count") int count,
                                               @RequestParam("discount") float discount,
                                               @RequestParam("description") String description,
                                               @RequestParam("selectedImage") MultipartFile selectedImage,
                                               @RequestParam("categoryValue") int categoryValue) {
        // Save the file to your desired location
        try {
            if (!Objects.equals(selectedImage.getContentType(), "image/png")) {
                return new ResponseEntity<>("Only PNG files are allowed", HttpStatus.BAD_REQUEST);
            }
            byte[] bytes = selectedImage.getBytes();
            String fileName = "image_" + System.currentTimeMillis() + ".png";

            Path directory = Paths.get("UploadFolder");
            Path filePath = directory.resolve(fileName);
            Files.write(filePath, bytes);

            // Process the product data here
            System.out.println("Product data: " + productName + ", " + price + ", " + count + ", " + discount + ", " + description + "," + categoryValue);

            adminSupplyService.uploadProduct(adminId, productName, price, count, discount, description, categoryValue, filePath.toString());

            return ResponseEntity.ok("File uploaded successfully");
        } catch (IOException e) {
            return new ResponseEntity<>(e.getMessage(), HttpStatus.BAD_REQUEST);
        }
    }
}
