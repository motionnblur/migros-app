package com.example.MigrosBackend.helper;

import com.example.MigrosBackend.exception.shared.GeneralException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * The one owner of the effective-price arithmetic, shared by catalog/cart
 * display and checkout.
 *
 * <p>This used to be written out twice, once in
 * {@code UserCatalogReadService.getEffectivePrice} and once in
 * {@code CheckoutCalculations.effectiveUnitPrice}. The two copies agreed on the
 * arithmetic and disagreed only about what to do with input they did not like,
 * so every fix to one of them had to be remembered for the other. A customer
 * could therefore be quoted a different price in the cart than the one checkout
 * charged, and nothing in the code said that was possible.
 *
 * <h2>The arithmetic</h2>
 *
 * <p>For a price {@code p} (major units) and a discount {@code d} (whole
 * percent), with {@code H(x, s, m)} meaning {@code x} rounded to {@code s}
 * decimal places with mode {@code m}:
 *
 * <pre>
 *   n     = H(p, 2, HALF_UP)
 *   if d &lt;= 0 : price = n
 *   else        : price = H(n * (1 - H(d / 100, 6, HALF_UP)), 2, HALF_UP)
 * </pre>
 *
 * <p>The discount factor is rounded to six decimals <em>before</em> the
 * multiplication, and the product is rounded to the two decimals of the
 * {@code NUMERIC(19, 2)} money columns. Rounding the factor first is what keeps
 * the displayed price equal to the charged price: both callers now run this one
 * expression, and a later change to the rounding order changes both at once
 * instead of silently splitting them.
 *
 * <p>Nothing here validates. The arithmetic is total, so a caller that has
 * decided a value is acceptable can always price it, and a caller that has not
 * can decide first and then price.
 *
 * <h2>Why validation is separate</h2>
 *
 * <p>Two callers legitimately want different things from a row that is not a
 * valid product, and averaging those into one behaviour is how an invalid
 * payable price becomes a zero-priced checkout:
 *
 * <ul>
 *   <li><b>Checkout</b> owns the charge amount, so it must refuse to price
 *       anything it would not take money for. {@link #requireValidPrice} and
 *       {@link #requireValidDiscount} are that refusal, and a checkout caller is
 *       expected to call them first and pass their results to
 *       {@link #effectivePrice}.</li>
 *   <li><b>Catalog/cart</b> only renders a listing and must not 500 a whole
 *       product page because of one damaged row, so it adapts a missing value to
 *       zero at the boundary instead. That adaptation lives in the caller, named
 *       as a boundary decision, so this class cannot quietly become the lenient
 *       one by default.</li>
 * </ul>
 *
 * <p>Neither caller weakens the other's rules: a value checkout refuses to charge
 * is still refused, and a legacy row that catalog keeps displayable is never
 * priceable at checkout.
 */
public final class ProductPricingPolicy {

    /** The scale of {@code NUMERIC(19, 2)} money columns, and of every price returned here. */
    public static final int MONEY_SCALE = 2;

    /**
     * The scale the discount factor is rounded to before the multiplication.
     *
     * <p>Six decimals is finer than any {@code NUMERIC(19, 2)} discount, so the
     * factor is not what decides the last cent; the final rounding is.
     */
    public static final int DISCOUNT_FACTOR_SCALE = 6;

    /** A discount of 100 percent is the most a product may be reduced by. */
    public static final BigDecimal MAX_DISCOUNT_PERCENT = new BigDecimal("100");

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private ProductPricingPolicy() {
    }

    /**
     * The payable price of one unit: the price normalized to the money scale and
     * then reduced by the discount percentage.
     *
     * <p>Pure arithmetic with no validation and no null tolerance. Both arguments
     * are required: a caller decides what a missing value means (zero, or a
     * refusal) and says so at its own boundary, rather than having that decision
     * inherited from whichever caller happened to need it.
     *
     * <p>A discount of zero or less returns the normalized price unchanged.
     * A negative percentage cannot mean anything, so this method does not
     * reinterpret it: the strict caller rejects it before it arrives, and the
     * lenient caller inherits the "no discount" reading it has always had rather
     * than turning the same row into a discount of its own.
     *
     * @return a value with scale {@link #MONEY_SCALE}
     */
    public static BigDecimal effectivePrice(BigDecimal price, BigDecimal discountPercent) {
        Objects.requireNonNull(price, "price must not be null");
        Objects.requireNonNull(discountPercent, "discountPercent must not be null");

        BigDecimal normalized = price.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        if (discountPercent.signum() <= 0) {
            return normalized;
        }
        BigDecimal factor = BigDecimal.ONE.subtract(
                discountPercent.divide(HUNDRED, DISCOUNT_FACTOR_SCALE, RoundingMode.HALF_UP));
        return normalized.multiply(factor).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * A price a checkout may charge: present, not negative, and no finer than the
     * money scale.
     *
     * <p>Zero is allowed because the column allows it; the charge that follows
     * is what refuses a zero total, in {@code PaymentAmountConverter}.
     *
     * <p>The precision check reads the value as written rather than after
     * rounding, so {@code 10.001} is rejected instead of being quietly billed as
     * {@code 10.00}. The message is part of the existing API contract and is
     * unchanged.
     *
     * @throws GeneralException if the price cannot be charged
     */
    public static BigDecimal requireValidPrice(BigDecimal price, String productName) {
        if (price == null || price.signum() < 0 || price.stripTrailingZeros().scale() > MONEY_SCALE) {
            throw new GeneralException("Product has an invalid price: " + productName);
        }
        return price;
    }

    /**
     * A discount a checkout may apply, with an absent discount meaning none.
     *
     * <p>Returning {@link BigDecimal#ZERO} for a missing discount is the
     * established reading of the optional field (see
     * {@code ProductCreationPolicy.requireProductDiscount}) and is not a
     * fallback for an invalid one: a negative or over-100 percent discount is
     * still rejected.
     *
     * @throws GeneralException if the discount is negative or exceeds 100 percent
     */
    public static BigDecimal requireValidDiscount(BigDecimal discountPercent, String productName) {
        BigDecimal discount = discountPercent == null ? BigDecimal.ZERO : discountPercent;
        if (discount.signum() < 0 || discount.compareTo(MAX_DISCOUNT_PERCENT) > 0) {
            throw new GeneralException("Product has an invalid discount: " + productName);
        }
        return discount;
    }
}
