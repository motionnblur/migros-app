package com.example.MigrosBackend.controller.user.supply;

import com.example.MigrosBackend.config.security.AuthCookies;
import com.example.MigrosBackend.dto.admin.panel.ProductDescriptionTabDto;
import com.example.MigrosBackend.dto.admin.panel.ProductDescriptionListDto;
import com.example.MigrosBackend.dto.user.category.SubCategoryDto;
import com.example.MigrosBackend.dto.user.order.UserOrderDetailDto;
import com.example.MigrosBackend.dto.user.order.UserOrderGroupDto;
import com.example.MigrosBackend.dto.user.product.ProductDetailDto;
import com.example.MigrosBackend.dto.user.product.ProductPreviewDto;
import com.example.MigrosBackend.dto.user.product.UserCartItemDto;
import com.example.MigrosBackend.exception.shared.FileNotFoundException;
import com.example.MigrosBackend.exception.shared.TokenNotFoundException;
import com.example.MigrosBackend.helper.AuthTokenResolver;
import com.example.MigrosBackend.repository.admin.AdminEntityRepository;
import com.example.MigrosBackend.service.global.TokenService;
import com.example.MigrosBackend.service.user.supply.UserCartService;
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

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(UserSupplyController.class)
@AutoConfigureMockMvc(addFilters = false)
class UserSupplyControllerTest {
    private static final String SESSION_TOKEN = "session-token";
    private static final String USER_MAIL = "user@example.com";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private UserSupplyService userSupplyService;

    @MockBean
    private UserCartService userCartService;

    @MockBean
    private AuthTokenResolver authTokenResolver;

    @MockBean
    private AdminEntityRepository adminEntityRepository;

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
    void getProductImage_shouldReturnNotFound_whenImageIsMissing() throws Exception {
        when(userSupplyService.getProductImage(999L)).thenThrow(new FileNotFoundException());

        mockMvc.perform(get("/user/supply/getProductImage")
                        .param("productId", "999"))
                .andExpect(status().isNotFound());
    }

    @Test
    void addProductToUserCart_shouldReturnOk() throws Exception {
        when(authTokenResolver.requireAuthenticatedUserMail()).thenReturn(USER_MAIL);
        doNothing().when(userCartService).addProductToCart(1L, USER_MAIL);

        mockMvc.perform(post("/user/supply/addProductToUserCart")
                        .param("productId", "1")
                        .cookie(new Cookie(AuthCookies.USER_SESSION_COOKIE_NAME, SESSION_TOKEN)))
                .andExpect(status().isOk());

        verify(userCartService).addProductToCart(1L, USER_MAIL);
    }

    @Test
    void addProductToUserCart_shouldReturnMethodNotAllowed_whenRequestedWithGet() throws Exception {
        mockMvc.perform(get("/user/supply/addProductToUserCart")
                        .param("productId", "1")
                        .cookie(new Cookie(AuthCookies.USER_SESSION_COOKIE_NAME, SESSION_TOKEN)))
                .andExpect(status().isMethodNotAllowed());

        verify(userCartService, never()).addProductToCart(anyLong(), anyString());
    }

    @Test
    void addProductToUserCart_shouldReturnNotFound_whenNoAuthenticatedUser() throws Exception {
        when(authTokenResolver.requireAuthenticatedUserMail()).thenThrow(new TokenNotFoundException());

        mockMvc.perform(post("/user/supply/addProductToUserCart")
                        .param("productId", "1"))
                .andExpect(status().isNotFound());
    }

    @Test
    void removeProductFromUserCart_shouldReturnOk() throws Exception {
        when(authTokenResolver.requireAuthenticatedUserMail()).thenReturn(USER_MAIL);
        doNothing().when(userCartService).removeProductFromCart(1L, USER_MAIL);

        mockMvc.perform(delete("/user/supply/removeProductFromUserCart")
                        .param("productId", "1")
                        .cookie(new Cookie(AuthCookies.USER_SESSION_COOKIE_NAME, SESSION_TOKEN)))
                .andExpect(status().isOk());

        verify(userCartService).removeProductFromCart(1L, USER_MAIL);
    }

