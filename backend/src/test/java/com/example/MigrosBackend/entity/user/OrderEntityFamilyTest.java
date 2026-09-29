package com.example.MigrosBackend.entity.user;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class OrderEntityFamilyTest {

    @Test
    void orderEntity_usesIdEquality() {
        OrderEntity withSameId = new OrderEntity();
        withSameId.setId(1L);
        withSameId.setStatus("Pending");

        OrderEntity other = new OrderEntity();
        other.setId(1L);
        other.setStatus("Shipped");

        OrderEntity differentId = new OrderEntity();
        differentId.setId(2L);
        differentId.setStatus("Pending");

        assertEquals(withSameId, other);
        assertEquals(withSameId.hashCode(), other.hashCode());
        assertNotEquals(withSameId, differentId);
    }

    @Test
    void orderGroupEntity_usesIdEquality() {
        OrderGroupEntity withSameId = new OrderGroupEntity();
        withSameId.setId(1L);
        withSameId.setStatus("Pending");

        OrderGroupEntity other = new OrderGroupEntity();
        other.setId(1L);
        other.setStatus("Shipped");

        OrderGroupEntity differentId = new OrderGroupEntity();
        differentId.setId(2L);
        differentId.setStatus("Pending");

        assertEquals(withSameId, other);
        assertEquals(withSameId.hashCode(), other.hashCode());
        assertNotEquals(withSameId, differentId);
    }
}
