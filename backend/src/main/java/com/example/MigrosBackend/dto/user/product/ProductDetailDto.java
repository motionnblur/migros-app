package com.example.MigrosBackend.dto.user.product;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class ProductDetailDto {
    private String productName;
    private String subCategoryName;
    private BigDecimal productPrice;
    private int productCount;
    private BigDecimal productDiscount;
    private String productDescription;
    private int productCategoryId;

    /**
     * The price a customer actually pays for this product, already discounted and
     * rounded to the money scale.
     *
     * <p>Additive, and the reason it exists: {@link #productPrice} and
     * {@link #productDiscount} are the <em>stored</em> columns, and a client that
     * recombines them has to reproduce
     * {@link com.example.MigrosBackend.helper.ProductPricingPolicy}'s rounding
     * sequence in the browser to arrive at the price. It does not agree with it at
     * the boundaries - a stored price of 10.10 with a 5 percent discount is 9.60 to
     * the policy and 9.59 to {@code +(10.10 - 10.10 * 5 / 100).toFixed(2)} - so
     * the detail page would quote a price one cent below the one the catalogue card
     * above it shows, the cart line shows, and checkout charges.
     *
     * <p>{@link #productPrice} and {@link #productDiscount} keep their existing
     * meaning and are still sent: the client still needs them to show the struck
     * through original and the discount percentage. This field is what it renders
     * as the current price.
     */
    private BigDecimal effectivePrice;

    /**
     * The edit version the reader observed, for a subsequent product update.
     *
     * <p>Additive for catalog readers: existing fields and their meaning are
     * unchanged, and nothing else in the app consumes this. It is what lets an
     * admin edit form detect that stock moved under it instead of overwriting
     * it with a stale absolute count.
     */
    private Long productVersion;

    /**
     * How much is in one package, or {@code null} when the product carries no
     * package size.
     *
     * <p>Additive, and {@code null} for every product that predates V14. This read
     * is also the admin editor's source, so it carries the stored size rather than
     * a display formatting of it: an editor has to be able to show what is stored
     * and change it, and the customer rendering happens on top of that.
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
     * <p>Computed on the server by
     * {@link com.example.MigrosBackend.helper.ProductUnitPricePolicy} from the same
     * discounted price {@link #productPrice} and {@link #productDiscount}
     * describe, so the number a customer reads next to the package price cannot
     * describe a different price than the one they pay. The client formats it and
     * never divides.
     */
    private BigDecimal unitPrice;

    /** What {@link #unitPrice} is per: {@code KG}, {@code L} or {@code ADET}. */
    private String unitPriceBasis;
}
