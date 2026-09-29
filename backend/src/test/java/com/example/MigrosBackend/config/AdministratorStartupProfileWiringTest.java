package com.example.MigrosBackend.config;

import com.example.MigrosBackend.entity.admin.AdminEntity;
import com.example.MigrosBackend.repository.admin.AdminEntityRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdministratorStartupProfileWiringTest {

    private final AtomicReference<AdminEntityRepository> repositoryRef = new AtomicReference<>();

    @Test
    void guardBeanUsesPreServerLifecycleAndIsNotACommandLineRunner() {
        contextRunner(null)
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("prod"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    Object guard = context.getBean("nonLocalDefaultAdministratorGuard");
                    assertThat(guard).isInstanceOf(SmartInitializingSingleton.class);
                    assertThat(guard).isNotInstanceOf(CommandLineRunner.class);
                    assertThat(context).hasBean("localDefaultAdministratorInitializer");
                });
    }

    @Test
    void nonLocalContextWithDefaultCredentialFailsDuringContextRefresh() {
        contextRunner(defaultAdminEntity())
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("prod"))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).isInstanceOf(IllegalStateException.class);
                });
    }

    @Test
    void prodPlusLocalContextWithDefaultCredentialFailsDuringContextRefresh() {
        contextRunner(defaultAdminEntity())
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("prod", "local"))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).isInstanceOf(IllegalStateException.class);
                });
    }

    @Test
    void localPlusStagingContextWithDefaultCredentialFailsDuringContextRefresh() {
        contextRunner(defaultAdminEntity())
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("local", "staging"))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).isInstanceOf(IllegalStateException.class);
                });
    }

    @Test
    void exactLocalContextWithDefaultCredentialStarts() {
        contextRunner(defaultAdminEntity())
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("local"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasBean("localDefaultAdministratorInitializer");
                });
    }

    @Test
    void exactLocalProfileSeedsDefaultAdministratorWhenSeedRunnerRuns() throws Exception {
        contextRunner(null)
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("local"))
                .run(context -> {
                    CommandLineRunner seed = context.getBean("localDefaultAdministratorInitializer", CommandLineRunner.class);
                    seed.run();

                    ArgumentCaptor<AdminEntity> captor = ArgumentCaptor.forClass(AdminEntity.class);
                    verify(repositoryRef.get()).save(captor.capture());
                    assertThat(captor.getValue().getAdminName()).isEqualTo("admin");
                });
    }

    @Test
    void nonLocalProfileSeedRunnerNeverWritesAdministrator() throws Exception {
        contextRunner(null)
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("prod"))
                .run(context -> {
                    CommandLineRunner seed = context.getBean("localDefaultAdministratorInitializer", CommandLineRunner.class);
                    seed.run();

                    verify(repositoryRef.get(), never()).save(any(AdminEntity.class));
                });
    }

    private ApplicationContextRunner contextRunner(AdminEntity admin) {
        return new ApplicationContextRunner()
                .withUserConfiguration(AdministratorStartupConfiguration.class, AdminStartupProfilePolicy.class)
                .withBean(AdminEntityRepository.class, () -> {
                    AdminEntityRepository repository = mock(AdminEntityRepository.class);
                    when(repository.findByAdminName("admin")).thenReturn(admin);
                    repositoryRef.set(repository);
                    return repository;
                })
                .withBean(PasswordEncoder.class, BCryptPasswordEncoder::new);
    }

    private AdminEntity defaultAdminEntity() {
        AdminEntity admin = new AdminEntity();
        admin.setAdminName("admin");
        admin.setAdminPassword(new BCryptPasswordEncoder().encode("admin"));
        return admin;
    }
}
