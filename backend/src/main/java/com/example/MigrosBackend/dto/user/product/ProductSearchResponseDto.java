package com.example.MigrosBackend.dto.user.product;

import java.util.List;

/**
 * One page of catalogue search results, with everything the client needs to
 * render the filters and the paginator without a second round trip.
 *
 * <p>{@code items} are the same {@link ProductPreviewDto} the existing catalogue
 * listings return, so a client already rendering those can render this without
 * learning a second product shape.
 *
 * @param items         the products on this page, already carrying the effective
 *                      price a card shows
 * @param totalItems    how many products match every filter, before paging - the
 *                      same predicate the pages walk, not a separately computed
 *                      approximation
 * @param page          the zero-based page index that produced {@code items}
 * @param size          the page size that produced {@code items}
 * @param subcategories the selectable subcategory buckets with their remaining
 *                      counts, empty when no category was requested because there
 *                      is then nothing to switch between
 */
public record ProductSearchResponseDto(List<ProductPreviewDto> items,
                                       long totalItems,
                                       int page,
                                       int size,
                                       List<SubCategoryCountDto> subcategories) {
}