    @Test
    void getAllOrderIds_shouldReturnList() throws Exception {
        when(authTokenResolver.requireAuthenticatedUserMail()).thenReturn(USER_MAIL);
        List<Long> orderIds = List.of(100L, 101L);
        when(userSupplyService.getAllOrderIds(USER_MAIL)).thenReturn(orderIds);

        mockMvc.perform(get("/user/supply/getAllOrderIds")
                        .cookie(new Cookie(AuthCookies.USER_SESSION_COOKIE_NAME, SESSION_TOKEN)))
                .andExpect(status().isOk())
                .andExpect(content().json(objectMapper.writeValueAsString(orderIds)));
    }

    @Test
    void getOrderStatusByOrderId_shouldReturnStatus() throws Exception {
        when(authTokenResolver.requireAuthenticatedUserMail()).thenReturn(USER_MAIL);
        when(userSupplyService.getOrderStatusByOrderId(100L, USER_MAIL)).thenReturn("DELIVERED");

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
        dto.setProductPrice(new BigDecimal("2.5"));

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
        item1.setProductPrice(new BigDecimal("1.5"));
        item1.setProductCount(2);

        UserCartItemDto item2 = new UserCartItemDto();
        item2.setProductId(102L);
        item2.setProductName("Bread");
        item2.setProductPrice(new BigDecimal("2.0"));
        item2.setProductCount(1);

        List<UserCartItemDto> cartItems = List.of(item1, item2);

        when(authTokenResolver.requireAuthenticatedUserMail()).thenReturn(USER_MAIL);
        when(userCartService.getCartData(USER_MAIL)).thenReturn(cartItems);

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

        verify(userCartService, times(1)).getCartData(USER_MAIL);
    }

