package com.example.MigrosBackend.controller.admin.panel;

import com.example.MigrosBackend.dto.admin.panel.AdminAddItemDto;
import com.example.MigrosBackend.dto.admin.panel.AdminProductPreviewDto;
import com.example.MigrosBackend.dto.admin.panel.ProductDescriptionTabDto;
import com.example.MigrosBackend.dto.admin.panel.ProductDescriptionListDto;
import com.example.MigrosBackend.dto.order.OrderDto;
import com.example.MigrosBackend.dto.order.OrderPageDto;
import com.example.MigrosBackend.dto.user.UserProfileTableDto;
import com.example.MigrosBackend.dto.user.product.ProductDetailDto;
import com.example.MigrosBackend.dto.user.product.ProductDto;
import com.example.MigrosBackend.entity.product.ProductEntity;
import com.example.MigrosBackend.exception.admin.ProductEditConflictException;
import com.example.MigrosBackend.exception.shared.GeneralException;
import com.example.MigrosBackend.config.security.CsrfAccessDeniedHandler;
import com.example.MigrosBackend.repository.admin.AdminEntityRepository;
import com.example.MigrosBackend.service.admin.supply.AdminOrderService;
import com.example.MigrosBackend.service.admin.supply.AdminSupplyService;
import com.example.MigrosBackend.service.global.TokenService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import com.example.MigrosBackend.config.ProductEditConflictExceptionHandler;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import org.springframework.security.web.csrf.InvalidCsrfTokenException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
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
    private AdminOrderService adminOrderService;

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
        ProductDetailDto productDto2 = new ProductDetailDto();
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
                        .content(objectMapper.writeValueAsString(validAddItemDto())))
                .andExpect(status().isOk());
    }

    /**
     * The category name is part of the request, and it is what puts the created
     * row in a category: a product with no category key is invisible to every
     * category listing. The request field names are unchanged; what changed is
     * that a request without one is no longer accepted.
     */
    @Test
    void addProduct_shouldReturnBadRequest_whenCategoryNameIsMissing() throws Exception {
        AdminAddItemDto withoutCategory = validAddItemDto();
        withoutCategory.getProductDto().setCategoryName(null);

        mockMvc.perform(post("/admin/panel/addProduct")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(withoutCategory)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[?(@.field == 'productDto.categoryName')]").exists());

        verify(adminSupplyService, never()).addProduct(any());
    }

    @Test
    void addProduct_shouldReturnBadRequest_whenCategoryNameIsBlank() throws Exception {
        AdminAddItemDto blankCategory = validAddItemDto();
        blankCategory.getProductDto().setCategoryName("   ");

        mockMvc.perform(post("/admin/panel/addProduct")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(blankCategory)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        verify(adminSupplyService, never()).addProduct(any());
    }

    @Test
    void addProduct_shouldReturnBadRequest_whenProductNameIsBlank() throws Exception {
        AdminAddItemDto blankName = validAddItemDto();
        blankName.getProductDto().setProductName("  ");

        mockMvc.perform(post("/admin/panel/addProduct")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(blankName)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[?(@.field == 'productDto.productName')]").exists());

        verify(adminSupplyService, never()).addProduct(any());
    }

    @Test
    void addProduct_shouldReturnBadRequest_whenSubCategoryNameIsBlank() throws Exception {
        AdminAddItemDto blankSub = validAddItemDto();
        blankSub.getProductDto().setSubCategoryName("  ");

        mockMvc.perform(post("/admin/panel/addProduct")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(blankSub)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[?(@.field == 'productDto.subCategoryName')]").exists());

        verify(adminSupplyService, never()).addProduct(any());
    }

    @Test
    void addProduct_shouldReturnBadRequest_whenPriceIsNegative() throws Exception {
        AdminAddItemDto negativePrice = validAddItemDto();
        negativePrice.getProductDto().setProductPrice(new BigDecimal("-1.00"));

        mockMvc.perform(post("/admin/panel/addProduct")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(negativePrice)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        verify(adminSupplyService, never()).addProduct(any());
    }

    @Test
    void addProduct_shouldReturnBadRequest_whenCountIsNegative() throws Exception {
        AdminAddItemDto negativeCount = validAddItemDto();
        negativeCount.getProductDto().setProductCount(-1);

        mockMvc.perform(post("/admin/panel/addProduct")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(negativeCount)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[?(@.field == 'productDto.productCount')]").exists());

        verify(adminSupplyService, never()).addProduct(any());
    }

    @Test
    void addProduct_shouldReturnBadRequest_whenDiscountIsOutOfRange() throws Exception {
        AdminAddItemDto highDiscount = validAddItemDto();
        highDiscount.getProductDto().setProductDiscount(new BigDecimal("100.01"));

        mockMvc.perform(post("/admin/panel/addProduct")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(highDiscount)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        verify(adminSupplyService, never()).addProduct(any());
    }

    /**
     * The length and money-scale rules are not re-implemented at the boundary,
     * so they surface as the policy's own 400 rather than a bean-validation
     * failure - and reach the service, which is the only place that can apply
     * them to a non-HTTP caller too.
     */
    @Test
    void addProduct_shouldReturnBadRequest_whenAValueExceedsTheSchema() throws Exception {
        doThrow(new GeneralException("Product name must not exceed 255 characters"))
                .when(adminSupplyService).addProduct(any());

        AdminAddItemDto tooLong = validAddItemDto();
        tooLong.getProductDto().setProductName("x".repeat(256));

        mockMvc.perform(post("/admin/panel/addProduct")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(tooLong)))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("Product name must not exceed 255 characters"));
    }

    @Test
    void addProduct_shouldReturnBadRequest_whenPriceHasTooManyDecimals() throws Exception {
        doThrow(new GeneralException("Product price must not exceed two decimal places"))
                .when(adminSupplyService).addProduct(any());

        AdminAddItemDto overPrecise = validAddItemDto();
        overPrecise.getProductDto().setProductPrice(new BigDecimal("10.001"));

        mockMvc.perform(post("/admin/panel/addProduct")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(overPrecise)))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("Product price must not exceed two decimal places"));
    }

    /**
     * The request field names are the published contract for this endpoint.
     */
    @Test
    void addProduct_requestShapeIsFrozen() throws Exception {
        String requestJson = "{\"adminId\":7,\"productDto\":{"
                + "\"productName\":\"Coke\",\"subCategoryName\":\"Cola\","
                + "\"productCount\":12,\"productPrice\":1.50,\"productDiscount\":0.10,"
                + "\"categoryName\":\"İçecek\",\"productDescription\":\"Iced cola\"}}";

        mockMvc.perform(post("/admin/panel/addProduct")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isOk());

        ArgumentCaptor<AdminAddItemDto> captor = ArgumentCaptor.forClass(AdminAddItemDto.class);
        verify(adminSupplyService).addProduct(captor.capture());
        ProductDto captured = captor.getValue().getProductDto();
        assertEquals(7L, captor.getValue().getAdminId());
        assertEquals("Coke", captured.getProductName());
        assertEquals("Cola", captured.getSubCategoryName());
        assertEquals(12, captured.getProductCount());
        assertEquals(0, new BigDecimal("1.50").compareTo(captured.getProductPrice()));
        assertEquals(0, new BigDecimal("0.10").compareTo(captured.getProductDiscount()));
        assertEquals("İçecek", captured.getCategoryName());
        assertEquals("Iced cola", captured.getProductDescription());
    }

    /**
     * A description is optional and a discount is optional, so an omitted one
     * must not be a rejection: the admin UI's multipart form has always been
     * able to omit the discount, and the column is NOT NULL.
     */
    @Test
    void addProduct_shouldAcceptAnAbsentDescriptionAndDiscount() throws Exception {
        AdminAddItemDto minimal = validAddItemDto();
        minimal.getProductDto().setProductDescription(null);
        minimal.getProductDto().setProductDiscount(null);

        mockMvc.perform(post("/admin/panel/addProduct")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(minimal)))
                .andExpect(status().isOk());

        verify(adminSupplyService).addProduct(any());
    }

    private AdminAddItemDto validAddItemDto() {
        AdminAddItemDto dto = new AdminAddItemDto();
        dto.setAdminId(1L);
        ProductDto productDto = new ProductDto();
        productDto.setProductName("Test Product");
        productDto.setSubCategoryName("SubCat");
        productDto.setProductPrice(new BigDecimal("10.00"));
        productDto.setProductCount(5);
        productDto.setProductDiscount(new BigDecimal("2.00"));
        productDto.setCategoryName("Beverages");
        productDto.setProductDescription("A description");
        dto.setProductDto(productDto);
        return dto;
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
                        .param("categoryValue", "1")
                        .param("expectedVersion", "5"))
                .andExpect(status().isOk())
                .andExpect(content().string("File uploaded successfully"));

        ArgumentCaptor<Long> versionCaptor = ArgumentCaptor.forClass(Long.class);
        verify(adminSupplyService).updateProduct(anyLong(), anyLong(), any(), any(), any(BigDecimal.class),
                anyInt(), any(BigDecimal.class), any(), anyInt(), any(), versionCaptor.capture());
        assertEquals(5L, versionCaptor.getValue(),
                "the version the editor loaded must reach the service unchanged");
    }

    /**
     * The version is required, and there is no default. An old client that
     * omits it is exactly the caller this guard exists for: an edit form whose
     * stock count may be minutes stale.
     */
    @Test
    void updateProduct_shouldReturnTypedBadRequest_whenVersionIsMissing() throws Exception {
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
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_PARAMETER"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("expectedVersion")));

        verify(adminSupplyService, never()).updateProduct(anyLong(), anyLong(), any(), any(),
                any(BigDecimal.class), anyInt(), any(BigDecimal.class), any(), anyInt(), any(), any());
    }

    @Test
    void updateProduct_shouldReturnTypedBadRequest_whenVersionIsNotNumeric() throws Exception {
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
                        .param("categoryValue", "1")
                        .param("expectedVersion", "seven"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("expectedVersion"))
                .andExpect(jsonPath("$.errors[0].message").value(not(containsString("seven"))));

        verify(adminSupplyService, never()).updateProduct(anyLong(), anyLong(), any(), any(),
                any(BigDecimal.class), anyInt(), any(BigDecimal.class), any(), anyInt(), any(), any());
    }

    @Test
    void updateProduct_shouldReturnTypedBadRequest_whenVersionIsNegative() throws Exception {
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
                        .param("categoryValue", "1")
                        .param("expectedVersion", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        verify(adminSupplyService, never()).updateProduct(anyLong(), anyLong(), any(), any(),
                any(BigDecimal.class), anyInt(), any(BigDecimal.class), any(), anyInt(), any(), any());
    }

    @Test
    void updateProduct_shouldReturnConflictWithoutLeakingRowState() throws Exception {
        doThrow(ProductEditConflictException.staleVersion())
                .when(adminSupplyService)
                .updateProduct(anyLong(), anyLong(), any(), any(), any(BigDecimal.class), anyInt(),
                        any(BigDecimal.class), any(), anyInt(), any(), any());

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
                        .param("categoryValue", "1")
                        .param("expectedVersion", "1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PRODUCT_EDIT_CONFLICT"))
                .andExpect(jsonPath("$.status").value(409));
    }

    /**
     * An optimistic-lock failure detected by the persistence context rather than
     * by the explicit version comparison answers with the identical conflict
     * body. Without that mapping it would be a 500 carrying the JPA message,
     * which contains row values.
     */
    @Test
    void updateProduct_shouldMapAnOptimisticLockFailureToTheSameConflict() throws Exception {
        doThrow(new ObjectOptimisticLockingFailureException(ProductEntity.class, 2L))
                .when(adminSupplyService)
                .updateProduct(anyLong(), anyLong(), any(), any(), any(BigDecimal.class), anyInt(),
                        any(BigDecimal.class), any(), anyInt(), any(), any());

        String body = mockMvc.perform(multipart("/admin/panel/updateProduct")
                        .file(mockFile)
                        .param("adminId", "1")
                        .param("productId", "2")
                        .param("productName", "Updated Product")
                        .param("subCategoryName", "SubCat")
                        .param("productPrice", "20.0")
                        .param("productCount", "10")
                        .param("productDiscount", "3.0")
                        .param("productDescription", "Updated Desc")
                        .param("categoryValue", "1")
                        .param("expectedVersion", "1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PRODUCT_EDIT_CONFLICT"))
                .andReturn().getResponse().getContentAsString();

        org.junit.jupiter.api.Assertions.assertFalse(body.contains("ProductEntity"),
                "the JPA exception text must not reach the client: " + body);
    }

    /**
     * A CSRF rejection stays a CSRF rejection.
     *
     * <p>The product-edit conflict handler lives in the same
     * {@code @RestControllerAdvice} layer as every other exception mapping, so
     * this pins the boundary: security-filter failures are answered by
     * {@code CsrfAccessDeniedHandler} as 403 / {@code CSRF_INVALID} and must
     * never be reclassified into the 409 product-conflict contract, or the SPA
     * would stop refreshing its token on an expired CSRF token.
     */
    @Test
    void aCsrfRejectionStays403AndIsNotReclassifiedAsAnEditConflict() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        try {
            new CsrfAccessDeniedHandler(new ObjectMapper()).handle(
                    new MockHttpServletRequest(), response,
                    new InvalidCsrfTokenException(
                            new DefaultCsrfToken("X-XSRF-TOKEN", "_csrf", "token"), "invalid"));
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }

        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("\"code\":\"CSRF_INVALID\""),
                "a CSRF failure must keep its own stable code: " + response.getContentAsString());
        assertFalse(response.getContentAsString().contains("PRODUCT_EDIT_CONFLICT"));

        // The reclassification this guards against has to be ruled out at the
        // handler, not just absent from the response above. The product-edit
        // advice declares no mapping for an access-denied exception, so a CSRF
        // failure can never be answered with the 409 conflict contract - which
        // would leave the SPA refreshing a token it does not need and never
        // retrying the mutation it should.
        List<Class<? extends Throwable>[]> mapped = Arrays.stream(
                        ProductEditConflictExceptionHandler.class.getDeclaredMethods())
                .map(m -> m.getAnnotation(ExceptionHandler.class))
                .filter(Objects::nonNull)
                .map(ExceptionHandler::value)
                .toList();

        for (Class<? extends Throwable>[] handled : mapped) {
            for (Class<? extends Throwable> mappedType : handled) {
                assertFalse(AccessDeniedException.class.isAssignableFrom(mappedType),
                        "the product-edit advice must not claim access-denied failures, but maps "
                                + mappedType.getName());
            }
        }
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

        when(adminOrderService.getAllOrders(anyInt(), anyInt())).thenReturn(pageDto);

        mockMvc.perform(get("/admin/panel/getAllOrders")
                        .param("page", "0")
                        .param("productRange", "10"))
                .andExpect(status().isOk())
                .andExpect(content().json(objectMapper.writeValueAsString(pageDto)));
    }

    /**
     * The paginated admin reads carry the same {@code PageRequestPolicy} bound as
     * the anonymous listings, so a page or range outside it is a structured 400
     * naming the parameter rather than a 500 from {@code PageRequest.of} or an
     * oversized LIMIT.
     */
    @Test
    void getAllAdminProducts_shouldRejectPageAndSizeOutsideThePolicy() throws Exception {
        assertRejectedAdminProductPage(-1, 10, "page");
        assertRejectedAdminProductPage(0, -1, "productRange");
        assertRejectedAdminProductPage(0, 0, "productRange");
        assertRejectedAdminProductPage(0, 101, "productRange");
        assertRejectedAdminProductPage(0, Integer.MAX_VALUE, "productRange");

        verify(adminSupplyService, never()).getAllAdminProducts(anyLong(), anyInt(), anyInt());
    }

    @Test
    void getAllAdminProducts_shouldAcceptTheBoundaryValues() throws Exception {
        when(adminSupplyService.getAllAdminProducts(anyLong(), anyInt(), anyInt())).thenReturn(List.of());

        mockMvc.perform(get("/admin/panel/getAllAdminProducts")
                        .param("adminId", "1")
                        .param("page", "0")
                        .param("productRange", "100"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/admin/panel/getAllAdminProducts")
                        .param("adminId", "1")
                        .param("page", "1")
                        .param("productRange", "1"))
                .andExpect(status().isOk());
    }

    @Test
    void getAllOrders_shouldRejectPageAndSizeOutsideThePolicy() throws Exception {
        assertRejectedOrderPage(-1, 10, "page");
        assertRejectedOrderPage(0, -1, "productRange");
        assertRejectedOrderPage(0, 0, "productRange");
        assertRejectedOrderPage(0, 101, "productRange");
        assertRejectedOrderPage(0, Integer.MAX_VALUE, "productRange");

        verify(adminOrderService, never()).getAllOrders(anyInt(), anyInt());
    }

    private void assertRejectedAdminProductPage(int page, int productRange, String expectedField) throws Exception {
        mockMvc.perform(get("/admin/panel/getAllAdminProducts")
                        .param("adminId", "1")
                        .param("page", String.valueOf(page))
                        .param("productRange", String.valueOf(productRange)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[?(@.field == '" + expectedField + "')]").exists());
    }

    private void assertRejectedOrderPage(int page, int productRange, String expectedField) throws Exception {
        mockMvc.perform(get("/admin/panel/getAllOrders")
                        .param("page", String.valueOf(page))
                        .param("productRange", String.valueOf(productRange)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[?(@.field == '" + expectedField + "')]").exists());
    }

    @Test
    void getUserProfileData_shouldReturnOk() throws Exception {
        UserProfileTableDto profile = new UserProfileTableDto();
        when(adminOrderService.getUserProfileData(anyLong())).thenReturn(profile);

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

    @Test
    void getProductData_jsonShapeIsFrozen() throws Exception {
        ProductDetailDto product = new ProductDetailDto();
        product.setProductName("Organic Honey");
        product.setSubCategoryName("Sweeteners");
        product.setProductPrice(new BigDecimal("15.50"));
        product.setProductCount(100);
        product.setProductDiscount(new BigDecimal("10.0"));
        product.setProductDescription("Pure natural honey.");
        product.setProductCategoryId(5);
        product.setProductVersion(11L);

        when(adminSupplyService.getProductData(anyLong())).thenReturn(product);

        // productVersion is the one intentional addition to this contract; it is
        // what an admin editor submits back as expectedVersion.
        String expectedJson = "{\"productName\":\"Organic Honey\",\"subCategoryName\":\"Sweeteners\","
                + "\"productPrice\":15.50,\"productCount\":100,\"productDiscount\":10.0,"
                + "\"productDescription\":\"Pure natural honey.\",\"productCategoryId\":5,"
                + "\"productVersion\":11}";

        mockMvc.perform(get("/admin/panel/getProductData")
                        .param("productId", "50"))
                .andExpect(status().isOk())
                .andExpect(content().json(expectedJson, true));
    }

    @Test
    void addProductDescription_requestShapeIsFrozen() throws Exception {
        String requestJson = "{\"productId\":50,\"descriptionList\":["
                + "{\"descriptionId\":101,\"descriptionTabName\":\"Ingredients\","
                + "\"descriptionTabContent\":\"Sugar, Spice, Everything Nice\"}]}";

        mockMvc.perform(post("/admin/panel/addProductDescription")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isOk());

        ArgumentCaptor<ProductDescriptionListDto> captor = ArgumentCaptor.forClass(ProductDescriptionListDto.class);
        verify(adminSupplyService).addProductDescription(captor.capture());
        ProductDescriptionListDto captured = captor.getValue();
        assertEquals(50L, captured.getProductId());
        assertEquals(1, captured.getDescriptionList().size());
        assertEquals(101L, captured.getDescriptionList().get(0).descriptionId());
        assertEquals("Ingredients", captured.getDescriptionList().get(0).tabName());
        assertEquals("Sugar, Spice, Everything Nice", captured.getDescriptionList().get(0).tabContent());
    }

    @Test
    void getProductDescription_jsonShapeIsFrozen() throws Exception {
        ProductDescriptionTabDto desc1 = new ProductDescriptionTabDto(101L, "Ingredients", "Sugar, Spice, Everything Nice");
        ProductDescriptionTabDto desc2 = new ProductDescriptionTabDto(102L, "Storage", "<p>Keep dry</p>");

        ProductDescriptionListDto resultDto = new ProductDescriptionListDto();
        resultDto.setProductId(50L);
        resultDto.setDescriptionList(List.of(desc1, desc2));

        when(adminSupplyService.getProductDescription(anyLong())).thenReturn(resultDto);

        String expectedJson = "{\"productId\":50,\"descriptionList\":["
                + "{\"descriptionId\":101,\"descriptionTabName\":\"Ingredients\","
                + "\"descriptionTabContent\":\"Sugar, Spice, Everything Nice\"},"
                + "{\"descriptionId\":102,\"descriptionTabName\":\"Storage\","
                + "\"descriptionTabContent\":\"<p>Keep dry</p>\"}]}";

        mockMvc.perform(get("/admin/panel/getProductDescription")
                        .param("productId", "50"))
                .andExpect(status().isOk())
                .andExpect(content().json(expectedJson, true));
    }
}
