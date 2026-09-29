package com.example.MigrosBackend.repository.product;

/**
 * Projection for one {@code GROUP BY subcategoryName} row: the subcategory name
 * and how many in-stock products carry it.
 */
public record SubcategoryCount(String subcategoryName, long productCount) {
}
