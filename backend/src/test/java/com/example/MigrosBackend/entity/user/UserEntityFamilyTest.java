package com.example.MigrosBackend.entity.user;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class UserEntityFamilyTest {

    @Test
    void userEntity_usesIdEquality() {
        UserEntity withSameId = new UserEntity();
        withSameId.setId(1L);
        withSameId.setUserMail("first@migros.com");

        UserEntity other = new UserEntity();
        other.setId(1L);
        other.setUserMail("second@migros.com");

        UserEntity differentId = new UserEntity();
        differentId.setId(2L);
        differentId.setUserMail("first@migros.com");

        assertEquals(withSameId, other);
        assertEquals(withSameId.hashCode(), other.hashCode());
        assertNotEquals(withSameId, differentId);
    }

    @Test
    void pendingSignupEntity_usesTokenEquality() {
        PendingSignupEntity withSameToken = new PendingSignupEntity();
        withSameToken.setToken("token-1");
        withSameToken.setUserMail("first@migros.com");

        PendingSignupEntity other = new PendingSignupEntity();
        other.setToken("token-1");
        other.setUserMail("second@migros.com");

        PendingSignupEntity differentToken = new PendingSignupEntity();
        differentToken.setToken("token-2");
        differentToken.setUserMail("first@migros.com");

        assertEquals(withSameToken, other);
        assertEquals(withSameToken.hashCode(), other.hashCode());
        assertNotEquals(withSameToken, differentToken);
    }

    @Test
    void supportMessageEntity_usesIdEquality() {
        SupportMessageEntity withSameId = new SupportMessageEntity();
        withSameId.setId(1L);
        withSameId.setMessage("first");

        SupportMessageEntity other = new SupportMessageEntity();
        other.setId(1L);
        other.setMessage("second");

        SupportMessageEntity differentId = new SupportMessageEntity();
        differentId.setId(2L);
        differentId.setMessage("first");

        assertEquals(withSameId, other);
        assertEquals(withSameId.hashCode(), other.hashCode());
        assertNotEquals(withSameId, differentId);
    }

    @Test
    void supportMessageEntity_toStringNeverContainsMessageText() {
        SupportMessageEntity entity = new SupportMessageEntity();
        entity.setId(7L);
        entity.setUserMail("user@migros.com");
        entity.setSender("USER");
        entity.setMessage("SECRET-CUSTOMER-TEXT");

        assertFalse(entity.toString().contains("SECRET-CUSTOMER-TEXT"));
    }
}
