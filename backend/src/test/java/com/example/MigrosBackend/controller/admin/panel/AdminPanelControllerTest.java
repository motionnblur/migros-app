package com.example.MigrosBackend.controller.admin.panel;

import com.example.MigrosBackend.dto.admin.panel.AdminAddItemDto;
import com.example.MigrosBackend.dto.admin.panel.AdminProductPreviewDto;
import com.example.MigrosBackend.dto.admin.panel.ProductDescriptionListDto;
import com.example.MigrosBackend.dto.admin.panel.ProductDto2;
import com.example.MigrosBackend.dto.order.OrderDto;
import com.example.MigrosBackend.dto.order.OrderPageDto;
import com.example.MigrosBackend.dto.user.UserProfileTableDto;
import com.example.MigrosBackend.dto.user.product.ProductDto;
import com.example.MigrosBackend.exception.shared.GeneralException;
import com.example.MigrosBackend.service.admin.supply.AdminSupplyService;
import com.example.MigrosBackend.repository.admin.AdminEntityRepository;
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
import java.math.BigDecimal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AdminPanelController.class)
@AutoConfigureMockMvc(addFilters = false)
class AdminPanelControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AdminSupplyService adminSupplyService;

    @MockBean
    private UserOrderService userOrderService;

    @MockBean
    private AdminEntityRepository adminEntityRepository;

    @MockBean
    private TokenService tokenService;

    @Autowired
    private ObjectMapper objectMapper;

    private ProductDescriptionListDto descriptionListDto;
    private AdminAddItemDto addItemDto;
    private MockMultipartFile mockFile;

    @BeforeEach
    void setup() {
        descriptionListDto = new ProductDescriptionListDto();
        addItemDto = new AdminAddItemDto();

        mockFile = new MockMultipartFile(
                "selectedImage",
                "image.png",
                "image/png",
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
        AdminAddItemDto validDto = new AdminAddItemDto();
        validDto.setAdminId(1L);
        ProductDto productDto = new ProductDto();
        productDto.setProductName("Test Product");
        productDto.setSubCategoryName("SubCat");
        productDto.setProductPrice(new BigDecimal("10.0"));
        productDto.setProductCount(5);
        validDto.setProductDto(productDto);

        mockMvc.perform(post("/admin/panel/addProduct")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validDto)))
                .andExpect(status().isOk());
    }

    @Test
    void addProduct_shouldReturnBadRequest_whenProductMissing() throws Exception {
        mockMvc.perform(post("/admin/panel/addProduct")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(addItemDto)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors").isArray());
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
    void uploadProduct_shouldReturnBadRequest_whenValidationFails() throws Exception {
        doThrow(new GeneralException("Product name is required"))
                .when(adminSupplyService)
                .uploadProduct(anyLong(), any(), any(), any(BigDecimal.class), anyInt(), any(BigDecimal.class), any(), anyInt(), any());

        mockMvc.perform(multipart("/admin/panel/uploadProduct")
                        .file(mockFile)
                        .param("adminId", "1")
                        .param("productName", "undefined")
                        .param("subCategoryName", "SubCat")
                        .param("productPrice", "10.0")
                        .param("productCount", "5")
                        .param("productDiscount", "2.0")
                        .param("productDescription", "Desc")
                        .param("categoryValue", "1"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("Product name is required"));
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
        OrderDto order = new OrderDto();
        order.setOrderId(1L);
        order.setOrderGroupId(1L);
        order.setTotalPrice(new BigDecimal("25.0"));
        order.setStatus("PENDING");

        OrderPageDto pageDto = new OrderPageDto();
        pageDto.setItems(List.of(order));
        pageDto.setTotal(1);

        when(userOrderService.getAllOrders(anyInt(), anyInt())).thenReturn(pageDto);

        mockMvc.perform(get("/admin/panel/getAllOrders")
                        .param("page", "0")
                        .param("productRange", "10"))
                .andExpect(status().isOk())
                .andExpect(content().json(objectMapper.writeValueAsString(pageDto)));
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
        mockMvc.perform(post("/admin/panel/updateOrderStatus")
                        .param("orderId", "1")
                        .param("status", "SHIPPED"))
                .andExpect(status().isOk());
    }

    @Test
    void updateOrderStatus_shouldReturnMethodNotAllowed_whenRequestedWithGet() throws Exception {
        mockMvc.perform(get("/admin/panel/updateOrderStatus")
                        .param("orderId", "1")
                        .param("status", "SHIPPED"))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void deleteOrder_shouldReturnOk() throws Exception {
        mockMvc.perform(delete("/admin/panel/deleteOrder")
                        .param("orderId", "1"))
                .andExpect(status().isOk());
    }

    @Test
    void deleteOrder_shouldReturnBadRequest_whenMissingParam() throws Exception {
        mockMvc.perform(delete("/admin/panel/deleteOrder"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_PARAMETER"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("orderId")));
    }

    @Test
    void uploadProduct_shouldReturnTypedBadRequest_whenPriceNegative() throws Exception {
        mockMvc.perform(multipart("/admin/panel/uploadProduct")
                        .file(mockFile)
                        .param("adminId", "1")
                        .param("productName", "Test Product")
                        .param("subCategoryName", "SubCat")
                        .param("productPrice", "-1")
                        .param("productCount", "5")
                        .param("productDiscount", "2.0")
                        .param("productDescription", "Desc")
                        .param("categoryValue", "1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void uploadProduct_shouldReturnTypedBadRequest_whenCountNegative() throws Exception {
        mockMvc.perform(multipart("/admin/panel/uploadProduct")
                        .file(mockFile)
                        .param("adminId", "1")
                        .param("productName", "Test Product")
                        .param("subCategoryName", "SubCat")
                        .param("productPrice", "10.0")
                        .param("productCount", "-5")
                        .param("productDiscount", "2.0")
                        .param("productDescription", "Desc")
                        .param("categoryValue", "1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
}
