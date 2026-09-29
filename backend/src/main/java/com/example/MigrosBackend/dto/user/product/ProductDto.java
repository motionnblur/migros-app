package com.example.MigrosBackend.dto.user.product;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;

/**
 * The product body of a JSON product-creation request.
 *
 * <p>The constraints here are the ones that only make sense at a request
 * boundary: which fields must be present at all, and which may not be negative.
 * The {@code VARCHAR(255)} lengths, the two-decimal money scale and the
 * {@code NUMERIC(19, 2)} capacity are enforced once, by
 * {@code ProductCreationPolicy}, which every creation path shares - a second
 * copy of the arithmetic here would be a second thing to fall out of step, and
 * a value this class let through would still have to be rejected further in.
 *
 * <p>Field names are part of the published request contract and are unchanged.
 */
@Getter
@Setter
public class ProductDto {
    // Lengths are validated by the shared policy against the real VARCHAR(255)
    // columns; @Size is deliberately absent so the rule has exactly one owner.
    @NotBlank
    private String productName;
    @NotBlank
    private String subCategoryName;
    @PositiveOrZero
    private int productCount;
    @NotNull
    @PositiveOrZero
    private BigDecimal productPrice;
    /**
     * Optional. An omitted discount is a product with no discount rather than a
     * rejected request, because the column is {@code NOT NULL} and the multipart
     * creation form - the path the admin UI actually uses - has always sent the
     * field as optional-but-present.
     */
    @PositiveOrZero
    @DecimalMax("100.00")
    private BigDecimal productDiscount;
    /**
     * Identifies the category this product belongs to. Required: the product row
     * carries a category foreign key with no default, so a product created
     * without one is a row nothing can list it by.
     */
    @NotBlank
    private String categoryName;
    /** Optional. An absent description is stored as the empty string. */
    private String productDescription;
    private List<String> productImageNames;
}
