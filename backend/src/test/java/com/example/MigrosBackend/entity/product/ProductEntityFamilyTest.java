package com.example.MigrosBackend.entity.product;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class ProductEntityFamilyTest {

    @Test
    void productEntity_usesIdEquality() {
        ProductEntity withSameId = new ProductEntity();
        withSameId.setId(1L);
        withSameId.setProductName("different name");

        ProductEntity other = new ProductEntity();
        other.setId(1L);
        other.setProductName("same name");

        ProductEntity differentId = new ProductEntity();
        differentId.setId(2L);
        differentId.setProductName("same name");

        assertEquals(withSameId, other);
        assertEquals(withSameId.hashCode(), other.hashCode());
        assertNotEquals(withSameId, differentId);
    }

    @Test
    void productEntity_nullIdsUseIdentityEquality() {
        ProductEntity first = new ProductEntity();
        ProductEntity second = new ProductEntity();

        assertNotEquals(first, second);
        assertEquals(first, first);
    }

    @Test
    void productImageEntity_usesIdEquality() {
        ProductImageEntity withSameId = new ProductImageEntity();
        withSameId.setId(1L);
        withSameId.setImagePath("a.png");

        ProductImageEntity other = new ProductImageEntity();
        other.setId(1L);
        other.setImagePath("b.png");

        ProductImageEntity differentId = new ProductImageEntity();
        differentId.setId(2L);
        differentId.setImagePath("a.png");

        assertEquals(withSameId, other);
        assertEquals(withSameId.hashCode(), other.hashCode());
        assertNotEquals(withSameId, differentId);
    }

    @Test
    void productDescriptionEntity_usesIdEquality() {
        ProductDescriptionEntity withSameId = new ProductDescriptionEntity();
        withSameId.setId(1L);
        withSameId.setDescriptionTabName("tab");

        ProductDescriptionEntity other = new ProductDescriptionEntity();
        other.setId(1L);
        other.setDescriptionTabName("other tab");

        ProductDescriptionEntity differentId = new ProductDescriptionEntity();
        differentId.setId(2L);
        differentId.setDescriptionTabName("tab");

        assertEquals(withSameId, other);
        assertEquals(withSameId.hashCode(), other.hashCode());
        assertNotEquals(withSameId, differentId);
    }
}
