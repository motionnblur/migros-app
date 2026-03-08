package com.example.MigrosBackend.controller.user.supply;

import com.example.MigrosBackend.config.security.AuthCookies;
import com.example.MigrosBackend.dto.admin.panel.DescriptionsDto;
import com.example.MigrosBackend.dto.admin.panel.ProductDescriptionListDto;
import com.example.MigrosBackend.dto.admin.panel.ProductDto2;
import com.example.MigrosBackend.dto.user.category.SubCategoryDto;
import com.example.MigrosBackend.dto.user.order.UserOrderDetailDto;
import com.example.MigrosBackend.dto.user.order.UserOrderGroupDto;
import com.example.MigrosBackend.dto.user.product.ProductPreviewDto;
import com.example.MigrosBackend.dto.user.product.UserCartItemDto;
import com.example.MigrosBackend.exception.shared.TokenNotFoundException;
import com.example.MigrosBackend.helper.AuthTokenResolver;
import com.example.MigrosBackend.service.global.TokenService;
import com.example.MigrosBackend.service.user.supply.UserSupplyService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Arrays;
import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(UserSupplyController.class)
@AutoConfigureMockMvc(addFilters = false)
class UserSupplyControllerTest {
    private static final String SESSION_TOKEN = "session-token";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private UserSupplyService userSupplyService;

    @MockBean
    private AuthTokenResolver authTokenResolver;

    @MockBean
    private TokenService tokenService;

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
        when(authTokenResolver.requireToken(SESSION_TOKEN)).thenReturn(SESSION_TOKEN);
        doNothing().when(userSupplyService).addProductToInventory(1L, SESSION_TOKEN);

        mockMvc.perform(get("/user/supply/addProductToUserCart")
                        .param("productId", "1")
                        .cookie(new Cookie(AuthCookies.USER_SESSION_COOKIE_NAME, SESSION_TOKEN)))
                .andExpect(status().isOk());

