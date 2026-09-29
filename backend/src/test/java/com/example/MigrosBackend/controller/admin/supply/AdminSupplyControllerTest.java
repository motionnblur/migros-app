package com.example.MigrosBackend.controller.admin.supply;

import com.example.MigrosBackend.dto.user.product.ProductPreviewDto;
import com.example.MigrosBackend.service.admin.supply.AdminSupplyService;
import com.example.MigrosBackend.repository.admin.AdminEntityRepository;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
    private AdminEntityRepository adminEntityRepository;

    @MockBean
    private TokenService tokenService;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void addCategory_shouldReturnOk_whenCategoryAdded() throws Exception {
        mockMvc.perform(post("/admin/supply/addCategory")
                        .param("categoryName", "Electronics"))
                .andExpect(status().isOk());

        Mockito.verify(adminSupplyService)
                .addCategory("Electronics");
    }

    @Test
    void addCategory_shouldReturnMethodNotAllowed_whenRequestedWithGet() throws Exception {
        mockMvc.perform(get("/admin/supply/addCategory")
                        .param("categoryName", "Electronics"))
                .andExpect(status().isMethodNotAllowed());
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
    void getAllProductCounts_shouldReturnCount() throws Exception {
        when(userSupplyService.getAllProductCounts()).thenReturn(15);

        mockMvc.perform(get("/admin/supply/getAllProductCounts"))
                .andExpect(status().isOk())
                .andExpect(content().string("15"));

        Mockito.verify(userSupplyService).getAllProductCounts();
    }

    @Test
    void getAllProducts_shouldReturnProductList() throws Exception {
        ProductPreviewDto product = new ProductPreviewDto();
        product.setProductName("Laptop");

        List<ProductPreviewDto> products = List.of(product);

        when(userSupplyService.getAllProducts(anyInt(), anyInt())).thenReturn(products);

        mockMvc.perform(get("/admin/supply/getAllProducts")
                        .param("page", "0")
                        .param("productRange", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].productName").value("Laptop"));

        Mockito.verify(userSupplyService).getAllProducts(0, 10);
    }

    @Test
    void addCategory_shouldReturnBadRequest_whenParamMissing() throws Exception {
        mockMvc.perform(post("/admin/supply/addCategory"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getProductsFromCategory_shouldRejectPagesAndRangesOutsideTheBound() throws Exception {
        for (String page : List.of("-1", "-100")) {
            mockMvc.perform(get("/admin/supply/getProductsFromCategory")
                            .param("categoryId", "1")
                            .param("page", page)
                            .param("productRange", "10"))
                    .andExpect(status().isBadRequest());
        }

        for (String range : List.of("-1", "0", "101", "2147483647", "999999999999", "not-a-number")) {
            mockMvc.perform(get("/admin/supply/getProductsFromCategory")
                            .param("categoryId", "1")
                            .param("page", "0")
                            .param("productRange", range))
                    .andExpect(status().isBadRequest());
        }

        verify(userSupplyService, never()).getProductsFromCategory(anyLong(), anyInt(), anyInt());
    }

    @Test
    void getAllProducts_shouldRejectPagesAndRangesOutsideTheBound() throws Exception {
        for (String page : List.of("-1", "-100")) {
            mockMvc.perform(get("/admin/supply/getAllProducts")
                            .param("page", page)
                            .param("productRange", "10"))
                    .andExpect(status().isBadRequest());
        }

        for (String range : List.of("-1", "0", "101", "2147483647", "999999999999", "not-a-number")) {
            mockMvc.perform(get("/admin/supply/getAllProducts")
                            .param("page", "0")
                            .param("productRange", range))
                    .andExpect(status().isBadRequest());
        }

        verify(userSupplyService, never()).getAllProducts(anyInt(), anyInt());
    }

    @Test
    void paginatedReads_shouldAcceptPageZeroAndBothSizeBounds() throws Exception {
        when(userSupplyService.getProductsFromCategory(anyLong(), anyInt(), anyInt())).thenReturn(List.of());
        when(userSupplyService.getAllProducts(anyInt(), anyInt())).thenReturn(List.of());

        for (String range : List.of("1", "100")) {
            mockMvc.perform(get("/admin/supply/getProductsFromCategory")
                            .param("categoryId", "1")
                            .param("page", "0")
                            .param("productRange", range))
                    .andExpect(status().isOk())
                    .andExpect(content().json("[]"));

            mockMvc.perform(get("/admin/supply/getAllProducts")
                            .param("page", "0")
                            .param("productRange", range))
                    .andExpect(status().isOk())
                    .andExpect(content().json("[]"));
        }
    }
}
