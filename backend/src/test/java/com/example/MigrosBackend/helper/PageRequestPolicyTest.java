package com.example.MigrosBackend.helper;

import com.example.MigrosBackend.exception.shared.GeneralException;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The single bound every client-supplied page request has to satisfy.
 *
 * <p>The rejection cases matter more than the acceptance ones: a request that
 * is out of range has to be refused before a {@code PageRequest} exists,
 * because {@code PageRequest.of} itself would either throw an
 * {@link IllegalArgumentException} (which is not a mapped 400) or build an
 * unbounded {@code LIMIT}.
 */
class PageRequestPolicyTest {

    @Test
    void acceptsTheZeroBasedFirstPageAtBothSizeBounds() {
        Pageable smallest = PageRequestPolicy.of(0, PageRequestPolicy.MIN_PAGE_SIZE);
        assertEquals(0, smallest.getPageNumber());
        assertEquals(1, smallest.getPageSize());

        Pageable largest = PageRequestPolicy.of(0, PageRequestPolicy.MAX_PAGE_SIZE);
        assertEquals(0, largest.getPageNumber());
        assertEquals(100, largest.getPageSize());
    }

    @Test
    void acceptsAHighPageNumberAsAnEmptyPageRatherThanAClamp() {
        Pageable pageable = PageRequestPolicy.of(99, 10);

        assertEquals(99, pageable.getPageNumber());
        assertEquals(990L, pageable.getOffset());
    }

    @Test
    void rejectsANegativePageInsteadOfClampingItToTheFirstPage() {
        assertThrows(GeneralException.class, () -> PageRequestPolicy.of(-1, 10));
        assertThrows(GeneralException.class, () -> PageRequestPolicy.of(-3, 10));
    }

    @Test
    void rejectsANonPositiveSizeInsteadOfTreatingItAsACountOnlyRequest() {
        assertThrows(GeneralException.class, () -> PageRequestPolicy.of(0, 0));
        assertThrows(GeneralException.class, () -> PageRequestPolicy.of(0, -1));
    }

    @Test
    void rejectsAnOversizedSizeInsteadOfSilentlyResizingIt() {
        assertThrows(GeneralException.class, () -> PageRequestPolicy.of(0, 101));
        assertThrows(GeneralException.class, () -> PageRequestPolicy.of(0, Integer.MAX_VALUE));
    }

    @Test
    void rejectsBeforePageRequestConstruction() {
        // PageRequest.of throws IllegalArgumentException for a negative page or a
        // nonpositive size. Reaching a GeneralException instead proves the
        // validation happened first rather than as a side effect of building it.
        GeneralException ex = assertThrows(GeneralException.class, () -> PageRequestPolicy.of(-1, 0));
        assertTrue(ex.getMessage().contains("page"));

        GeneralException sizeEx = assertThrows(GeneralException.class, () -> PageRequestPolicy.of(0, 0));
        assertTrue(sizeEx.getMessage().contains("size"));
    }

    @Test
    void anUnsortedRequestCarriesNoSortSoAnInQueryOrderSurvives() {
        Pageable pageable = PageRequestPolicy.of(0, 10);

        assertTrue(pageable.getSort().isUnsorted(),
                "a native query with its own ORDER BY must not gain a generated sort");
    }

    @Test
    void productPagesAreOrderedByProductIdAscending() {
        Pageable pageable = PageRequestPolicy.ofProductIdAscending(1, 10);

        assertEquals(1, pageable.getPageNumber());
        assertEquals(10, pageable.getPageSize());
        assertEquals(Sort.by(Sort.Direction.ASC, "id"), pageable.getSort());
    }

    @Test
    void productPagesApplyTheSameBoundsAsEveryOtherRequest() {
        assertThrows(GeneralException.class, () -> PageRequestPolicy.ofProductIdAscending(-1, 10));
        assertThrows(GeneralException.class, () -> PageRequestPolicy.ofProductIdAscending(0, 0));
        assertThrows(GeneralException.class, () -> PageRequestPolicy.ofProductIdAscending(0, 101));
    }
}
