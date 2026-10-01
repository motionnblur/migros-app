package com.example.MigrosBackend.helper;

import java.util.List;
import java.util.Locale;

/**
 * The measures a package amount may be counted in.
 *
 * <p>A closed set rather than free text, and the reason is arithmetic, not
 * tidiness: {@link ProductUnitPricePolicy} divides a package price by a package
 * size, and the unit is what tells it what the quotient means. A unit nobody
 * defined has no basis to be priced per, so it must be one of these or it must
 * not be stored - a value such as {@code "GRAM"} or a Turkish spelling of
 * {@code KG} would otherwise be accepted here and produce a number nobody could
 * check, or a division by a factor nobody chose.
 *
 * <p>{@link #parse(String)} is the single normalizer, so the stored spelling and
 * the accepted spelling cannot drift: what
 * {@link com.example.MigrosBackend.service.admin.supply.ProductCreationPolicy}
 * writes and what this reads are the same tokens by construction.
 */
public enum ProductPackageUnit {

    /** Gram. A thousandth of the {@link #KG} basis. */
    G(ProductPriceBasis.KG, 1_000),
    /** Kilogram. Already the {@link ProductPriceBasis#KG} basis. */
    KG(ProductPriceBasis.KG, 1),
    /** Millilitre. A thousandth of the {@link #L} basis. */
    ML(ProductPriceBasis.L, 1_000),
    /** Litre. Already the {@link ProductPriceBasis#L} basis. */
    L(ProductPriceBasis.L, 1),
    /**
     * A countable item - an egg, a pack of biscuits - so the basis is the item
     * itself and no unit conversion applies.
     *
     * <p>Also the one unit whose amount must be a whole number: a product cannot
     * be sold as 1.5 eggs.
     */
    ADET(ProductPriceBasis.ADET, 1);

    private final ProductPriceBasis unitPriceBasis;
    private final int unitsPerBasis;

    ProductPackageUnit(ProductPriceBasis unitPriceBasis, int unitsPerBasis) {
        this.unitPriceBasis = unitPriceBasis;
        this.unitsPerBasis = unitsPerBasis;
    }

    /**
     * The measure a customer compares prices in, which is not the same as the
     * measure a package happens to be sold in.
     *
     * <p>This is why the unit is stored rather than derived: 500 {@code G} and
     * 0.5 {@code KG} describe the same package and both have to be comparable per
     * {@code KG}.
     */
    public ProductPriceBasis unitPriceBasis() {
        return unitPriceBasis;
    }

    /** How many of this unit make up one basis unit: 1000 for G and ML, 1 otherwise. */
    public int unitsPerBasis() {
        return unitsPerBasis;
    }

    /**
     * The accepted reading of a submitted token, or {@code null} when it is not
     * one.
     *
     * <p>Trims and upper-cases first, because an administrator typing
     * {@code " kg"} into a free-text field means kilograms and rejecting that would
     * be a message about whitespace rather than about the value. It returns
     * {@code null} rather than throwing, because both callers need that: the
     * creation policy reports an unsupported unit in the message the
     * administrator sees, and the unit-price arithmetic must produce nothing
     * rather than raise while rendering a catalogue page.
     */
    public static ProductPackageUnit parse(String raw) {
        if (raw == null) {
            return null;
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        if (normalized.isEmpty() || "UNDEFINED".equals(normalized) || "NULL".equals(normalized)) {
            return null;
        }
        for (ProductPackageUnit unit : values()) {
            if (unit.name().equals(normalized)) {
                return unit;
            }
        }
        return null;
    }

    /** Every accepted token, in the order the admin form offers them. */
    public static List<ProductPackageUnit> selectable() {
        return List.of(values());
    }
}
