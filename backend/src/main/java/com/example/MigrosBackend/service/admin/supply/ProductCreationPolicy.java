package com.example.MigrosBackend.service.admin.supply;

import com.example.MigrosBackend.entity.admin.AdminEntity;
import com.example.MigrosBackend.entity.category.CategoryEntity;
import com.example.MigrosBackend.entity.product.ProductEntity;
import com.example.MigrosBackend.exception.shared.GeneralException;
import com.example.MigrosBackend.helper.ProductPackageUnit;
import com.example.MigrosBackend.helper.ProductPricingPolicy;
import com.example.MigrosBackend.helper.ProductUnitPricePolicy;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.stream.Collectors;

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
 *   <li>{@code package_amount} is {@code NUMERIC(12, 3)}, because a package is
 *       measured rather than priced: nine integer digits and up to three decimals
 *       for a dose in millilitres or a spice in grams. The same reasoning applies
 *       - a quantity the driver would round is a value the administrator never
 *       typed.</li>
 * </ul>
 *
 * <h2>Package metadata</h2>
 *
 * <p>The two package columns are optional and are treated as one fact. Both or
 * neither, because "how much is in the package" is not answerable with only one of
 * them, and a half-filled pair would hand {@link ProductUnitPricePolicy} a size with
 * no measure. When present, the amount is positive and the unit is one of the closed
 * set in {@link ProductPackageUnit}, because the unit is what turns a package price
 * into a comparable price per kilogram, litre or item - and a unit nobody defined
 * cannot be divided by.
 *
 * <p>Absent stays absent. No value is derived from the product name or the
 * description: a guessed quantity produces a unit price that looks authoritative and
 * is wrong, which is worse than showing nothing, and it is why V14 left every
 * pre-existing row null instead of backfilling it.
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

    /** The scale of {@code NUMERIC(12, 3)} package-amount columns. */
    static final int PACKAGE_AMOUNT_SCALE = 3;

    /** The precision of {@code NUMERIC(12, 3)} package-amount columns. */
    static final int PACKAGE_AMOUNT_PRECISION = 12;

    /** Integer digits available in a {@code NUMERIC(12, 3)} package-amount column. */
    static final int PACKAGE_AMOUNT_INTEGER_DIGITS =
            PACKAGE_AMOUNT_PRECISION - PACKAGE_AMOUNT_SCALE;

    /** The precision of {@code NUMERIC(19, 2)} money columns. */
    static final int MONEY_PRECISION = 19;

    /** Integer digits available in a {@code NUMERIC(19, 2)} money column. */
    static final int MONEY_INTEGER_DIGITS = MONEY_PRECISION - MONEY_SCALE;

    private static final BigDecimal MAX_MONEY =
            BigDecimal.TEN.pow(MONEY_INTEGER_DIGITS).subtract(BigDecimal.ONE.movePointLeft(MONEY_SCALE));

    private static final BigDecimal MAX_DISCOUNT = new BigDecimal("100");

    /**
     * The largest amount {@code NUMERIC(12, 3)} holds: nine integer digits and three
     * decimals, so {@code 999999999.999}.
     */
    private static final BigDecimal MAX_PACKAGE_AMOUNT =
            BigDecimal.TEN.pow(PACKAGE_AMOUNT_INTEGER_DIGITS)
                    .subtract(BigDecimal.ONE.movePointLeft(PACKAGE_AMOUNT_SCALE));

    /**
     * The accepted unit tokens, rendered once so the rejection message names the
     * real list instead of repeating it.
     *
     * <p>Built from {@link ProductPackageUnit} rather than written out, so adding a
     * unit cannot leave this message offering an option the policy rejects.
     */
    private static final String ACCEPTED_PACKAGE_UNITS =
            ProductPackageUnit.selectable().stream()
                    .map(ProductPackageUnit::name)
                    .collect(Collectors.joining(", ", "[", "]"));

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

        BigDecimal packageAmount = normalizePackageAmount(raw.packageAmount());
        String packageUnit = normalizePackageUnit(raw.packageUnit());
        requirePackageMetadataIsPaired(packageAmount, packageUnit);
        requireWholeCountAmount(packageAmount, packageUnit);

        return ProductDetails.of(productName, subCategoryName, productPrice, productCount,
                productDiscount, productDescription, packageAmount, packageUnit);
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
     *
     * <p>This is also the single place {@code effective_price} is written, which
     * is what makes it the single place. The search path filters and orders by
     * the price a product card shows, and it can only do that in the database;
     * so the stored number has to come from the same
     * {@link ProductPricingPolicy} that renders the card, and this is the one
     * funnel every create and edit path already goes through. Setting it here
     * rather than in each of the three call sites is what keeps a third
     * hypothetical writer from forgetting it and leaving a row whose sort key is
     * null or stale - a product that sorts and filters differently from the
     * price it is displayed at, which is the exact defect the column removes.
     *
     * <p>{@code effective_price} is derived from the two columns being written
     * here, so it never adds dirtiness on its own: when neither price nor
     * discount moved, it is recomputed to a byte-identical value and the genuine
     * no-op edit still dirties nothing, which is what keeps the {@code @Version}
     * behaviour documented on {@link ProductEntity#version}.
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
        productEntity.setEffectivePrice(
                ProductPricingPolicy.effectivePrice(details.productPrice(), details.productDiscount()));
        productEntity.setProductDescription(details.productDescription());
        productEntity.setPackageAmount(details.packageAmount());
        productEntity.setPackageUnit(details.packageUnit());
    }

    /**
     * The package amount, or {@code null} when the product has no package size.
     *
     * <p>Absent is a legitimate reading and is stored as {@code null}, not as
     * zero and not as a value guessed from the product name: the overwhelming
     * majority of rows predate package metadata, and a guessed quantity produces a
     * unit price that looks authoritative and is not.
     *
     * <p>The bounds are the column's own, so an amount the driver would round or
     * overflow is a 400 the administrator can act on instead:
     *
     * <ul>
     *   <li>Positive. Zero has no unit price (it is a division by zero) and a
     *       negative one prices a package backwards.</li>
     *   <li>At most three decimals. {@code NUMERIC(12, 3)} rounds silently on the
     *       values it accepts, so 0.0005 would be stored as something the
     *       administrator never typed.</li>
     *   <li>At most {@code 999999999.999}, which is more than any real package
     *       and exists only so the rejection is a message rather than an
     *       integrity error.</li>
     * </ul>
     */
    private BigDecimal normalizePackageAmount(BigDecimal rawPackageAmount) {
        if (rawPackageAmount == null) {
            return null;
        }

        if (rawPackageAmount.signum() <= 0) {
            throw new GeneralException("Package amount must be greater than zero");
        }

        if (rawPackageAmount.stripTrailingZeros().scale() > PACKAGE_AMOUNT_SCALE) {
            throw new GeneralException("Package amount must not exceed three decimal places");
        }

        if (rawPackageAmount.compareTo(MAX_PACKAGE_AMOUNT) > 0) {
            throw new GeneralException(
                    "Package amount must not exceed " + MAX_PACKAGE_AMOUNT.toPlainString());
        }

        return rawPackageAmount;
    }

    /**
     * The canonical unit token, or {@code null} when the product has no package
     * size.
     *
     * <p>Delegated to {@link ProductPackageUnit} so the accepted spelling and the
     * stored spelling are one decision. Anything outside the closed set is
     * refused rather than stored: the unit is what tells
     * {@link ProductUnitPricePolicy} what the quotient means, so a unit nobody
     * defined is a unit price nobody could check.
     */
    private String normalizePackageUnit(String rawPackageUnit) {
        if (rawPackageUnit == null) {
            return null;
        }
        if (rawPackageUnit.isBlank()) {
            return null;
        }
        ProductPackageUnit unit = ProductPackageUnit.parse(rawPackageUnit);
        if (unit == null) {
            throw new GeneralException(
                    "Package unit must be one of " + ACCEPTED_PACKAGE_UNITS);
        }
        return unit.name();
    }

    /**
     * Both package columns or neither.
     *
     * <p>An amount with no unit is a size in an unnamed measure, which is not a
     * smaller fact than an amount with a wrong unit - it is no fact at all, and it
     * is what a half-filled admin form produces. A unit with no amount has no
     * denominator at all. Rejecting the half-filled pair here means the catalog
     * can treat "has package metadata" as a single boolean, which is what lets the
     * listing show one line or none and never a partial one.
     */
    private void requirePackageMetadataIsPaired(BigDecimal packageAmount, String packageUnit) {
        if ((packageAmount == null) != (packageUnit == null)) {
            throw new GeneralException("Package amount and package unit must be provided together");
        }
    }

    /**
     * ADET counts discrete items, so its amount cannot be fractional.
     *
     * <p>Checked here rather than only in the {@code chk_product_package_whole_adet}
     * constraint because a rejection the administrator sees beats a 500 from the
     * driver. This is the one unit-specific rule; a gram or a litre is continuous
     * and stays unrounded.
     */
    private void requireWholeCountAmount(BigDecimal packageAmount, String packageUnit) {
        if (packageAmount == null || packageUnit == null) {
            return;
        }
        if (ProductPackageUnit.ADET.name().equals(packageUnit)
                && packageAmount.stripTrailingZeros().scale() > 0) {
            throw new GeneralException("Package amount must be a whole number when the unit is ADET");
        }
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