        verify(userSupplyService).addProductToInventory(1L, SESSION_TOKEN);
    }

    @Test
    void addProductToUserCart_shouldReturnNotFound_whenCookieMissing() throws Exception {
        when(authTokenResolver.requireToken(null)).thenThrow(new TokenNotFoundException());

        mockMvc.perform(get("/user/supply/addProductToUserCart")
                        .param("productId", "1"))
                .andExpect(status().isNotFound());
    }

    @Test
    void removeProductFromUserCart_shouldReturnOk() throws Exception {
        when(authTokenResolver.requireToken(SESSION_TOKEN)).thenReturn(SESSION_TOKEN);
        doNothing().when(userSupplyService).removeProductFromInventory(1L, SESSION_TOKEN);

        mockMvc.perform(delete("/user/supply/removeProductFromUserCart")
                        .param("productId", "1")
                        .cookie(new Cookie(AuthCookies.USER_SESSION_COOKIE_NAME, SESSION_TOKEN)))
                .andExpect(status().isOk());

        verify(userSupplyService).removeProductFromInventory(1L, SESSION_TOKEN);
    }

    @Test
    void getAllOrderIds_shouldReturnList() throws Exception {
        when(authTokenResolver.requireToken(SESSION_TOKEN)).thenReturn(SESSION_TOKEN);
        List<Long> orderIds = List.of(100L, 101L);
        when(userSupplyService.getAllOrderIds(SESSION_TOKEN)).thenReturn(orderIds);

        mockMvc.perform(get("/user/supply/getAllOrderIds")
                        .cookie(new Cookie(AuthCookies.USER_SESSION_COOKIE_NAME, SESSION_TOKEN)))
                .andExpect(status().isOk())
                .andExpect(content().json(objectMapper.writeValueAsString(orderIds)));
    }

    @Test
    void getOrderStatusByOrderId_shouldReturnStatus() throws Exception {
        when(authTokenResolver.requireToken(SESSION_TOKEN)).thenReturn(SESSION_TOKEN);
        when(userSupplyService.getOrderStatusByOrderId(100L, SESSION_TOKEN)).thenReturn("DELIVERED");

        mockMvc.perform(get("/user/supply/getOrderStatusByOrderId")
                        .param("orderId", "100")
                        .cookie(new Cookie(AuthCookies.USER_SESSION_COOKIE_NAME, SESSION_TOKEN)))
                .andExpect(status().isOk())
                .andExpect(content().string("DELIVERED"));
    }

    @Test
    void getSubProductsFromCategory_ShouldReturnProductList() throws Exception {
        String subName = "Fruits";
        int page = 0;
        int range = 10;

        ProductPreviewDto dto = new ProductPreviewDto();
        dto.setProductId(1L);
        dto.setProductName("Apple");
        dto.setProductPrice(2.5f);

        when(userSupplyService.getProductsFromSubcategory(subName, page, range))
                .thenReturn(List.of(dto));

        mockMvc.perform(get("/user/supply/getProductsFromSubcategory")
                        .param("subcategoryName", subName)
                        .param("page", String.valueOf(page))
                        .param("productRange", String.valueOf(range)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].productName").value("Apple"));
    }

    @Test
    void getProductCountsFromSubcategory_ShouldReturnCount() throws Exception {
        String subName = "Beverages";
        int expectedCount = 42;

        when(userSupplyService.getProductCountsFromSubcategory(subName))
                .thenReturn(expectedCount);

        mockMvc.perform(get("/user/supply/getProductCountsFromSubcategory")
                        .param("subcategoryName", subName)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value(expectedCount));

        verify(userSupplyService, times(1)).getProductCountsFromSubcategory(subName);
    }

    @Test
    void getProductCountsFromCategory_ShouldReturnTotalCount() throws Exception {
        Long categoryId = 5L;
        int expectedCount = 120;

        when(userSupplyService.getProductCountsFromCategory(categoryId))
                .thenReturn(expectedCount);

        mockMvc.perform(get("/user/supply/getProductCountsFromCategory")
                        .param("categoryId", categoryId.toString())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value(expectedCount));

        verify(userSupplyService).getProductCountsFromCategory(categoryId);
    }

    @Test
    void getProductImageNames_ShouldReturnListOfStrings() throws Exception {
        Long productId = 101L;
        List<String> imageNames = List.of("image1.jpg", "image2.png", "thumbnail.webp");

        when(userSupplyService.getProductImageNames(productId))
                .thenReturn(imageNames);

        mockMvc.perform(get("/user/supply/getProductImageNames")
                        .param("productId", productId.toString())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size()").value(3))
                .andExpect(jsonPath("$[0]").value("image1.jpg"))
                .andExpect(jsonPath("$[1]").value("image2.png"))
                .andExpect(jsonPath("$[2]").value("thumbnail.webp"));

        verify(userSupplyService).getProductImageNames(productId);
    }

    @Test
    void getSubCategories_ShouldReturnDtoList() throws Exception {
        Long categoryId = 1L;

        SubCategoryDto dto1 = new SubCategoryDto();
        dto1.setSubCategoryId(10L);
        dto1.setSubCategoryName("Dairy");

        SubCategoryDto dto2 = new SubCategoryDto();
        dto2.setSubCategoryId(11L);
        dto2.setSubCategoryName("Eggs");

        List<SubCategoryDto> mockList = List.of(dto1, dto2);

        when(userSupplyService.getSubCategories(categoryId)).thenReturn(mockList);

        mockMvc.perform(get("/user/supply/getSubCategories")
                        .param("categoryId", categoryId.toString())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size()").value(2))
                .andExpect(jsonPath("$[0].subCategoryId").value(10))
                .andExpect(jsonPath("$[0].subCategoryName").value("Dairy"))
                .andExpect(jsonPath("$[1].subCategoryId").value(11))
                .andExpect(jsonPath("$[1].subCategoryName").value("Eggs"));

        verify(userSupplyService, times(1)).getSubCategories(categoryId);
    }

    @Test
    void getProductData_ShouldReturnCartItems() throws Exception {
        UserCartItemDto item1 = new UserCartItemDto();
        item1.setProductId(101L);
        item1.setProductName("Milk");
        item1.setProductPrice(1.5f);
        item1.setProductCount(2);

        UserCartItemDto item2 = new UserCartItemDto();
        item2.setProductId(102L);
        item2.setProductName("Bread");
        item2.setProductPrice(2.0f);
        item2.setProductCount(1);

        List<UserCartItemDto> cartItems = List.of(item1, item2);

        when(authTokenResolver.requireToken(SESSION_TOKEN)).thenReturn(SESSION_TOKEN);
        when(userSupplyService.getProductData(SESSION_TOKEN)).thenReturn(cartItems);

        mockMvc.perform(get("/user/supply/getProductData")
                        .cookie(new Cookie(AuthCookies.USER_SESSION_COOKIE_NAME, SESSION_TOKEN))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size()").value(2))
                .andExpect(jsonPath("$[0].productId").value(101))
                .andExpect(jsonPath("$[0].productName").value("Milk"))
                .andExpect(jsonPath("$[0].productPrice").value(1.5))
                .andExpect(jsonPath("$[0].productCount").value(2))
                .andExpect(jsonPath("$[1].productId").value(102))
                .andExpect(jsonPath("$[1].productName").value("Bread"));

        verify(userSupplyService, times(1)).getProductData(SESSION_TOKEN);
    }

    @Test
    void getProductDataWithProductId_ShouldReturnProduct() throws Exception {
        Long productId = 50L;

        ProductDto2 mockProduct = new ProductDto2();
        mockProduct.setProductName("Organic Honey");
        mockProduct.setSubCategoryName("Sweeteners");
        mockProduct.setProductPrice(15.50f);
        mockProduct.setProductCount(100);
        mockProduct.setProductDiscount(10.0f);
        mockProduct.setProductDescription("Pure natural honey.");
        mockProduct.setProductCategoryId(5);

        when(userSupplyService.getProductData(productId)).thenReturn(mockProduct);

        mockMvc.perform(get("/user/supply/getProductDataWithProductId")
                        .param("productId", productId.toString())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productName").value("Organic Honey"))
                .andExpect(jsonPath("$.subCategoryName").value("Sweeteners"))
                .andExpect(jsonPath("$.productPrice").value(15.50))
                .andExpect(jsonPath("$.productCount").value(100))
                .andExpect(jsonPath("$.productCategoryId").value(5));

        verify(userSupplyService).getProductData(productId);
    }

    @Test
    void getProductDescription_ShouldReturnNestedDescriptions() throws Exception {
        Long productId = 50L;

        DescriptionsDto desc1 = new DescriptionsDto(101L, "Ingredients", "Sugar, Spice, Everything Nice");

        ProductDescriptionListDto resultDto = new ProductDescriptionListDto();
        resultDto.setProductId(productId);
        resultDto.setDescriptionList(List.of(desc1));

        when(userSupplyService.getProductDescription(productId)).thenReturn(resultDto);

        mockMvc.perform(get("/user/supply/getProductDescription")
                        .param("productId", productId.toString())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productId").value(50))
                .andExpect(jsonPath("$.descriptionList[0].descriptionId").value(101))
                .andExpect(jsonPath("$.descriptionList[0].descriptionTabName").value("Ingredients"))
                .andExpect(jsonPath("$.descriptionList[0].descriptionTabContent").value("Sugar, Spice, Everything Nice"));

        verify(userSupplyService).getProductDescription(productId);
    }

    @Test
    void updateProductCountInUserCart_ShouldReturnOk() throws Exception {
        Long productId = 101L;
        int count = 5;

        when(authTokenResolver.requireToken(SESSION_TOKEN)).thenReturn(SESSION_TOKEN);
        doNothing().when(userSupplyService).updateProductCountInInventory(productId, count, SESSION_TOKEN);

        mockMvc.perform(get("/user/supply/updateProductCountInUserCart")
                        .param("productId", productId.toString())
                        .param("count", String.valueOf(count))
                        .cookie(new Cookie(AuthCookies.USER_SESSION_COOKIE_NAME, SESSION_TOKEN))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        verify(userSupplyService, times(1)).updateProductCountInInventory(productId, count, SESSION_TOKEN);
    }

    @Test
    void cancelOrder_ShouldReturnOk() throws Exception {
        Long orderId = 99L;

        when(authTokenResolver.requireToken(SESSION_TOKEN)).thenReturn(SESSION_TOKEN);
        doNothing().when(userSupplyService).cancelOrder(orderId, SESSION_TOKEN);

        mockMvc.perform(delete("/user/supply/cancelOrder")
                        .param("orderId", orderId.toString())
                        .cookie(new Cookie(AuthCookies.USER_SESSION_COOKIE_NAME, SESSION_TOKEN))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        verify(userSupplyService, times(1)).cancelOrder(orderId, SESSION_TOKEN);
    }

    @Test
    void getUserOrders_shouldReturnList() throws Exception {
        when(authTokenResolver.requireToken(SESSION_TOKEN)).thenReturn(SESSION_TOKEN);

        UserOrderDetailDto detail = new UserOrderDetailDto();
        detail.setOrderId(1L);
        detail.setProductName("Milk");
        List<UserOrderDetailDto> details = List.of(detail);

        when(userSupplyService.getUserOrderDetails(SESSION_TOKEN)).thenReturn(details);

        mockMvc.perform(get("/user/supply/getUserOrders")
                        .cookie(new Cookie(AuthCookies.USER_SESSION_COOKIE_NAME, SESSION_TOKEN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].orderId").value(1));
    }

    @Test
    void getUserOrders_shouldReturnNotFound_whenCookieMissing() throws Exception {
        when(authTokenResolver.requireToken(null)).thenThrow(new TokenNotFoundException());

        mockMvc.perform(get("/user/supply/getUserOrders"))
                .andExpect(status().isNotFound());
    }

    @Test
    void getUserOrderGroups_shouldReturnList() throws Exception {
        when(authTokenResolver.requireToken(SESSION_TOKEN)).thenReturn(SESSION_TOKEN);

        UserOrderGroupDto group = new UserOrderGroupDto();
        group.setOrderGroupId(10L);
        List<UserOrderGroupDto> groups = List.of(group);

        when(userSupplyService.getUserOrderGroups(SESSION_TOKEN)).thenReturn(groups);

        mockMvc.perform(get("/user/supply/getUserOrderGroups")
                        .cookie(new Cookie(AuthCookies.USER_SESSION_COOKIE_NAME, SESSION_TOKEN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].orderGroupId").value(10));
    }
}


