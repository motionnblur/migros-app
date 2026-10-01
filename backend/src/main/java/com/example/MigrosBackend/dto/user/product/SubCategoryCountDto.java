package com.example.MigrosBackend.dto.user.product;

/**
 * One selectable subcategory bucket in a catalogue search response.
 *
 * <p>Two fields, deliberately. {@code SubCategoryDto} also carries a
 * {@code subCategoryId}, which is populated with the <em>category's</em> id - the
 * catalogue has no subcategory table, so there is no id to report and the field
 * only invites a caller to filter by something that identifies the category
 * instead. This response is additive and gets its own type rather than reusing a
 * shape whose id field means something else.
 *
 * @param subCategoryName the subcategory name, matching
 *                        {@code product_entity.subcategory_name} exactly
 * @param productCount    how many products the other active filters would leave
 *                        in this bucket, ignoring the selected subcategory so the
 *                        figures stay comparable
 */
public record SubCategoryCountDto(String subCategoryName, long productCount) {
}
