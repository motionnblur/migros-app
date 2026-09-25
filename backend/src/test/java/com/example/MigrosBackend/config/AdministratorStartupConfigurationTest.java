package com.example.MigrosBackend.config;

import com.example.MigrosBackend.entity.admin.AdminEntity;
import com.example.MigrosBackend.repository.admin.AdminEntityRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdministratorStartupConfigurationTest {

    private final AdminEntityRepository adminEntityRepository = mock(AdminEntityRepository.class);
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final AdministratorStartupConfiguration configuration = new AdministratorStartupConfiguration();

    @Test
    void exactLocalCreatesDefaultAdministrator_whenAbsent() throws Exception {
        when(adminEntityRepository.existsByAdminName("admin")).thenReturn(false);

        seedRunner("local").run();

        ArgumentCaptor<AdminEntity> captor = ArgumentCaptor.forClass(AdminEntity.class);
        verify(adminEntityRepository).save(captor.capture());
        AdminEntity saved = captor.getValue();
        assertThat(saved.getAdminName()).isEqualTo("admin");
        assertThat(passwordEncoder.matches("admin", saved.getAdminPassword())).isTrue();
    }

    @Test
    void exactLocalDoesNotOverwriteExistingAdministrator() throws Exception {
        when(adminEntityRepository.existsByAdminName("admin")).thenReturn(true);

        seedRunner("local").run();

        verify(adminEntityRepository, never()).save(any(AdminEntity.class));
    }

    @Test
    void noActiveProfileNeverCreatesDefaultAdministrator() throws Exception {
        seedRunner().run();

        verify(adminEntityRepository, never()).save(any(AdminEntity.class));
    }

    @Test
    void exactProdNeverCreatesDefaultAdministrator() throws Exception {
        seedRunner("prod").run();

        verify(adminEntityRepository, never()).save(any(AdminEntity.class));
    }

    @Test
    void prodPlusLocalNeverCreatesDefaultAdministrator() throws Exception {
        seedRunner("prod", "local").run();

        verify(adminEntityRepository, never()).save(any(AdminEntity.class));
    }

    @Test
    void localPlusStagingNeverCreatesDefaultAdministrator() throws Exception {
        seedRunner("local", "staging").run();

        verify(adminEntityRepository, never()).save(any(AdminEntity.class));
    }

    @Test
    void guardIsSmartInitializingSingletonAndNotCommandLineRunner() {
        SmartInitializingSingleton guard = guard("prod");

        assertThat(guard).isInstanceOf(SmartInitializingSingleton.class);
        assertThat(guard).isNotInstanceOf(CommandLineRunner.class);
    }

    @Test
    void noActiveProfileRejectsDefaultAdministrator() {
        assertGuardRejectsDefaultAdministrator(guard());
    }

    @Test
    void exactProdRejectsDefaultAdministrator() {
        assertGuardRejectsDefaultAdministrator(guard("prod"));
    }

    @Test
    void prodPlusLocalRejectsDefaultAdministrator() {
        assertGuardRejectsDefaultAdministrator(guard("prod", "local"));
    }

    @Test
    void localPlusStagingRejectsDefaultAdministrator() {
        assertGuardRejectsDefaultAdministrator(guard("local", "staging"));
    }

    @Test
    void nonLocalGuardAllowsEmptyAdminTable() {
        when(adminEntityRepository.findByAdminName("admin")).thenReturn(null);

        assertThatCode(() -> guard("prod").afterSingletonsInstantiated()).doesNotThrowAnyException();
    }

    @Test
    void nonLocalGuardAllowsDifferentlyNamedAdministrator() {
        when(adminEntityRepository.findByAdminName("admin")).thenReturn(null);

        assertThatCode(() -> guard("prod").afterSingletonsInstantiated()).doesNotThrowAnyException();
        verify(adminEntityRepository).findByAdminName("admin");
    }

    @Test
    void nonLocalGuardAllowsAdministratorWithRotatedPassword() {
        AdminEntity admin = new AdminEntity();
        admin.setAdminName("admin");
        admin.setAdminPassword(passwordEncoder.encode("a-strong-unique-password"));
        when(adminEntityRepository.findByAdminName("admin")).thenReturn(admin);

        assertThatCode(() -> guard("prod").afterSingletonsInstantiated()).doesNotThrowAnyException();
    }

    @Test
    void exactLocalGuardDoesNotRejectDefaultAdministrator() {
        when(adminEntityRepository.findByAdminName("admin")).thenReturn(defaultAdminEntity());

        assertThatCode(() -> guard("local").afterSingletonsInstantiated()).doesNotThrowAnyException();
    }

    private CommandLineRunner seedRunner(String... activeProfiles) {
        return configuration.localDefaultAdministratorInitializer(
                adminEntityRepository, passwordEncoder, policy(activeProfiles));
    }

    private SmartInitializingSingleton guard(String... activeProfiles) {
        return configuration.nonLocalDefaultAdministratorGuard(
                adminEntityRepository, passwordEncoder, policy(activeProfiles));
    }

    private void assertGuardRejectsDefaultAdministrator(SmartInitializingSingleton guard) {
        when(adminEntityRepository.findByAdminName("admin")).thenReturn(defaultAdminEntity());

        assertThatThrownBy(guard::afterSingletonsInstantiated).isInstanceOf(IllegalStateException.class);
    }

    private AdminEntity defaultAdminEntity() {
        AdminEntity admin = new AdminEntity();
        admin.setAdminName("admin");
        admin.setAdminPassword(passwordEncoder.encode("admin"));
        return admin;
    }

    private static AdminStartupProfilePolicy policy(String... activeProfiles) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.setActiveProfiles(activeProfiles);
        return new AdminStartupProfilePolicy(environment);
    }
}
