package com.example.MigrosBackend.entity.admin;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class AdminEntityTest {

    @Test
    void adminEntity_usesIdEquality() {
        AdminEntity withSameId = new AdminEntity();
        withSameId.setId(1L);
        withSameId.setAdminName("first");

        AdminEntity other = new AdminEntity();
        other.setId(1L);
        other.setAdminName("second");

        AdminEntity differentId = new AdminEntity();
        differentId.setId(2L);
        differentId.setAdminName("first");

        assertEquals(withSameId, other);
        assertEquals(withSameId.hashCode(), other.hashCode());
        assertNotEquals(withSameId, differentId);
    }
}