    @Test
    void getProductDataWithProductId_ShouldReturnProduct() throws Exception {
        Long productId = 50L;

        ProductDetailDto mockProduct = new ProductDetailDto();
        mockProduct.setProductName("Organic Honey");
        mockProduct.setSubCategoryName("Sweeteners");
        mockProduct.setProductPrice(new BigDecimal("15.50"));
        mockProduct.setProductCount(100);
        mockProduct.setProductDiscount(new BigDecimal("10.0"));
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

        ProductDescriptionTabDto desc1 = new ProductDescriptionTabDto(101L, "Ingredients", "Sugar, Spice, Everything Nice");

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

        when(authTokenResolver.requireAuthenticatedUserMail()).thenReturn(USER_MAIL);
        doNothing().when(userCartService).updateProductCountInCart(productId, count, USER_MAIL);

        mockMvc.perform(post("/user/supply/updateProductCountInUserCart")
                        .param("productId", productId.toString())
                        .param("count", String.valueOf(count))
                        .cookie(new Cookie(AuthCookies.USER_SESSION_COOKIE_NAME, SESSION_TOKEN))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        verify(userCartService, times(1)).updateProductCountInCart(productId, count, USER_MAIL);
    }

    @Test
    void updateProductCountInUserCart_ShouldReturnMethodNotAllowed_whenRequestedWithGet() throws Exception {
        mockMvc.perform(get("/user/supply/updateProductCountInUserCart")
                        .param("productId", "101")
                        .param("count", "5")
                        .cookie(new Cookie(AuthCookies.USER_SESSION_COOKIE_NAME, SESSION_TOKEN)))
                .andExpect(status().isMethodNotAllowed());

        verify(userCartService, never()).updateProductCountInCart(anyLong(), anyInt(), anyString());
    }

    @Test
    void cancelOrder_ShouldReturnOk() throws Exception {
        Long orderId = 99L;

        when(authTokenResolver.requireAuthenticatedUserMail()).thenReturn(USER_MAIL);
        doNothing().when(userSupplyService).cancelOrder(orderId, USER_MAIL);

        mockMvc.perform(delete("/user/supply/cancelOrder")
                        .param("orderId", orderId.toString())
                        .cookie(new Cookie(AuthCookies.USER_SESSION_COOKIE_NAME, SESSION_TOKEN))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        verify(userSupplyService, times(1)).cancelOrder(orderId, USER_MAIL);
    }

    @Test
    void getUserOrders_shouldReturnList() throws Exception {
        when(authTokenResolver.requireAuthenticatedUserMail()).thenReturn(USER_MAIL);

        UserOrderDetailDto detail = new UserOrderDetailDto();
        detail.setOrderId(1L);
        detail.setProductName("Milk");
        List<UserOrderDetailDto> details = List.of(detail);

        when(userSupplyService.getUserOrderDetails(USER_MAIL)).thenReturn(details);

        mockMvc.perform(get("/user/supply/getUserOrders")
                        .cookie(new Cookie(AuthCookies.USER_SESSION_COOKIE_NAME, SESSION_TOKEN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].orderId").value(1));
    }

    @Test
    void getUserOrders_shouldReturnNotFound_whenNoAuthenticatedUser() throws Exception {
        when(authTokenResolver.requireAuthenticatedUserMail()).thenThrow(new TokenNotFoundException());

        mockMvc.perform(get("/user/supply/getUserOrders"))
                .andExpect(status().isNotFound());
    }

    @Test
    void getUserOrderGroups_shouldReturnList() throws Exception {
        when(authTokenResolver.requireAuthenticatedUserMail()).thenReturn(USER_MAIL);

        UserOrderGroupDto group = new UserOrderGroupDto();
        group.setOrderGroupId(10L);
        List<UserOrderGroupDto> groups = List.of(group);

        when(userSupplyService.getUserOrderGroups(USER_MAIL)).thenReturn(groups);

        mockMvc.perform(get("/user/supply/getUserOrderGroups")
                        .cookie(new Cookie(AuthCookies.USER_SESSION_COOKIE_NAME, SESSION_TOKEN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].orderGroupId").value(10));
    }

    @Test
    void getProductDataWithProductId_jsonShapeIsFrozen() throws Exception {
        Long productId = 50L;

        ProductDetailDto product = new ProductDetailDto();
        product.setProductName("Organic Honey");
        product.setSubCategoryName("Sweeteners");
        product.setProductPrice(new BigDecimal("15.50"));
        product.setProductCount(100);
        product.setProductDiscount(new BigDecimal("10.0"));
        product.setProductDescription("Pure natural honey.");
        product.setProductCategoryId(5);
        product.setProductVersion(11L);

        when(userSupplyService.getProductData(productId)).thenReturn(product);

        // productVersion is additive for catalog readers; existing fields and
        // their meaning are unchanged.
        String expectedJson = "{\"productName\":\"Organic Honey\",\"subCategoryName\":\"Sweeteners\","
                + "\"productPrice\":15.50,\"productCount\":100,\"productDiscount\":10.0,"
                + "\"productDescription\":\"Pure natural honey.\",\"productCategoryId\":5,"
                + "\"productVersion\":11}";

        mockMvc.perform(get("/user/supply/getProductDataWithProductId")
                        .param("productId", productId.toString())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().json(expectedJson, true));
    }

    @Test
    void getProductDescription_jsonShapeIsFrozen() throws Exception {
        Long productId = 50L;

        ProductDescriptionTabDto desc1 = new ProductDescriptionTabDto(101L, "Ingredients", "Sugar, Spice, Everything Nice");
        ProductDescriptionTabDto desc2 = new ProductDescriptionTabDto(102L, "Storage", "<p>Keep dry</p>");

        ProductDescriptionListDto resultDto = new ProductDescriptionListDto();
        resultDto.setProductId(productId);
        resultDto.setDescriptionList(List.of(desc1, desc2));

        when(userSupplyService.getProductDescription(productId)).thenReturn(resultDto);

        String expectedJson = "{\"productId\":50,\"descriptionList\":["
                + "{\"descriptionId\":101,\"descriptionTabName\":\"Ingredients\","
                + "\"descriptionTabContent\":\"Sugar, Spice, Everything Nice\"},"
                + "{\"descriptionId\":102,\"descriptionTabName\":\"Storage\","
                + "\"descriptionTabContent\":\"<p>Keep dry</p>\"}]}";

        mockMvc.perform(get("/user/supply/getProductDescription")
                        .param("productId", productId.toString())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().json(expectedJson, true));
    }

    @Test
    void getProductsFromCategory_rejectsAPageBelowZero() throws Exception {
        mockMvc.perform(get("/user/supply/getProductsFromCategory")
                        .param("categoryId", "1")
                        .param("page", "-1")
                        .param("productRange", "10"))
                .andExpect(status().isBadRequest());

        verify(userSupplyService, never()).getProductsFromCategory(anyLong(), anyInt(), anyInt());
    }

    @Test
    void getProductsFromCategory_rejectsARangeOutsideOneToOneHundred() throws Exception {
        for (String range : List.of("-1", "0", "101", "2147483647")) {
            mockMvc.perform(get("/user/supply/getProductsFromCategory")
                            .param("categoryId", "1")
                            .param("page", "0")
                            .param("productRange", range))
                    .andExpect(status().isBadRequest());
        }

        verify(userSupplyService, never()).getProductsFromCategory(anyLong(), anyInt(), anyInt());
    }

    @Test
    void getProductsFromCategory_rejectsAValueThatIsNotAnInteger() throws Exception {
        // Surrounding whitespace and hex literals are deliberately absent:
        // Spring's number converter trims whitespace and parses a "0x" prefix,
        // so " 1", "1 " and "0x10" are valid encodings of 1, 1 and 16 rather
        // than malformed input. Rejecting them would mean rejecting values the
        // rest of the stack already accepts.
        for (String malformed : List.of("abc", "1.5", "", "999999999999", "2147483648")) {
            int status = mockMvc.perform(get("/user/supply/getProductsFromCategory")
                            .param("categoryId", "1")
                            .param("page", "0")
                            .param("productRange", malformed))
                    .andReturn().getResponse().getStatus();
            assertEquals(400, status, "expected productRange=" + malformed + " to be rejected");
        }

        mockMvc.perform(get("/user/supply/getProductsFromCategory")
                        .param("categoryId", "1")
                        .param("page", "not-a-page")
                        .param("productRange", "10"))
                .andExpect(status().isBadRequest());

        verify(userSupplyService, never()).getProductsFromCategory(anyLong(), anyInt(), anyInt());
    }

    @Test
    void getProductsFromCategory_acceptsPageZeroAndBothSizeBounds() throws Exception {
        when(userSupplyService.getProductsFromCategory(1L, 0, 1)).thenReturn(List.of());
        when(userSupplyService.getProductsFromCategory(1L, 0, 100)).thenReturn(List.of());

        mockMvc.perform(get("/user/supply/getProductsFromCategory")
                        .param("categoryId", "1")
                        .param("page", "0")
                        .param("productRange", "1"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));

        mockMvc.perform(get("/user/supply/getProductsFromCategory")
                        .param("categoryId", "1")
                        .param("page", "0")
                        .param("productRange", "100"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    @Test
    void getProductsFromCategory_aPageBeyondTheLastOneIsAnEmptySuccess() throws Exception {
        when(userSupplyService.getProductsFromCategory(1L, 99, 10)).thenReturn(List.of());

        mockMvc.perform(get("/user/supply/getProductsFromCategory")
                        .param("categoryId", "1")
                        .param("page", "99")
                        .param("productRange", "10"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    @Test
    void getProductsFromSubcategory_rejectsPagesAndRangesOutsideTheBound() throws Exception {
        for (String page : List.of("-1", "-100")) {
            mockMvc.perform(get("/user/supply/getProductsFromSubcategory")
                            .param("subcategoryName", "Fruits")
                            .param("page", page)
                            .param("productRange", "10"))
                    .andExpect(status().isBadRequest());
        }

        for (String range : List.of("-1", "0", "101", "999999999999", "not-a-number")) {
            mockMvc.perform(get("/user/supply/getProductsFromSubcategory")
                            .param("subcategoryName", "Fruits")
                            .param("page", "0")
                            .param("productRange", range))
                    .andExpect(status().isBadRequest());
        }

        verify(userSupplyService, never()).getProductsFromSubcategory(anyString(), anyInt(), anyInt());
    }

    @Test
    void getProductsFromSubcategory_acceptsPageZeroAndBothSizeBounds() throws Exception {
        when(userSupplyService.getProductsFromSubcategory("Fruits", 0, 1)).thenReturn(List.of());
        when(userSupplyService.getProductsFromSubcategory("Fruits", 0, 100)).thenReturn(List.of());

        mockMvc.perform(get("/user/supply/getProductsFromSubcategory")
                        .param("subcategoryName", "Fruits")
                        .param("page", "0")
                        .param("productRange", "1"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));

        mockMvc.perform(get("/user/supply/getProductsFromSubcategory")
                        .param("subcategoryName", "Fruits")
                        .param("page", "0")
                        .param("productRange", "100"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }
}


