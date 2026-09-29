package com.example.MigrosBackend.config.security;

import com.example.MigrosBackend.config.AdminStartupProfilePolicy;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.env.StandardEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthCookieStartupConfigurationTest {

    private final AuthCookieStartupConfiguration configuration = new AuthCookieStartupConfiguration();

    @Test
    void guardIsSmartInitializingSingletonSoItRunsBeforeTheWebServerStarts() {
        assertThat(guard(false, "prod")).isInstanceOf(SmartInitializingSingleton.class);
    }

    @Test
    void noActiveProfileRejectsInsecureCookies() {
        assertThatThrownBy(() -> guard(false).afterSingletonsInstantiated())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void exactProdRejectsInsecureCookies() {
        assertThatThrownBy(() -> guard(false, "prod").afterSingletonsInstantiated())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void prodPlusLocalRejectsInsecureCookiesBecauseItIsNotExactLocal() {
        assertThatThrownBy(() -> guard(false, "prod", "local").afterSingletonsInstantiated())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void nonLocalAllowsSecureCookies() {
        assertThatCode(() -> guard(true, "prod").afterSingletonsInstantiated())
                .doesNotThrowAnyException();
    }

    @Test
    void exactLocalAllowsInsecureCookies() {
        assertThatCode(() -> guard(false, "local").afterSingletonsInstantiated())
                .doesNotThrowAnyException();
    }

    private SmartInitializingSingleton guard(boolean secure, String... activeProfiles) {
        AuthCookieProperties properties = new AuthCookieProperties();
        properties.setSecure(secure);

        StandardEnvironment environment = new StandardEnvironment();
        environment.setActiveProfiles(activeProfiles);

        return configuration.nonLocalInsecureCookieGuard(properties, new AdminStartupProfilePolicy(environment));
    }
}
