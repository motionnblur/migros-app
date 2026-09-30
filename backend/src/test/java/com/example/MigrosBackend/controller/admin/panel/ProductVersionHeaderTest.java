package com.example.MigrosBackend.controller.admin.panel;

import com.example.MigrosBackend.config.security.SecurityConfiguration;
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
import com.example.MigrosBackend.helper.ProductEditVersionHeader;
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
import org.springframework.web.cors.CorsConfigurationSource;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

/**
 * The edit version has to travel back to the browser, and there are three separate
 * places that can lose it.
 *
 * <p>The controller produces it, the CORS configuration exposes it, and the
 * response has to keep the body the endpoint has always returned. All three are
 * trivial in isolation and only meaningful together: a version that is computed
 * correctly but not written to a header, or written to a header the browser
 * discards on a cross-origin response, delivers nothing at all to the editor that
 * needs it, and the editor's next save is then rejected against its own
 * successful write. This is the HTTP boundary for that, with no database involved.
 */
@WebMvcTest(AdminPanelController.class)
@AutoConfigureMockMvc(addFilters = false)
class ProductVersionHeaderTest {
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

    /**
     * A successful edit answers 200, keeps its historical body byte for byte, and
     * adds the version the transaction produced.
     *
     * <p>The body matters as much as the header: the header is additive, and a
     * client that reads the old body must still find it there.
     */
    @Test
    void aSuccessfulEditCarriesTheVersionHeaderAndKeepsItsBody() throws Exception {
        when(adminSupplyService.updateProduct(anyLong(), anyLong(), any(), any(), any(BigDecimal.class),
                anyInt(), any(BigDecimal.class), any(), anyInt(), any(), any()))
                .thenReturn(42L);

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
                        .param("expectedVersion", "41"))
                .andExpect(status().isOk())
                .andExpect(content().string("File uploaded successfully"))
                .andExpect(header().string(ProductEditVersionHeader.NAME, "42"));

        // The editor's next save has to pair with the version this write produced,
        // so the submitted version and the produced one are different numbers and
        // both must be present in the exchange.
        ArgumentCaptor<Long> versionCaptor = ArgumentCaptor.forClass(Long.class);
        verify(adminSupplyService).updateProduct(anyLong(), anyLong(), any(), any(), any(BigDecimal.class),
                anyInt(), any(BigDecimal.class), any(), anyInt(), any(), versionCaptor.capture());
        assertEquals(41L, versionCaptor.getValue());
    }

    /**
     * A rejected edit has no version to hand out.
     *
     * <p>Emitting one - the version the editor submitted, or the current one read
     * after the fact - would tell the client that its write landed. The editor
     * would advance its form to that version and its next save would be accepted
     * against a change that was never applied, which is the precise failure the
     * 409 exists to prevent.
     */
    @Test
    void aRejectedEditCarriesNoVersionHeader() throws Exception {
        when(adminSupplyService.updateProduct(anyLong(), anyLong(), any(), any(), any(BigDecimal.class),
                anyInt(), any(BigDecimal.class), any(), anyInt(), any(), any()))
                .thenThrow(ProductEditConflictException.staleVersion());

        mockMvc.perform(multipart("/admin/panel/updateProduct")
                        .file(mockFile)
                        .param("adminId", "1")
                        .param("productId", "2")
                        .param("productName", "Stale Product")
                        .param("subCategoryName", "SubCat")
                        .param("productPrice", "20.0")
                        .param("productCount", "10")
                        .param("productDiscount", "3.0")
                        .param("productDescription", "Stale Desc")
                        .param("categoryValue", "1")
                        .param("expectedVersion", "5"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PRODUCT_EDIT_CONFLICT"))
                .andExpect(header().doesNotExist(ProductEditVersionHeader.NAME));
    }

    /**
     * The CORS half, which is the half that fails silently.
     *
     * <p>The client is a separate origin in every real deployment. A response
     * header that is not named in {@code Access-Control-Expose-Headers} is
     * present on the wire and invisible to browser JavaScript, so the version
     * would be computed, sent, and dropped in transit with no error anywhere. The
     * production bean is instantiated here directly, so this cannot pass by
     * asserting on a hand-built configuration that the real one happens to differ
     * from.
     */
    @Test
    void corsExposesTheVersionHeaderToTheBrowser() throws Exception {
        // The production bean, built the way the container builds it: the
        // constructor arguments are the four @Value-backed settings, and the
        // origins list is the one a split-origin deployment supplies.
        SecurityConfiguration securityConfiguration =
                new SecurityConfiguration("http://localhost:4200", "", false, "Lax");

        // The production bean, called directly. Reaching the real thing is the
        // point: a test that built its own CorsConfiguration would keep passing
        // if the production one stopped exposing the header, which is the
        // regression this guards. The bean is public for exactly this reason.
        CorsConfigurationSource source = securityConfiguration.corsConfigurationSource();

        List<String> exposedHeaders = source
                .getCorsConfiguration(new MockHttpServletRequest("OPTIONS", "/admin/panel/updateProduct"))
                .getExposedHeaders();

        assertNotNull(exposedHeaders,
                "without an exposed-header list the browser discards the version it was sent");
        assertTrue(exposedHeaders.contains(ProductEditVersionHeader.NAME),
                "the version header must be named in Access-Control-Expose-Headers, but was " + exposedHeaders);
        assertEquals("X-Product-Version", ProductEditVersionHeader.NAME,
                "the controller and the CORS configuration must agree on the name");
    }
}
