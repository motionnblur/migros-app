package com.example.MigrosBackend.helper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * The one owner of the "price per kg / litre / item" arithmetic.
 *
 * <p>The package price is the number a customer is charged, and it is not the
 * number two products are compared on: a 45 TL bottle and a 45 TL sack are not
 * comparable, and a price band filter over package prices cannot compare them
 * either. This class exists so the comparison is one division in one place rather
 * than a formula in a card, a detail page and a hypothetical export.
 *
 * <h2>The arithmetic</h2>
 *
 * <p>For an effective package price {@code p} (from
 * {@link ProductPricingPolicy#effectivePrice}), an amount {@code a} and a unit
 * {@code u}:
 *
 * <pre>
 *   basis   = u.unitPriceBasis()                     KG | L | ADET
 *   a'      = a / u.unitsPerBasis()                 1000 for G and ML, 1 otherwise
 *   unitPrice = H(p / a', 2, HALF_UP)
 * </pre>
 *
 * <p>The conversion happens <em>before</em> the division, so 500 {@code G} and
 * 0.5 {@code KG} produce the same answer instead of one of them being off by a
 * factor of a thousand - the same "normalize the factor first, then divide" rule
 * {@link ProductPricingPolicy} already applies to the discount factor, and for the
 * same reason: it is what makes the displayed number agree with the number that
 * was measured.
 *
 * <p>Rounding is {@link ProductPricingPolicy#MONEY_SCALE} decimals, {@code HALF_UP},
 * so the unit price is a money figure like every other price in the system.
 *
 * <h2>When there is no answer</h2>
 *
 * <p>This returns {@code null} rather than a number whenever it cannot compute an
 * honest one, and the whole point is that it has several such cases:
 *
 * <ul>
 *   <li>No package metadata. Most of the catalogue has none, and a price per kg
 *       without a size is a guess.</li>
 *   <li>An amount of zero or less, which would be a division by zero. Application
 *       validation refuses to store one, and this refuses to render one, so a row
 *       that somehow holds it cannot 500 a whole catalogue page.</li>
 *   <li>A unit outside the closed set, which may only happen for a row written
 *       outside the creation policy. Deriving a basis from it would be inventing
 *       one.</li>
 * </ul>
 *
 * <p>A caller therefore cannot accidentally display a computed value for a row
 * that has none: absence is an absent field in the DTO, and the client hides the
 * line. It also means the computation is total - it never throws while a listing
 * is being rendered - which is the property
 * {@link com.example.MigrosBackend.service.user.supply.UserCatalogReadService}
 * needs from it.
 */
public final class ProductUnitPricePolicy {

    /**
     * A unit price and the measure it is per, in the pair they have to travel in.
     *
     * <p>Never constructed with a null amount: an absent unit price is an absent
     * {@link #unitPrice} result, not a record holding a null.
     */
    public record UnitPrice(BigDecimal amount, ProductPriceBasis basis) {
        public UnitPrice {
            Objects.requireNonNull(amount, "amount must not be null");
            Objects.requireNonNull(basis, "basis must not be null");
        }
    }

    private static final BigDecimal THOUSAND = BigDecimal.valueOf(1_000);

    /**
     * The scale of {@code product_entity.package_amount}.
     *
     * <p>Used to size the converted amount so 500 {@code G} becomes exactly
     * 0.500 rather than a value whose precision depends on how the caller happened
     * to write it. Dividing with an explicit scale never rounds a non-terminating
     * quotient into an exception, so this is safe for every input.
     */
    private static final int PACKAGE_SCALE = 3;

    private ProductUnitPricePolicy() {
    }

    /**
     * The price of one basis unit of this package, or {@code null} when the product
     * carries no package size or the size cannot be used.
     *
     * @param effectivePrice the price of one package, already reduced by any
     *                        discount - the same number the card shows and
     *                        checkout charges, so a unit price can never describe a
     *                        different price than the one being paid
     * @param packageAmount  how much is in one package, or {@code null}
     * @param packageUnit    the stored unit token, or {@code null}
     */
    public static UnitPrice unitPrice(BigDecimal effectivePrice,
                                      BigDecimal packageAmount,
                                      String packageUnit) {
        if (effectivePrice == null || packageAmount == null) {
            return null;
        }
        // Refused rather than clamped. A zero amount has no quotient, and a
        // negative one would price a package backwards - either way the number on
        // screen would be a rendering artefact rather than a fact about the
        // product.
        if (packageAmount.signum() <= 0) {
            return null;
        }

        ProductPackageUnit unit = ProductPackageUnit.parse(packageUnit);
        if (unit == null) {
            return null;
        }

        BigDecimal amountInBasis = unit.unitsPerBasis() == 1
                ? packageAmount
                : packageAmount.divide(THOUSAND, PACKAGE_SCALE, RoundingMode.HALF_UP);

        if (amountInBasis.signum() <= 0) {
            return null;
        }

        BigDecimal unitPrice = effectivePrice
                .divide(amountInBasis, ProductPricingPolicy.MONEY_SCALE, RoundingMode.HALF_UP);

        return new UnitPrice(unitPrice, unit.unitPriceBasis());
    }
}
