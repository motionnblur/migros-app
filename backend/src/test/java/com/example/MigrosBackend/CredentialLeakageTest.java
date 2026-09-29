package com.example.MigrosBackend;

import com.example.MigrosBackend.dto.admin.sign.AdminSignDto;
import com.example.MigrosBackend.dto.user.sign.ResetPasswordDto;
import com.example.MigrosBackend.dto.user.sign.UserSignDto;
import com.example.MigrosBackend.entity.admin.AdminEntity;
import com.example.MigrosBackend.entity.user.PendingSignupEntity;
import com.example.MigrosBackend.entity.user.PendingTokenPurpose;
import com.example.MigrosBackend.entity.user.UserEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Credential-equivalent values must never reach a log line or a JSON response
 * through an object's default rendering.
 *
 * <p>Every assertion uses a distinctive sentinel value, so a passing test
 * proves the value itself is absent rather than that some unrelated field name
 * changed.
 */
class CredentialLeakageTest {

    private static final String SECRET = "S3cr3t-H4sh-Value";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void userEntityToStringDoesNotContainThePassword() {
        UserEntity user = new UserEntity();
        user.setUserMail("user@migros.com");
        user.setUserPassword(SECRET);

        assertFalse(user.toString().contains(SECRET));
    }

    @Test
    void adminEntityToStringDoesNotContainThePassword() {
        AdminEntity admin = new AdminEntity();
        admin.setAdminName("admin");
        admin.setAdminPassword(SECRET);

        assertFalse(admin.toString().contains(SECRET));
    }

    @Test
    void pendingSignupEntityToStringDoesNotContainTheTokenOrPassword() {
        PendingSignupEntity pending = new PendingSignupEntity(
                SECRET, "user@migros.com", SECRET, LocalDateTime.now().plusMinutes(15),
                PendingTokenPurpose.SIGNUP);

        assertFalse(pending.toString().contains(SECRET));
    }

    @Test
    void userSignDtoToStringDoesNotContainThePassword() {
        UserSignDto dto = new UserSignDto();
        dto.setUserMail("user@migros.com");
        dto.setUserPassword(SECRET);

        assertFalse(dto.toString().contains(SECRET));
    }

    @Test
    void adminSignDtoToStringDoesNotContainThePassword() {
        AdminSignDto dto = new AdminSignDto();
        dto.setAdminName("admin");
        dto.setAdminPassword(SECRET);

        assertFalse(dto.toString().contains(SECRET));
    }

    @Test
    void resetPasswordDtoToStringDoesNotContainTheTokenOrPassword() {
        ResetPasswordDto dto = new ResetPasswordDto();
        dto.setToken(SECRET);
        dto.setUserPassword(SECRET);

        assertFalse(dto.toString().contains(SECRET));
    }

    @Test
    void jacksonOmitsTheUserPassword() throws Exception {
        UserEntity user = new UserEntity();
        user.setUserMail("user@migros.com");
        user.setUserPassword(SECRET);

        String json = objectMapper.writeValueAsString(user);

        assertFalse(json.contains(SECRET), json);
        assertFalse(json.contains("userPassword"), json);
    }

    @Test
    void jacksonOmitsTheAdminPassword() throws Exception {
        AdminEntity admin = new AdminEntity();
        admin.setAdminName("admin");
        admin.setAdminPassword(SECRET);

        String json = objectMapper.writeValueAsString(admin);

        assertFalse(json.contains(SECRET), json);
        assertFalse(json.contains("adminPassword"), json);
    }
}
