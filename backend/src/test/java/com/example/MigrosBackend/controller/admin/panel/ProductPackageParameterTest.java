package com.example.MigrosBackend.controller.admin.panel;

import com.example.MigrosBackend.dto.admin.panel.AdminAddItemDto;
import com.example.MigrosBackend.dto.user.product.ProductDto;
import com.example.MigrosBackend.exception.admin.ProductEditConflictException;
import com.example.MigrosBackend.exception.shared.GeneralException;
import com.example.MigrosBackend.helper.ProductEditVersionHeader;
import com.example.MigrosBackend.repository.admin.AdminEntityRepository;
import com.example.MigrosBackend.service.admin.supply.AdminOrderService;
import com.example.MigrosBackend.service.admin.supply.AdminSupplyService;
import com.example.MigrosBackend.service.global.TokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The two optional package parameters, at the HTTP boundary.
 *
 * <p>Everything below the controller is covered against a real database by
 * {@code ProductPackageMetadataPostgresTest}, and the values themselves by
 * {@code ProductPackageMetadataPolicyTest}. What only the boundary can show is
 * whether an administrator's intent survives the trip: a multipart field that
 * binds to {@code null} when the form was left blank, a decimal that arrives as a
 * {@code BigDecimal} rather than a string, and a client that never heard of the
 * field at all still producing a product with no package size.
 *
 * <p>That last one is the compatibility claim of the whole feature, so it is
 * asserted explicitly on both write endpoints rather than left implicit in the
 * service tests.
 */
@WebMvcTest(AdminPanelController.class)
@AutoConfigureMockMvc(addFilters = false)
class ProductPackageParameterTest {

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

    private MockMultipartFile image;

    @BeforeEach
    void setUp() {
        image = new MockMultipartFile("selectedImage", "image.png", "image/png",
                "image-bytes".getBytes());
    }

    // -------------------------------------------------------------------------
    // multipart creation
    // -------------------------------------------------------------------------

    /**
     * A filled pair reaches the service as a number and a string, not as two
     * strings - the amount is divided by later, and a string that survived to the
     * division would be the sort of thing that throws.
     */
    @Test
    void uploadProductBindsAPackageAmountToANumber() throws Exception {
        mockMvc.perform(multipart("/admin/panel/uploadProduct")
                        .file(image)
                        .param("adminId", "1")
                        .param("productName", "Süt")
                        .param("subCategoryName", "Günlük")
                        .param("productPrice", "24.99")
                        .param("productCount", "12")
                        .param("productDiscount", "0")
                        .param("productDescription", "Fresh")
                        .param("categoryValue", "1")
                        .param("packageAmount", "1.5")
                        .param("packageUnit", "L"))
                .andExpect(status().isOk());

        ArgumentCaptor<BigDecimal> amount = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<String> unit = ArgumentCaptor.forClass(String.class);
        verify(adminSupplyService).uploadProduct(anyLong(), any(), any(), any(BigDecimal.class),
                anyInt(), any(BigDecimal.class), any(), anyInt(), amount.capture(), unit.capture(),
                any());

        assertEquals(0, new BigDecimal("1.5").compareTo(amount.getValue()));
        assertEquals("L", unit.getValue());
    }

    /**
     * A request from a client that predates the field is still a valid creation.
     *
     * <p>Both parameters are declared optional for exactly this, and this is the
     * test that would fail if one of them were made required.
     */
    @Test
    void uploadProductStillWorksWhenTheClientSendsNoPackageFields() throws Exception {
        mockMvc.perform(multipart("/admin/panel/uploadProduct")
                        .file(image)
                        .param("adminId", "1")
                        .param("productName", "Süt")
                        .param("subCategoryName", "Günlük")
                        .param("productPrice", "24.99")
                        .param("productCount", "12")
                        .param("productDiscount", "0")
                        .param("productDescription", "Fresh")
                        .param("categoryValue", "1"))
                .andExpect(status().isOk());

        ArgumentCaptor<BigDecimal> amount = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<String> unit = ArgumentCaptor.forClass(String.class);
        verify(adminSupplyService).uploadProduct(anyLong(), any(), any(), any(BigDecimal.class),
                anyInt(), any(BigDecimal.class), any(), anyInt(), amount.capture(), unit.capture(),
                any());

        assertNull(amount.getValue(), "an omitted amount is no package size, not zero");
        assertNull(unit.getValue());
    }

