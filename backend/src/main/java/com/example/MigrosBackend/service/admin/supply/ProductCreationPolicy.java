package com.example.MigrosBackend.service.admin.supply;

import com.example.MigrosBackend.entity.admin.AdminEntity;
import com.example.MigrosBackend.entity.category.CategoryEntity;
import com.example.MigrosBackend.entity.product.ProductEntity;
import com.example.MigrosBackend.exception.shared.GeneralException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * The single set of rules for turning submitted product fields into a stored
 * product, used by JSON creation, multipart upload, and edit alike.
 *
 * <p>These used to be two sets of rules, which is how the two creation paths
 * drifted apart in the first place. The multipart path trimmed and checked every
 * field before writing anything; the JSON path copied four fields straight onto
 * a new {@code ProductEntity} and left the rest null. Since
 * {@code product_description} is {@code NOT NULL} and the category foreign key
 * has no default, that endpoint could not produce a row it was happy with: a
 * missing description was an integrity error, and a product with no category was
 * a row the detail endpoint itself could not read. One policy closes that gap
 * because there is now nothing left to keep in step - adding a field to a third
 * path would otherwise mean finding every path that fills the entity in.
 *
 * <p>It holds no state and is a Spring bean only so the service can be handed
 * one rather than reaching for a static. The rules themselves are pure functions
 * of their input, which is what makes them testable without a database.
 *
 * <h2>Why the numbers are the ones they are</h2>
 *
 * <p>Every bound here is derived from the actual schema rather than from a
 * round number that happens to be in range, because an out-of-range value must
 * be a 400 the administrator can act on and not a 500 raised by the driver
 * halfway through an insert:
 *
 * <ul>
 *   <li>{@code product_name}, {@code subcategory_name} and
 *       {@code product_description} are {@code VARCHAR(255)}, so a longer
 *       value is rejected here instead of overflowing the column.</li>
 *   <li>{@code product_price} and {@code product_discount} are
 *       {@code NUMERIC(19, 2)}: nineteen significant digits of which two sit
 *       after the point, leaving seventeen integer digits, and a maximum of
 *       {@code 99999999999999999.99}. A price PostgreSQL would refuse - and then
 *       round silently on the values it accepts - is rejected with a message
 *       that says which field is out of range.</li>
 * </ul>
 *
 * <p>These are deliberately not duplicated in the request DTOs beyond the
 * presence and sign checks that only make sense at a boundary; a second
 * independent copy of the length and scale rules is exactly the thing that lets
 * two entry points disagree.
 */
@Component
final class ProductCreationPolicy {

    /**
     * The declared width of {@code product_name}, {@code subcategory_name} and
     * {@code product_description}.
     */
    static final int MAX_TEXT_LENGTH = 255;

    /** The scale of {@code NUMERIC(19, 2)} money columns. */
    static final int MONEY_SCALE = 2;

    /** The precision of {@code NUMERIC(19, 2)} money columns. */
    static final int MONEY_PRECISION = 19;

    /** Integer digits available in a {@code NUMERIC(19, 2)} money column. */
    static final int MONEY_INTEGER_DIGITS = MONEY_PRECISION - MONEY_SCALE;

    private static final BigDecimal MAX_MONEY =
            BigDecimal.TEN.pow(MONEY_INTEGER_DIGITS).subtract(BigDecimal.ONE.movePointLeft(MONEY_SCALE));

    private static final BigDecimal MAX_DISCOUNT = new BigDecimal("100");

    /**
     * Validates and normalizes submitted product fields.
     *
     * <p>Returns a new value rather than mutating the argument, so a caller
     * cannot accidentally keep using a raw one after validation has passed. All
     * rejections are {@link GeneralException}, which the global handler already
     * answers with 400 and the message verbatim - the same contract the
     * multipart path has always returned, so the two creation endpoints are
     * indistinguishable to a client apart from their request shape.
     */
    ProductDetails normalize(ProductDetails raw) {
        String productName = normalizeRequiredText("Product name", raw.productName());
        String subCategoryName = normalizeRequiredText("Subcategory name", raw.subCategoryName());
        String productDescription = normalizeOptionalText("Product description", raw.productDescription());

        BigDecimal productPrice = requireProductPrice(raw.productPrice());
        int productCount = requireProductCount(raw.productCount());
        BigDecimal productDiscount = requireProductDiscount(raw.productDiscount());

        return ProductDetails.of(productName, subCategoryName, productPrice, productCount,
                productDiscount, productDescription);
    }

