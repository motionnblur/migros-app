package com.example.MigrosBackend.controller.admin.panel;

import com.example.MigrosBackend.dto.admin.panel.AdminAddItemDto;
import com.example.MigrosBackend.dto.admin.panel.AdminProductPreviewDto;
import com.example.MigrosBackend.dto.admin.panel.ProductDescriptionListDto;
import com.example.MigrosBackend.dto.admin.panel.ProductDto2;
import com.example.MigrosBackend.dto.order.OrderDto;
import com.example.MigrosBackend.dto.user.UserProfileTableDto;
import com.example.MigrosBackend.service.admin.supply.AdminSupplyService;
import com.example.MigrosBackend.service.global.TokenService;
import com.example.MigrosBackend.service.user.supply.UserOrderService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminPanelController.class)
@AutoConfigureMockMvc(addFilters = false)
class AdminPanelControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AdminSupplyService adminSupplyService;

    @MockBean
    private TokenService tokenService;

    @MockBean
    private UserOrderService userOrderService;

    @Autowired
    private ObjectMapper objectMapper;

    private ProductDescriptionListDto descriptionListDto;
    private AdminAddItemDto addItemDto;
    private MockMultipartFile mockFile;

    @BeforeEach
    void setup() {
        descriptionListDto = new ProductDescriptionListDto();
        // populate descriptionListDto as needed

        addItemDto = new AdminAddItemDto();
        // populate addItemDto as needed

        mockFile = new MockMultipartFile(
                "selectedImage",
                "image.jpg",
                "image/jpeg",
                "dummy content".getBytes()
        );
    }

    @Test
    void addProductDescription_shouldReturnOk() throws Exception {
        mockMvc.perform(post("/admin/panel/addProductDescription")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(descriptionListDto)))
                .andExpect(status().isOk());
    }

    @Test
    void deleteProductDescription_shouldReturnOk() throws Exception {
        mockMvc.perform(delete("/admin/panel/deleteProductDescription")
                        .param("descriptionId", "1"))
                .andExpect(status().isOk());
    }

    @Test
    void getProductDescription_shouldReturnOk() throws Exception {
        when(adminSupplyService.getProductDescription(anyLong()))
                .thenReturn(descriptionListDto);

        mockMvc.perform(get("/admin/panel/getProductDescription")
                        .param("productId", "1"))
                .andExpect(status().isOk())
                .andExpect(content().json(objectMapper.writeValueAsString(descriptionListDto)));
    }

    @Test
    void getAllAdminProducts_shouldReturnOk() throws Exception {
        List<AdminProductPreviewDto> products = List.of(new AdminProductPreviewDto());
        when(adminSupplyService.getAllAdminProducts(anyLong(), anyInt(), anyInt()))
                .thenReturn(products);

        mockMvc.perform(get("/admin/panel/getAllAdminProducts")
                        .param("adminId", "1")
                        .param("page", "0")
                        .param("productRange", "10"))
                .andExpect(status().isOk())
                .andExpect(content().json(objectMapper.writeValueAsString(products)));
    }

    @Test
    void getProductData_shouldReturnOk() throws Exception {
        ProductDto2 productDto2 = new ProductDto2();
        when(adminSupplyService.getProductData(anyLong())).thenReturn(productDto2);

        mockMvc.perform(get("/admin/panel/getProductData")
                        .param("productId", "1"))
                .andExpect(status().isOk())
                .andExpect(content().json(objectMapper.writeValueAsString(productDto2)));
    }

    @Test
    void addProduct_shouldReturnOk() throws Exception {
        mockMvc.perform(post("/admin/panel/addProduct")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(addItemDto)))
                .andExpect(status().isOk());
    }

    @Test
    void uploadProduct_shouldReturnOk() throws Exception {
        mockMvc.perform(multipart("/admin/panel/uploadProduct")
                        .file(mockFile)
                        .param("adminId", "1")
                        .param("productName", "Test Product")
                        .param("subCategoryName", "SubCat")
                        .param("productPrice", "10.0")
                        .param("productCount", "5")
                        .param("productDiscount", "2.0")
                        .param("productDescription", "Desc")
                        .param("categoryValue", "1"))
                .andExpect(status().isOk())
                .andExpect(content().string("File uploaded successfully"));
    }

    @Test
    void updateProduct_shouldReturnOk() throws Exception {
        mockMvc.perform(multipart("/admin/panel/updateProduct")
                        .file(mockFile)
                        .param("adminId", "1")
                        .param("productId", "2")
                        .param("productName", "Updated Product")
                        .param("subCategoryName", "SubCat")
                        .param("productPrice", "20.0")
                        .param("productCount", "10")
                        .param("productDiscount", "3.0")
                        .param("productDescription", "Updated Desc")
                        .param("categoryValue", "1"))
                .andExpect(status().isOk())
                .andExpect(content().string("File uploaded successfully"));
    }

    @Test
    void deleteProduct_shouldReturnOk() throws Exception {
        mockMvc.perform(delete("/admin/panel/deleteProduct")
                        .param("productId", "1"))
                .andExpect(status().isOk());
    }

    @Test
    void getAllOrders_shouldReturnOk() throws Exception {
        List<OrderDto> orders = List.of(new OrderDto());
        when(userOrderService.getAllOrders(anyInt(), anyInt())).thenReturn(orders);

        mockMvc.perform(get("/admin/panel/getAllOrders")
                        .param("page", "0")
                        .param("productRange", "10"))
                .andExpect(status().isOk())
                .andExpect(content().json(objectMapper.writeValueAsString(orders)));
    }

    @Test
    void getUserProfileData_shouldReturnOk() throws Exception {
        UserProfileTableDto profile = new UserProfileTableDto();
        when(userOrderService.getUserProfileData(anyLong())).thenReturn(profile);

        mockMvc.perform(get("/admin/panel/getUserProfileData")
                        .param("orderId", "1"))
                .andExpect(status().isOk())
                .andExpect(content().json(objectMapper.writeValueAsString(profile)));
    }

    @Test
    void updateOrderStatus_shouldReturnOk() throws Exception {
        mockMvc.perform(get("/admin/panel/updateOrderStatus")
                        .param("orderId", "1")
                        .param("status", "SHIPPED"))
                .andExpect(status().isOk());
    }
}