    /**
     * An empty multipart field - which is what an untouched form input produces -
     * must bind to null rather than to a string that would fail to parse or, worse,
     * parse as something.
     */
    @Test
    void anEmptyPackageFieldBindsToNothingRatherThanFailingTheBinding() throws Exception {
        mockMvc.perform(multipart("/admin/panel/uploadProduct")
                        .file(image)
                        .param("adminId", "1")
                        .param("productName", "Süt")
                        .param("subCategoryName", "Günlük")
                        .param("productPrice", "24.99")
                        .param("productCount", "12")
                        .param("productDiscount", "0")
                        .param("productDescription", "Fresh")
                        .param("categoryValue", "1")
                        .param("packageAmount", "")
                        .param("packageUnit", ""))
                .andExpect(status().isOk());

        ArgumentCaptor<BigDecimal> amount = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<String> unit = ArgumentCaptor.forClass(String.class);
        verify(adminSupplyService).uploadProduct(anyLong(), any(), any(), any(BigDecimal.class),
                anyInt(), any(BigDecimal.class), any(), anyInt(), amount.capture(), unit.capture(),
                any());

        assertNull(amount.getValue());
        assertEquals("", unit.getValue());
    }

    /**
     * The unit is forwarded exactly as submitted. Case normalization is the
     * policy's job, not the transport's: a controller that silently upper-cased it
     * would be a second place the accepted vocabulary is written down.
     */
    @Test
    void theUnitIsForwardedExactlyAsSubmitted() throws Exception {
        mockMvc.perform(multipart("/admin/panel/uploadProduct")
                        .file(image)
                        .param("adminId", "1")
                        .param("productName", "Peynir")
                        .param("subCategoryName", "Köy")
                        .param("productPrice", "45.00")
                        .param("productCount", "4")
                        .param("productDiscount", "0")
                        .param("productDescription", "Cheese")
                        .param("categoryValue", "1")
                        .param("packageAmount", "0.5")
                        .param("packageUnit", "kg"))
                .andExpect(status().isOk());

        verify(adminSupplyService).uploadProduct(anyLong(), any(), any(), any(BigDecimal.class),
                anyInt(), any(BigDecimal.class), any(), anyInt(), eq(new BigDecimal("0.5")),
                eq("kg"), any());
    }

    // -------------------------------------------------------------------------
    // version-checked edit
    // -------------------------------------------------------------------------

    /**
     * A metadata-only edit reaches the service with its pair and its version, and
     * the response still carries the version the write produced.
     *
     * <p>Package size is stored on the product row, so an edit that moves it has
     * to travel through the same version-checked endpoint as every other field.
     * If this pair had its own endpoint it would be the one write on this row
     * that no open editor could detect.
     */
    @Test
    void aMetadataOnlyEditTravelsThroughTheVersionCheckedEndpoint() throws Exception {
        when(adminSupplyService.updateProduct(anyLong(), anyLong(), any(), any(),
                any(BigDecimal.class), anyInt(), any(BigDecimal.class), any(), anyInt(), any(),
                any(), any(), any()))
                .thenReturn(7L);

        mockMvc.perform(multipart("/admin/panel/updateProduct")
                        .file(image)
                        .param("adminId", "1")
                        .param("productId", "2")
                        .param("productName", "Peynir")
                        .param("subCategoryName", "Köy")
                        .param("productPrice", "45.00")
                        .param("productCount", "4")
                        .param("productDiscount", "0")
                        .param("productDescription", "Cheese")
                        .param("categoryValue", "1")
                        .param("packageAmount", "0.5")
                        .param("packageUnit", "KG")
                        .param("expectedVersion", "6"))
                .andExpect(status().isOk())
                .andExpect(header().string(ProductEditVersionHeader.NAME, "7"));

        verify(adminSupplyService).updateProduct(anyLong(), anyLong(), any(), any(),
                any(BigDecimal.class), anyInt(), any(BigDecimal.class), any(), anyInt(),
                eq(new BigDecimal("0.5")), eq("KG"), any(), eq(6L));
    }