    /**
     * Validates the category name a JSON caller identifies its category by.
     *
     * <p>Kept here rather than in the service so the name is trimmed and length
     * checked by the same rules as the other submitted text. It returns the name
     * rather than a {@link CategoryEntity} because which row a name selects is a
     * lookup, and the lookup has to be able to reject a name that matches more
     * than one row.
     */
    String normalizeCategoryName(String rawCategoryName) {
        return normalizeRequiredText("Category name", rawCategoryName);
    }

    /**
     * Copies normalized details and both owning references onto a product.
     *
     * <p>The admin and category are set here, explicitly, and the product is
     * saved afterwards as the row that owns the two foreign keys. They are
     * declared on the many side, so a product that saved without them would have
     * no owner at all - and repairing that afterwards by mutating and re-saving
     * the admin's back-reference collection is a second write that can half
     * succeed, and that cascades the product again through
     * {@code CascadeType.ALL} besides. One insert with both references set has
     * no window in which the row is visible and unowned.
     */
    void applyTo(ProductEntity productEntity, ProductDetails details,
                 AdminEntity adminEntity, CategoryEntity categoryEntity) {
        productEntity.setAdminEntity(adminEntity);
        productEntity.setCategoryEntity(categoryEntity);
        productEntity.setProductName(details.productName());
        productEntity.setSubcategoryName(details.subCategoryName());
        productEntity.setProductCount(details.productCount());
        productEntity.setProductPrice(details.productPrice());
        productEntity.setProductDiscount(details.productDiscount());
        productEntity.setProductDescription(details.productDescription());
    }

    private BigDecimal requireProductPrice(BigDecimal productPrice) {
        if (productPrice == null || productPrice.signum() < 0) {
            throw new GeneralException("Product price cannot be negative");
        }

        if (productPrice.stripTrailingZeros().scale() > MONEY_SCALE) {
            throw new GeneralException("Product price must not exceed two decimal places");
        }

        if (productPrice.compareTo(MAX_MONEY) > 0) {
            throw new GeneralException("Product price must not exceed " + MAX_MONEY.toPlainString());
        }

        return productPrice;
    }

    private int requireProductCount(int productCount) {
        if (productCount < 0) {
            throw new GeneralException("Product count cannot be negative");
        }
        return productCount;
    }

    /**
     * An absent discount means no discount.
     *
     * <p>{@code product_discount} is {@code NOT NULL} and has no default, and the
     * JSON body has always treated the field as optional while the multipart
     * form has always sent it. Normalizing a missing value to zero is the one
     * reading both can honour: a caller that omits the field gets a product
     * with no discount rather than an integrity error, and neither path has to
     * special-case the other.
     */
    private BigDecimal requireProductDiscount(BigDecimal productDiscount) {
        BigDecimal discount = productDiscount == null ? BigDecimal.ZERO : productDiscount;

        if (discount.signum() < 0 || discount.compareTo(MAX_DISCOUNT) > 0) {
            throw new GeneralException("Product discount must be between 0 and 100");
        }

        if (discount.stripTrailingZeros().scale() > MONEY_SCALE) {
            throw new GeneralException("Product discount must not exceed two decimal places");
        }

        return discount;
    }

    private String normalizeRequiredText(String fieldName, String value) {
        if (value == null) {
            throw new GeneralException(fieldName + " is required");
        }

        String normalized = value.trim();
        if (normalized.isEmpty() || "undefined".equalsIgnoreCase(normalized) || "null".equalsIgnoreCase(normalized)) {
            throw new GeneralException(fieldName + " is required");
        }

        requireLength(fieldName, normalized);
        return normalized;
    }

    /**
     * An absent description is the empty string, not a missing one.
     *
     * <p>The column is {@code NOT NULL} and has no default, so a product created
     * without a description has to store something. The empty string is what the
     * multipart path has always stored for an absent value, and reusing it keeps
     * "no description" reading as one thing in the database rather than two.
     */
    private String normalizeOptionalText(String fieldName, String value) {
        if (value == null) {
            return "";
        }

        String normalized = value.trim();
        if ("undefined".equalsIgnoreCase(normalized) || "null".equalsIgnoreCase(normalized)) {
            return "";
        }

        requireLength(fieldName, normalized);
        return normalized;
    }

    private void requireLength(String fieldName, String value) {
        if (value.length() > MAX_TEXT_LENGTH) {
            throw new GeneralException(fieldName + " must not exceed " + MAX_TEXT_LENGTH + " characters");
        }
    }
}
