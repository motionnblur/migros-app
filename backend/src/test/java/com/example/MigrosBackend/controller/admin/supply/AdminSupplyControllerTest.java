package com.example.MigrosBackend.controller.admin.supply;

import com.example.MigrosBackend.dto.user.product.ProductPreviewDto;
import com.example.MigrosBackend.service.admin.supply.AdminSupplyService;
import com.example.MigrosBackend.service.global.TokenService;
import com.example.MigrosBackend.service.user.supply.UserSupplyService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AdminSupplyController.class)
@AutoConfigureMockMvc(addFilters = false)
class AdminSupplyControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AdminSupplyService adminSupplyService;

    @MockBean
    private UserSupplyService userSupplyService;

    @MockBean
    private TokenService tokenService;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void addCategory_shouldReturnOk_whenCategoryAdded() throws Exception {
        mockMvc.perform(get("/admin/supply/addCategory")
                        .param("categoryName", "Electronics"))
                .andExpect(status().isOk());

        Mockito.verify(adminSupplyService)
                .addCategory("Electronics");
    }

    @Test
    void getProductCountsFromCategory_shouldReturnCount() throws Exception {
        when(userSupplyService.getProductCountsFromCategory(anyLong()))
                .thenReturn(15);

        mockMvc.perform(get("/admin/supply/getProductCountsFromCategory")
                        .param("categoryId", "1"))
                .andExpect(status().isOk())
                .andExpect(content().string("15"));

        Mockito.verify(userSupplyService)
                .getProductCountsFromCategory(1L);
    }

    @Test
    void getProductsFromCategory_shouldReturnProductList() throws Exception {
        ProductPreviewDto product = new ProductPreviewDto();
        product.setProductName("Laptop");

        List<ProductPreviewDto> products = List.of(product);

        when(userSupplyService.getProductsFromCategory(anyLong(), anyInt(), anyInt()))
                .thenReturn(products);

        mockMvc.perform(get("/admin/supply/getProductsFromCategory")
                        .param("categoryId", "1")
                        .param("page", "0")
                        .param("productRange", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].productName").value("Laptop"));

        Mockito.verify(userSupplyService)
                .getProductsFromCategory(1L, 0, 10);
    }

    @Test
    void addCategory_shouldReturnBadRequest_whenParamMissing() throws Exception {
        mockMvc.perform(get("/admin/supply/addCategory"))
                .andExpect(status().isBadRequest());
    }
}
