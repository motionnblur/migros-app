package com.example.MigrosBackend.helper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The unit vocabulary, which is small and load-bearing.
 *
 * <p>Every accepted spelling funnels through {@link ProductPackageUnit#parse}, so
 * the token the admin form offers, the token the policy stores and the token the
 * unit-price arithmetic reads are one set. A second spelling list anywhere is how
 * a unit the form offers ends up being one the server rejects.
 */
class ProductPackageUnitTest {

    @ParameterizedTest
    @ValueSource(strings = {"G", "KG", "ML", "L", "ADET"})
    void acceptsEveryDefinedUnit(String token) {
        assertSame(ProductPackageUnit.valueOf(token), ProductPackageUnit.parse(token));
    }

    @ParameterizedTest(name = "\"{0}\" normalizes to {1}")
    @CsvSource({
            "'kg', KG",
            "' KG ', KG",
            "'Kg', KG",
            "'kG', KG",
            "'g', G",
            "'ml', ML",
            "'  ML  ', ML",
            "'l', L",
            "'adet', ADET",
            "'Adet', ADET"
    })
    void normalizesCaseAndPaddingBecauseAnAdministratorTypesThem(String raw, String expected) {
        assertSame(ProductPackageUnit.valueOf(expected), ProductPackageUnit.parse(raw));
    }

    /**
     * The two values a form submits when the administrator has not chosen a unit.
     *
     * <p>Both mean "absent" and neither may become a unit: an empty string that
     * parsed to something would give a half-filled pair a measure, and the half
     * filled pair is exactly what the policy refuses.
     */
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   ", "undefined", "null", "UNDEFINED", "NULL"})
    void treatsEveryEmptySpellingAsNoUnit(String raw) {
        assertNull(ProductPackageUnit.parse(raw));
    }

    /**
     * Anything outside the closed set, including units that are real measurements
     * but not ones the system can price per.
     */
    @ParameterizedTest
    @ValueSource(strings = {"GRAM", "KILOGRAM", "LITRE", "KG.", "1 KG", "pcs", "adet."})
    void refusesAUnitOutsideTheClosedSet(String raw) {
        assertNull(ProductPackageUnit.parse(raw),
                raw + " is not one of the accepted measures, and a measure nobody defined "
                        + "has no basis to be priced per");
    }

    /**
     * Only the five are offered, and they are offered whole.
     */
    @Test
    void offersExactlyTheAcceptedUnits() {
        List<ProductPackageUnit> selectable = ProductPackageUnit.selectable();

        assertEquals(List.of(ProductPackageUnit.G, ProductPackageUnit.KG, ProductPackageUnit.ML,
                ProductPackageUnit.L, ProductPackageUnit.ADET), selectable);
    }

    /**
     * The many-to-one mapping, which is the whole reason the basis is stored
     * separately from the unit.
     */
    @Test
    void mapsEveryUnitToTheMeasureCustomersCompareIn() {
        assertEquals(ProductPriceBasis.KG, ProductPackageUnit.G.unitPriceBasis());
        assertEquals(ProductPriceBasis.KG, ProductPackageUnit.KG.unitPriceBasis());
        assertEquals(ProductPriceBasis.L, ProductPackageUnit.ML.unitPriceBasis());
        assertEquals(ProductPriceBasis.L, ProductPackageUnit.L.unitPriceBasis());
        assertEquals(ProductPriceBasis.ADET, ProductPackageUnit.ADET.unitPriceBasis());
    }

    /**
     * A thousand small units make one basis unit, and only the small ones do.
     */
    @Test
    void scalesOnlyTheSubUnitMeasures() {
        assertEquals(1_000, ProductPackageUnit.G.unitsPerBasis());
        assertEquals(1_000, ProductPackageUnit.ML.unitsPerBasis());
        assertEquals(1, ProductPackageUnit.KG.unitsPerBasis());
        assertEquals(1, ProductPackageUnit.L.unitsPerBasis());
        assertEquals(1, ProductPackageUnit.ADET.unitsPerBasis());
    }

/**
 * The discrete unit is the only one whose amount must be a whole number, and the
 * reason is physical: half an egg is not a thing that can be sold, while half a
 * litre is.
 *
 * <p>Asserted on the basis rather than on a rule flag, because the rule itself
 * lives with the thing that has to enforce it - the creation policy, which owns
 * every write to both columns.
 */
@Test
void treatsTheItemUnitAsTheOnlyDiscreteOne() {
    assertEquals(ProductPriceBasis.ADET, ProductPackageUnit.ADET.unitPriceBasis());
    assertEquals(1, ProductPackageUnit.ADET.unitsPerBasis(),
            "a count is already its own basis, so no conversion applies");
}
}

