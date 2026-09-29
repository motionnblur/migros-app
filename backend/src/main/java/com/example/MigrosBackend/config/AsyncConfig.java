package com.example.MigrosBackend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Duration;

/**
 * Async and scheduling wiring.
 *
 * <p>{@code @Async} work runs on an explicitly configured, named, bounded
 * executor so a burst of slow tasks cannot grow an unbounded backlog and
 * graceful shutdown drains in-flight work for a bounded time.
 *
 * <p>The scheduling pool is deliberately left at Spring Boot's default (a
 * single scheduler thread). Every {@code @Scheduled} job in this application
 * performs its own bounded, per-item error handling and none relies on
 * overlapping execution, so its concurrency semantics are unchanged.
 */
@Configuration
@EnableAsync
@EnableScheduling
public class AsyncConfig {

    @Bean(name = "applicationTaskExecutor")
    public ThreadPoolTaskExecutor applicationTaskExecutor(
            @Value("${spring.task.execution.pool.core-size:4}") int corePoolSize,
            @Value("${spring.task.execution.pool.max-size:8}") int maxPoolSize,
            @Value("${spring.task.execution.pool.queue-capacity:100}") int queueCapacity,
            @Value("${spring.task.execution.pool.keep-alive:60s}") Duration keepAlive,
            @Value("${spring.task.execution.shutdown.await-termination-period:30s}") Duration awaitTermination) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("async-");
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setKeepAliveSeconds((int) keepAlive.toSeconds());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds((int) awaitTermination.toSeconds());
        return executor;
    }
}
