package com.example.MigrosBackend.entity.product;

import jakarta.persistence.Column;
import jakarta.persistence.Version;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ProductEntityFamilyTest {

    /**
     * The edit version must be mapped, not merely present as a field.
     *
     * <p>The whole feature rests on two mappings working together: {@code
     * @Version} makes every JPA-managed product write advance the column, and the
     * explicit column name is what the V11 migration and the bulk stock
     * increment both refer to. A field that lost either annotation would compile
     * and still let a stale edit through.
     */
    @Test
    void productEntity_mapsTheEditVersionAsAJpaVersion() throws NoSuchFieldException {
        Field version = ProductEntity.class.getDeclaredField("version");

        assertNotNull(version.getAnnotation(Version.class),
                "without @Version a managed product write would not advance the edit version");
        Column column = version.getAnnotation(Column.class);
        assertNotNull(column);
        assertEquals("version", column.name());
        assertFalse(column.nullable(), "a nullable version would never compare equal");
    }

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
