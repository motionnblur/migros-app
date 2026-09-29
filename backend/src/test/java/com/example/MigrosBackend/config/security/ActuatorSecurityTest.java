package com.example.MigrosBackend.config.security;

import com.example.MigrosBackend.filter.JwtRequestFilter;
import com.example.MigrosBackend.helper.AuthTokenResolver;
import com.example.MigrosBackend.repository.admin.AdminEntityRepository;
import com.example.MigrosBackend.service.global.TokenService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.autoconfigure.availability.AvailabilityHealthContributorAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.availability.AvailabilityProbesAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.endpoint.EndpointAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.health.HealthEndpointAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.web.server.ManagementContextAutoConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.availability.ApplicationAvailabilityAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = SecurityHeadersProbeController.class)
@AutoConfigureMockMvc
@Import({SecurityConfiguration.class, JwtRequestFilter.class})
@ImportAutoConfiguration({
        EndpointAutoConfiguration.class,
        WebEndpointAutoConfiguration.class,
        ApplicationAvailabilityAutoConfiguration.class,
        AvailabilityHealthContributorAutoConfiguration.class,
        AvailabilityProbesAutoConfiguration.class,
        HealthEndpointAutoConfiguration.class,
        ManagementContextAutoConfiguration.class
})
@TestPropertySource(properties = {
        "support.internal.key=test-internal-key",
        "management.endpoint.health.probes.enabled=true",
        "management.endpoint.health.group.readiness.include=readinessState",
        "management.endpoint.health.validate-group-membership=false"
})
class ActuatorSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AuthTokenResolver authTokenResolver;

    @MockBean
    private TokenService tokenService;

    @MockBean
    private AdminEntityRepository adminEntityRepository;

    @Test
    void healthEndpointIsPublicWithoutSession() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void livenessAndReadinessEndpointsArePublic() throws Exception {
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));

        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void sensitiveActuatorEndpointsAreNotPubliclyExposed() throws Exception {
        String[] sensitiveEndpoints = {
                "/actuator/env",
                "/actuator/configprops",
                "/actuator/beans",
                "/actuator/loggers",
                "/actuator/heapdump"
        };

        for (String endpoint : sensitiveEndpoints) {
            mockMvc.perform(get(endpoint))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void healthResponseDoesNotExposeComponentDetailsOrSecrets() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("components"))))
                .andExpect(content().string(not(containsString("\"details\""))))
                .andExpect(content().string(not(containsString("jdbc:"))))
                .andExpect(content().string(not(containsString("password"))))
                .andExpect(content().string(not(containsString("secret"))));
    }
}
