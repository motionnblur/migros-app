package com.example.MigrosBackend.controller.user.supply;

import com.example.MigrosBackend.dto.user.product.ProductPreviewDto;
import com.example.MigrosBackend.service.global.TokenService;
import com.example.MigrosBackend.service.user.supply.UserSupplyService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Arrays;
import java.util.List;

import static org.mockito.Mockito.doNothing;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.mockito.Mockito.when;

@WebMvcTest(UserSupplyController.class)
@AutoConfigureMockMvc(addFilters = false)
class UserSupplyControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private UserSupplyService userSupplyService;

    @MockBean
    private TokenService tokenService;

    @BeforeEach
    void setup() {
        // Any setup if needed
    }

    @Test
    void getAllCategoryNames_shouldReturnList() throws Exception {
        List<String> categories = Arrays.asList("Food", "Beverages");
        when(userSupplyService.getAllCategoryNames()).thenReturn(categories);

        mockMvc.perform(get("/user/supply/getAllCategoryNames"))
                .andExpect(status().isOk())
                .andExpect(content().json(objectMapper.writeValueAsString(categories)));
    }

    @Test
    void getProductsFromCategory_shouldReturnList() throws Exception {
        ProductPreviewDto product = new ProductPreviewDto();
        product.setProductId(1L);
        product.setProductName("Milk");
        List<ProductPreviewDto> products = List.of(product);

        when(userSupplyService.getProductsFromCategory(1L, 0, 10)).thenReturn(products);

        mockMvc.perform(get("/user/supply/getProductsFromCategory")
                        .param("categoryId", "1")
                        .param("page", "0")
                        .param("productRange", "10"))
                .andExpect(status().isOk())
                .andExpect(content().json(objectMapper.writeValueAsString(products)));
    }

    @Test
    void getProductImage_shouldReturnResource() throws Exception {
        Resource resource = new ByteArrayResource("image-content".getBytes()) {
            @Override
            public String getFilename() {
                return "milk.png";
            }
        };
        when(userSupplyService.getProductImage(1L)).thenReturn(resource);

        mockMvc.perform(get("/user/supply/getProductImage")
                        .param("productId", "1"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"milk.png\""))
                .andExpect(content().bytes("image-content".getBytes()));
    }

    @Test
    void addProductToUserCart_shouldReturnOk() throws Exception {
        doNothing().when(userSupplyService).addProductToInventory(1L, "token");

        mockMvc.perform(get("/user/supply/addProductToUserCart")
                        .param("productId", "1")
                        .param("token", "token"))
                .andExpect(status().isOk());
    }

    @Test
    void removeProductFromUserCart_shouldReturnOk() throws Exception {
        doNothing().when(userSupplyService).removeProductFromInventory(1L, "token");

        mockMvc.perform(delete("/user/supply/removeProductFromUserCart")
                        .param("productId", "1")
                        .param("token", "token"))
                .andExpect(status().isOk());
    }

    @Test
    void getAllOrderIds_shouldReturnList() throws Exception {
        List<Long> orderIds = List.of(100L, 101L);
        when(userSupplyService.getAllOrderIds("token")).thenReturn(orderIds);

        mockMvc.perform(get("/user/supply/getAllOrderIds")
                        .param("token", "token"))
                .andExpect(status().isOk())
                .andExpect(content().json(objectMapper.writeValueAsString(orderIds)));
    }

    @Test
    void getOrderStatusByOrderId_shouldReturnStatus() throws Exception {
        when(userSupplyService.getOrderStatusByOrderId(100L, "token")).thenReturn("DELIVERED");

        mockMvc.perform(get("/user/supply/getOrderStatusByOrderId")
                        .param("orderId", "100")
                        .param("token", "token"))
                .andExpect(status().isOk())
                .andExpect(content().string("DELIVERED"));
    }
}