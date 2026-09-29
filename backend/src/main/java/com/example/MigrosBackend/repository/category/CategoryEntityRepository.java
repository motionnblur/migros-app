package com.example.MigrosBackend.repository.category;

import com.example.MigrosBackend.entity.category.CategoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CategoryEntityRepository extends JpaRepository<CategoryEntity, Long> {
    CategoryEntity findByCategoryId(int categoryId);
    CategoryEntity findByCategoryName(String categoryName);
    boolean existsByCategoryName(String categoryName);

    /**
     * Every row carrying a name, for callers that must be able to tell "no such
     * category" from "several of them".
     *
     * <p>{@code category_name} has no unique constraint, so a name is not a key:
     * {@link #findByCategoryName} inherits the single-result contract and
     * answers a duplicated name with an
     * {@code IncorrectResultSizeDataAccessException}, which reaches the client
     * as a 500 carrying a persistence-layer message. A caller that resolves a
     * category from a submitted name needs to report the collision itself, so it
     * asks for the matches and rejects anything other than exactly one.
     *
     * <p>The existing finder stays because its callers ({@code addCategory}'s
     * duplicate check, the seeder) only ever pass a name already known to be
     * unique, and changing their semantics is not this change's business.
     */
    List<CategoryEntity> findAllByCategoryName(String categoryName);
}