    /**
     * A stale editor is still refused, and still gets no version header, whatever
     * it tried to write.
     *
     * <p>Emitting a version here would tell the editor its size change landed. It
     * would advance its form and its next save would be accepted against a change
     * that was never applied - which is the exact failure the 409 exists to
     * prevent, and it would now be reachable through package metadata too.
     */
    @Test
    void aRejectedMetadataOnlyEditCarriesNoVersion() throws Exception {
        doThrow(ProductEditConflictException.staleVersion())
                .when(adminSupplyService).updateProduct(anyLong(), anyLong(), any(), any(),
                        any(BigDecimal.class), anyInt(), any(BigDecimal.class), any(), anyInt(),
                        any(), any(), any(), any());

        mockMvc.perform(multipart("/admin/panel/updateProduct")
                        .file(image)
                        .param("adminId", "1")
                        .param("productId", "2")
                        .param("productName", "Peynir")
                        .param("subCategoryName", "Köy")
                        .param("productPrice", "45.00")
                        .param("productCount", "4")
                        .param("productDiscount", "0")
                        .param("productDescription", "Cheese")
                        .param("categoryValue", "1")
                        .param("packageAmount", "9")
                        .param("packageUnit", "KG")
                        .param("expectedVersion", "1"))
                .andExpect(status().isConflict())
                .andExpect(header().doesNotExist(ProductEditVersionHeader.NAME));
    }

    /**
     * The validation message the administrator sees is the policy's, unchanged by
     * the transport: the accepted units arrive verbatim rather than being
     * pre-screened into something vaguer.
     */
    @Test
    void aPolicyRejectionReachesTheClientWithItsMessage() throws Exception {
        doThrow(new GeneralException("Package unit must be one of [G, KG, ML, L, ADET]"))
                .when(adminSupplyService).uploadProduct(anyLong(), any(), any(),
                        any(BigDecimal.class), anyInt(), any(BigDecimal.class), any(), anyInt(),
                        any(), any(), any());

        mockMvc.perform(multipart("/admin/panel/uploadProduct")
                        .file(image)
                        .param("adminId", "1")
                        .param("productName", "Süt")
                        .param("subCategoryName", "Günlük")
                        .param("productPrice", "24.99")
                        .param("productCount", "12")
                        .param("productDiscount", "0")
                        .param("productDescription", "Fresh")
                        .param("categoryValue", "1")
                        .param("packageAmount", "500")
                        .param("packageUnit", "GRAM"))
                .andExpect(status().isBadRequest());
    }

    // -------------------------------------------------------------------------
    // JSON creation
    // -------------------------------------------------------------------------

/**
     * The JSON body carries the same two fields, and carries them additively: a
     * body that omits them still binds to a product with no package size, which is
     * what every client written before this feature sends.
     */
@Test
    void jsonCreationCarriesThePackageFieldsAdditively() throws Exception {
        String bodyWithPackage = """
                {"adminId":1,"productDto":{"productName":"Süt","subCategoryName":"Günlük",
                "productPrice":24.99,"productCount":12,"productDiscount":0,
                "productDescription":"Fresh","categoryName":"Dairy",
                "packageAmount":0.75,"packageUnit":"KG"}}""";
        String bodyWithoutPackage = """
                {"adminId":1,"productDto":{"productName":"Süt","subCategoryName":"Günlük",
                "productPrice":24.99,"productCount":12,"productDiscount":0,
                "productDescription":"Fresh","categoryName":"Dairy"}}""";

        mockMvc.perform(post("/admin/panel/addProduct")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyWithPackage))
                .andExpect(status().isOk());

        ArgumentCaptor<AdminAddItemDto> withPackage =
                ArgumentCaptor.forClass(AdminAddItemDto.class);
        verify(adminSupplyService).addProduct(withPackage.capture());
        assertEquals(0, new BigDecimal("0.75")
                .compareTo(withPackage.getValue().getProductDto().getPackageAmount()));
        assertEquals("KG", withPackage.getValue().getProductDto().getPackageUnit());

        mockMvc.perform(post("/admin/panel/addProduct")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyWithoutPackage))
                .andExpect(status().isOk());

        ArgumentCaptor<AdminAddItemDto> withoutPackage =
                ArgumentCaptor.forClass(AdminAddItemDto.class);
        verify(adminSupplyService, times(2)).addProduct(withoutPackage.capture());
        ProductDto second = withoutPackage.getAllValues().get(1).getProductDto();
        assertNull(second.getPackageAmount());
        assertNull(second.getPackageUnit());
    }
}

