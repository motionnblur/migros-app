package com.example.MigrosBackend.controller.user.supply;

import com.example.MigrosBackend.dto.admin.panel.DescriptionsDto;
import com.example.MigrosBackend.dto.admin.panel.ProductDescriptionListDto;
import com.example.MigrosBackend.dto.admin.panel.ProductDto2;
import com.example.MigrosBackend.dto.user.category.SubCategoryDto;
import com.example.MigrosBackend.dto.user.product.ProductPreviewDto;
import com.example.MigrosBackend.dto.user.product.UserCartItemDto;
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

    @Test
    void getSubProductsFromCategory_ShouldReturnProductList() throws Exception {
        // Arrange
        String subName = "Fruits";
        int page = 0;
        int range = 10;

        ProductPreviewDto dto = new ProductPreviewDto();
        dto.setProductId(1L);
        dto.setProductName("Apple");
        dto.setProductPrice(2.5f);

        when(userSupplyService.getProductsFromSubcategory(subName, page, range))
                .thenReturn(List.of(dto));

        // Act & Assert
        mockMvc.perform(get("/user/supply/getProductsFromSubcategory") // Full path updated!
                        .param("subcategoryName", subName)
                        .param("page", String.valueOf(page))
                        .param("productRange", String.valueOf(range)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].productName").value("Apple"));
    }

    @Test
    void getProductCountsFromSubcategory_ShouldReturnCount() throws Exception {
        // Arrange
        String subName = "Beverages";
        int expectedCount = 42;

        // Mock the service to return our specific integer
        when(userSupplyService.getProductCountsFromSubcategory(subName))
                .thenReturn(expectedCount);

        // Act & Assert
        mockMvc.perform(get("/user/supply/getProductCountsFromSubcategory") // Full path!
                        .param("subcategoryName", subName)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk()) // 200 OK
                // Since the response is just a raw integer, the root "$" is the value itself
                .andExpect(jsonPath("$").value(expectedCount));

        // Verify service interaction
        verify(userSupplyService, times(1)).getProductCountsFromSubcategory(subName);
    }

    @Test
    void getProductCountsFromCategory_ShouldReturnTotalCount() throws Exception {
        // Arrange
        Long categoryId = 5L;
        int expectedCount = 120;

        // Mock the service layer
        when(userSupplyService.getProductCountsFromCategory(categoryId))
                .thenReturn(expectedCount);

        // Act & Assert
        mockMvc.perform(get("/user/supply/getProductCountsFromCategory")
                        .param("categoryId", categoryId.toString()) // Parameters are sent as Strings in HTTP
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                // For a single primitive/wrapper return, "$" is the root value
                .andExpect(jsonPath("$").value(expectedCount));

        // Verify service interaction
        verify(userSupplyService).getProductCountsFromCategory(categoryId);
    }

    @Test
    void getProductImageNames_ShouldReturnListOfStrings() throws Exception {
        // Arrange
        Long productId = 101L;
        List<String> imageNames = List.of("image1.jpg", "image2.png", "thumbnail.webp");

        when(userSupplyService.getProductImageNames(productId))
                .thenReturn(imageNames);

        // Act & Assert
        mockMvc.perform(get("/user/supply/getProductImageNames")
                        .param("productId", productId.toString())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                // Check that the response is an array of size 3
                .andExpect(jsonPath("$.size()").value(3))
                // Verify specific values in the list
                .andExpect(jsonPath("$[0]").value("image1.jpg"))
                .andExpect(jsonPath("$[1]").value("image2.png"))
                .andExpect(jsonPath("$[2]").value("thumbnail.webp"));

        verify(userSupplyService).getProductImageNames(productId);
    }

    @Test
    void getSubCategories_ShouldReturnDtoList() throws Exception {
        // Arrange
        Long categoryId = 1L;

        SubCategoryDto dto1 = new SubCategoryDto();
        dto1.setSubCategoryId(10L); // Changed from .setId
        dto1.setSubCategoryName("Dairy"); // Changed from .setSubcategoryName

        SubCategoryDto dto2 = new SubCategoryDto();
        dto2.setSubCategoryId(11L);
        dto2.setSubCategoryName("Eggs");

        List<SubCategoryDto> mockList = List.of(dto1, dto2);

        when(userSupplyService.getSubCategories(categoryId)).thenReturn(mockList);

        // Act & Assert
        mockMvc.perform(get("/user/supply/getSubCategories")
                        .param("categoryId", categoryId.toString())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size()").value(2))
                // Updated paths to match your actual DTO fields
                .andExpect(jsonPath("$[0].subCategoryId").value(10))
                .andExpect(jsonPath("$[0].subCategoryName").value("Dairy"))
                .andExpect(jsonPath("$[1].subCategoryId").value(11))
                .andExpect(jsonPath("$[1].subCategoryName").value("Eggs"));

        verify(userSupplyService, times(1)).getSubCategories(categoryId);
    }

    @Test
    void getProductData_ShouldReturnCartItems() throws Exception {
        // Arrange
        UserCartItemDto item1 = new UserCartItemDto();
        item1.setProductId(101L);
        item1.setProductName("Milk");
        item1.setProductPrice(1.5f); // Match the body field name
        item1.setProductCount(2);   // Match the body field name

        UserCartItemDto item2 = new UserCartItemDto();
        item2.setProductId(102L);
        item2.setProductName("Bread");
        item2.setProductPrice(2.0f);
        item2.setProductCount(1);

        List<UserCartItemDto> cartItems = List.of(item1, item2);

        when(userSupplyService.getProductData()).thenReturn(cartItems);

        // Act & Assert
        mockMvc.perform(get("/user/supply/getProductData")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size()").value(2))
                // Content validation with correct keys
                .andExpect(jsonPath("$[0].productId").value(101))
                .andExpect(jsonPath("$[0].productName").value("Milk"))
                .andExpect(jsonPath("$[0].productPrice").value(1.5))
                .andExpect(jsonPath("$[0].productCount").value(2))
                .andExpect(jsonPath("$[1].productId").value(102))
                .andExpect(jsonPath("$[1].productName").value("Bread"));

        verify(userSupplyService, times(1)).getProductData();
    }

    @Test
    void getProductDataWithProductId_ShouldReturnProduct() throws Exception {
        // Arrange
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

        // Act & Assert
        mockMvc.perform(get("/user/supply/getProductDataWithProductId")
                        .param("productId", productId.toString())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                // Matching your DTO fields exactly
                .andExpect(jsonPath("$.productName").value("Organic Honey"))
                .andExpect(jsonPath("$.subCategoryName").value("Sweeteners"))
                .andExpect(jsonPath("$.productPrice").value(15.50))
                .andExpect(jsonPath("$.productCount").value(100))
                .andExpect(jsonPath("$.productCategoryId").value(5));

        verify(userSupplyService).getProductData(productId);
    }

    @Test
    void getProductDescription_ShouldReturnNestedDescriptions() throws Exception {
        // Arrange
        Long productId = 50L;

        // 1. Setup the Nested DTOs
        DescriptionsDto desc1 = new DescriptionsDto();
        desc1.setDescriptionId(101L);
        desc1.setDescriptionTabName("Ingredients");
        desc1.setDescriptionTabContent("Sugar, Spice, Everything Nice");

        ProductDescriptionListDto resultDto = new ProductDescriptionListDto();
        resultDto.setProductId(productId);
        resultDto.setDescriptionList(List.of(desc1));

        // 2. Mock Service
        when(userSupplyService.getProductDescription(productId)).thenReturn(resultDto);

        // Act & Assert
        mockMvc.perform(get("/user/supply/getProductDescription")
                        .param("productId", productId.toString())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                // Verify top-level field
                .andExpect(jsonPath("$.productId").value(50))
                // Verify nested list fields
                .andExpect(jsonPath("$.descriptionList[0].descriptionId").value(101))
                .andExpect(jsonPath("$.descriptionList[0].descriptionTabName").value("Ingredients"))
                .andExpect(jsonPath("$.descriptionList[0].descriptionTabContent").value("Sugar, Spice, Everything Nice"));

        verify(userSupplyService).getProductDescription(productId);
    }

    @Test
    void updateProductCountInUserCart_ShouldReturnOk() throws Exception {
        // Arrange
        Long productId = 101L;
        int count = 5;
        String token = "mock-jwt-token";

        // Since the service method returns void, we use doNothing() (or just skip when() as it's the default)
        doNothing().when(userSupplyService).updateProductCountInInventory(productId, count, token);

        // Act & Assert
        mockMvc.perform(get("/user/supply/updateProductCountInUserCart")
                        .param("productId", productId.toString())
                        .param("count", String.valueOf(count))
                        .param("token", token)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").doesNotExist()); // Verify the body is empty

        // Verify the service was actually triggered with these values
        verify(userSupplyService, times(1)).updateProductCountInInventory(productId, count, token);
    }

    @Test
    void cancelOrder_ShouldReturnOk() throws Exception {
        // Arrange
        Long orderId = 99L;
        String token = "user-auth-token";

        // Standard for void methods: we tell Mockito to do nothing
        doNothing().when(userSupplyService).cancelOrder(orderId, token);

        // Act & Assert
        mockMvc.perform(delete("/user/supply/cancelOrder") // Using delete() instead of get()
                        .param("orderId", orderId.toString())
                        .param("token", token)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").doesNotExist());

        // Crucial: verify the service was called with the specific ID and token
        verify(userSupplyService, times(1)).cancelOrder(orderId, token);
    }
}