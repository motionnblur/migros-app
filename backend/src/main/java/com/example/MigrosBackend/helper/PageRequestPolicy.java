package com.example.MigrosBackend.helper;

import com.example.MigrosBackend.exception.shared.GeneralException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * The single bound every client-supplied page request has to satisfy.
 *
 * <p>Two things went wrong before this existed. {@code PageRequest.of} was
 * reached with values taken straight from the query string, so a negative page
 * became an {@link IllegalArgumentException} and an oversized size turned one
 * HTTP request into an unbounded {@code LIMIT}; the admin order read papered
 * over the same input by silently clamping it instead of rejecting it, so a
 * caller that asked for a page it did not get could not tell it apart from one
 * it did. And a page request with no {@code Sort} has no {@code ORDER BY} at
 * all, so two reads of unchanged rows could return the same rows in different
 * orders and an adjacent page window could repeat or skip a row.
 *
 * <p>The bounds are deliberately narrow: page numbers are zero based and never
 * negative, and a page holds between {@value #MIN_PAGE_SIZE} and
 * {@value #MAX_PAGE_SIZE} rows. An out-of-range request is rejected rather than
 * quietly resized, because silently serving a different page size than the one
 * asked for is indistinguishable from serving the wrong data.
 *
 * <p>Rejection throws {@link GeneralException}, which
 * {@code GlobalExceptionHandler} already maps to HTTP 400. The HTTP layer adds
 * the matching {@code @Min}/{@code @Max}/{@code @PositiveOrZero} constraints so
 * the same rejection also reaches the caller as a structured
 * {@code ValidationErrorDto} naming the offending parameter; this class is the
 * backstop for every other caller, including direct service use.
 */
public final class PageRequestPolicy {

    /** Page numbers are zero based, so the only invalid page is a negative one. */
    public static final int MIN_PAGE_NUMBER = 0;

    public static final int MIN_PAGE_SIZE = 1;

    public static final int MAX_PAGE_SIZE = 100;

    /**
     * The stable order for product listings: primary key ascending.
     *
     * <p>None of the product page queries carries an explicit business sort, so
     * the primary key is both the ordering and its own unique tiebreaker. A
     * query that already sorts for a business reason must keep that sort and
     * append {@code id} as the tiebreaker rather than replacing it.
     */
    public static final Sort PRODUCT_ID_ASCENDING = Sort.by(Sort.Direction.ASC, "id");

    private PageRequestPolicy() {
    }

    /**
     * A bounded, unsorted page request.
     *
     * <p>Unsorted is only correct for a query that already orders its own
     * result inside the database, such as the native admin order union, whose
     * {@code ORDER BY order_id DESC, source_rank ASC} must not be replaced by a
     * generated sort on a column the derived projection does not select.
     */
    public static Pageable of(int page, int size) {
        return PageRequest.of(requirePage(page), requireSize(size), Sort.unsorted());
    }

    /**
     * A bounded page request with an explicit stable order.
     */
    public static Pageable of(int page, int size, Sort sort) {
        Sort resolved = sort == null ? Sort.unsorted() : sort;
        return PageRequest.of(requirePage(page), requireSize(size), resolved);
    }

    /**
     * A bounded product page request ordered by product id ascending.
     */
    public static Pageable ofProductIdAscending(int page, int size) {
        return PageRequest.of(requirePage(page), requireSize(size), PRODUCT_ID_ASCENDING);
    }

    public static int requirePage(int page) {
        if (page < MIN_PAGE_NUMBER) {
            throw new GeneralException("page must be greater than or equal to " + MIN_PAGE_NUMBER);
        }
        return page;
    }

    public static int requireSize(int size) {
        if (size < MIN_PAGE_SIZE || size > MAX_PAGE_SIZE) {
            throw new GeneralException("size must be between " + MIN_PAGE_SIZE + " and " + MAX_PAGE_SIZE + " inclusive");
        }
        return size;
    }
}
