package com.example.MigrosBackend.helper;

/**
 * What a unit price is measured against - the "per" in "price per kg".
 *
 * <p>Three values, because a customer can only meaningfully divide a price by a
 * mass, a volume, or a count. Anything else - per package, per shelf, per centimetre
 * - is the package price again under a longer name, which is why a "per package"
 * unit price is deliberately not offered: it would be a second way of showing the
 * main price and would make the two look like independent facts.
 *
 * <p>Kept separate from {@link ProductPackageUnit} because the mapping is many to
 * one ({@code G} and {@code KG} both price per {@link #KG}) and the display
 * contract has to name the basis, not the stored unit. Jackson serializes this by
 * name, so the API carries exactly {@code KG}, {@code L} or {@code ADET}.
 */
public enum ProductPriceBasis {

    /** Per kilogram. */
    KG,
    /** Per litre. */
    L,
    /** Per item. */
    ADET
}
