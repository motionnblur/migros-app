package com.example.MigrosBackend.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import static org.assertj.core.api.Assertions.assertThat;

class AsyncConfigExecutorTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
            .withInitializer(context -> context.getBeanFactory()
                    .setConversionService(ApplicationConversionService.getSharedInstance()))
            .withUserConfiguration(AsyncConfig.class);

    @Test
    void asyncExecutorIsExplicitlyNamedAndBounded() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            ThreadPoolTaskExecutor executor =
                    context.getBean("applicationTaskExecutor", ThreadPoolTaskExecutor.class);

            assertThat(executor.getThreadNamePrefix()).isEqualTo("async-");
            assertThat(executor.getCorePoolSize()).isEqualTo(4);
            assertThat(executor.getMaxPoolSize()).isEqualTo(8);
            assertThat(executor.getQueueCapacity()).isEqualTo(100);
        });
    }

    @Test
    void asyncExecutorHonorsConfiguredSizing() {
        runner.withPropertyValues(
                        "spring.task.execution.pool.core-size=2",
                        "spring.task.execution.pool.max-size=3",
                        "spring.task.execution.pool.queue-capacity=17")
                .run(context -> {
                    ThreadPoolTaskExecutor executor =
                            context.getBean("applicationTaskExecutor", ThreadPoolTaskExecutor.class);

                    assertThat(executor.getCorePoolSize()).isEqualTo(2);
                    assertThat(executor.getMaxPoolSize()).isEqualTo(3);
                    assertThat(executor.getQueueCapacity()).isEqualTo(17);
                });
    }

    @Test
    void asyncExecutorQueueIsFinite() {
        runner.run(context -> {
            ThreadPoolTaskExecutor executor =
                    context.getBean("applicationTaskExecutor", ThreadPoolTaskExecutor.class);

            assertThat(executor.getQueueCapacity()).isPositive().isLessThan(Integer.MAX_VALUE);
        });
    }
}
