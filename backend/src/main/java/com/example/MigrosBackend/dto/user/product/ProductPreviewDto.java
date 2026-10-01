package com.example.MigrosBackend.dto.user.product;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class ProductPreviewDto {
    private Long productId;
    private String productName;
    private BigDecimal productPrice;
    private int productCount;

    /**
     * How much is in one package, or {@code null} when the product carries no
     * package size.
     *
     * <p>Additive, and {@code null} for the overwhelming majority of products:
     * V14 added the column nullable and deliberately did not infer a size from a
     * product name or a description, because a guessed quantity is a unit price
     * that looks authoritative and is wrong. A client hides the package line
     * entirely rather than rendering a placeholder.
     */
    private BigDecimal packageAmount;

    /**
     * The measure {@link #packageAmount} is counted in - {@code G}, {@code KG},
     * {@code ML}, {@code L} or {@code ADET} - or {@code null} alongside it.
     */
    private String packageUnit;

    /**
     * The package price per kilogram, per litre or per item, or {@code null} when
     * the product has no package size.
     *
     * <p>Computed on the server from the same effective package price
     * {@link #productPrice} already carries, by
     * {@link com.example.MigrosBackend.helper.ProductUnitPricePolicy}. The client
     * formats this number and never derives it, so the unit price a customer reads
     * cannot disagree with the price they will be charged.
     */
    private BigDecimal unitPrice;

    /**
     * What {@link #unitPrice} is per: {@code KG}, {@code L} or {@code ADET}, or
     * {@code null} alongside it.
     *
     * <p>Named rather than inferred from {@link #packageUnit}, because the two are
     * not the same thing: 500 {@code G} and 0.5 {@code KG} are both priced per
     * {@code KG}.
     */
    private String unitPriceBasis;
}
