package com.example.MigrosBackend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Shared wall-clock bean so time-dependent payment and webhook components can
 * use a controllable clock instead of calling the system clock directly.
 */
@Configuration
public class TimeConfiguration {

    @Bean
    public Clock systemClock() {
        return Clock.systemDefaultZone();
    }
}
