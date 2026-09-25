package com.example.MigrosBackend.config;

import com.example.MigrosBackend.entity.category.CategoryEntity;
import com.example.MigrosBackend.repository.category.CategoryEntityRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.CommandLineRunner;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StartupConfigurationCategoryTest {

    private final CategoryEntityRepository categoryEntityRepository = mock(CategoryEntityRepository.class);
    private final StartupConfiguration configuration = new StartupConfiguration();

    @Test
    void initializesAllCategories_whenAbsent() throws Exception {
        when(categoryEntityRepository.existsByCategoryName(any())).thenReturn(false);

        CommandLineRunner runner = configuration.initializeCategories(categoryEntityRepository);
        runner.run();

        ArgumentCaptor<CategoryEntity> captor = ArgumentCaptor.forClass(CategoryEntity.class);
        verify(categoryEntityRepository, times(18)).save(captor.capture());
        List<String> names = captor.getAllValues().stream().map(CategoryEntity::getCategoryName).toList();
        assertThat(names).contains("Yılbaşı", "Elektronik", "Pet Shop");
        assertThat(names).doesNotHaveDuplicates();
    }

    @Test
    void skipsCategoriesThatAlreadyExist() throws Exception {
        when(categoryEntityRepository.existsByCategoryName(any())).thenReturn(true);

        CommandLineRunner runner = configuration.initializeCategories(categoryEntityRepository);
        runner.run();

        verify(categoryEntityRepository, never()).save(any(CategoryEntity.class));
    }
}
