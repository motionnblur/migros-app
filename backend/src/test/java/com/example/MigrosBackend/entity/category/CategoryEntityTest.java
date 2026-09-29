package com.example.MigrosBackend.entity.category;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class CategoryEntityTest {

    @Test
    void categoryEntity_usesIdEquality() {
        CategoryEntity withSameId = new CategoryEntity();
        withSameId.setId(1L);
        withSameId.setCategoryName("Fruit");

        CategoryEntity other = new CategoryEntity();
        other.setId(1L);
        other.setCategoryName("Vegetables");

        CategoryEntity differentId = new CategoryEntity();
        differentId.setId(2L);
        differentId.setCategoryName("Fruit");

        assertEquals(withSameId, other);
        assertEquals(withSameId.hashCode(), other.hashCode());
        assertNotEquals(withSameId, differentId);
    }
}
