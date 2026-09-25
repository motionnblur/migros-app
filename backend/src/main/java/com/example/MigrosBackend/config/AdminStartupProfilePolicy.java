package com.example.MigrosBackend.config;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class AdminStartupProfilePolicy {

    private static final String LOCAL_PROFILE = "local";

    private final Environment environment;

    public AdminStartupProfilePolicy(Environment environment) {
        this.environment = environment;
    }

    public boolean isLocalDevelopment() {
        String[] activeProfiles = environment.getActiveProfiles();
        return activeProfiles.length == 1 && LOCAL_PROFILE.equals(activeProfiles[0]);
    }
}
