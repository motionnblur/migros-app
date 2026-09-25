package com.example.MigrosBackend.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class AdminStartupProfilePolicyTest {

    @Test
    void treatsExactLocalProfileAsLocalDevelopment() {
        assertThat(policyFor("local").isLocalDevelopment()).isTrue();
    }

    @Test
    void treatsNoActiveProfileAsNonLocal() {
        assertThat(policyFor().isLocalDevelopment()).isFalse();
    }

    @Test
    void treatsExactProdProfileAsNonLocal() {
        assertThat(policyFor("prod").isLocalDevelopment()).isFalse();
    }

    @Test
    void treatsProdPlusLocalAsNonLocal() {
        assertThat(policyFor("prod", "local").isLocalDevelopment()).isFalse();
    }

    @Test
    void treatsLocalPlusStagingAsNonLocal() {
        assertThat(policyFor("local", "staging").isLocalDevelopment()).isFalse();
    }

    private static AdminStartupProfilePolicy policyFor(String... activeProfiles) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.setActiveProfiles(activeProfiles);
        return new AdminStartupProfilePolicy(environment);
    }
}
