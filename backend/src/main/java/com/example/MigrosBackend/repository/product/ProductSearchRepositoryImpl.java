package com.example.MigrosBackend.repository.product;

import com.example.MigrosBackend.entity.product.ProductEntity;
import com.example.MigrosBackend.service.user.supply.ProductSearchSort;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Criteria implementation of {@link ProductSearchRepository}.
 *
 * <p>All three queries build their predicate from the same
 * {@link ProductSearchSpecifications#toPredicate} call, differing only in what
 * they select, group and order. That is the whole reason this class is one file
 * rather than three: a search whose list, total and facet counts each carry
 * their own copy of the filter definition is a search that eventually reports
 * numbers the list does not agree with.
 */
@Repository
public class ProductSearchRepositoryImpl implements ProductSearchRepository {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public List<ProductEntity> findPage(ProductSearchCriteria criteria, ProductSearchSort sort,
                                        Pageable pageable) {
        CriteriaBuilder builder = entityManager.getCriteriaBuilder();
        CriteriaQuery<ProductEntity> query = builder.createQuery(ProductEntity.class);
        Root<ProductEntity> root = query.from(ProductEntity.class);

        query.select(root)
                .where(ProductSearchSpecifications.toPredicate(root, builder, criteria, true))
                .orderBy(ProductSearchSpecifications.ordersOf(root, builder, sort));

        return entityManager.createQuery(query)
                .setFirstResult((int) pageable.getOffset())
                .setMaxResults(pageable.getPageSize())
                .getResultList();
    }

    @Override
    public long countMatching(ProductSearchCriteria criteria) {
        CriteriaBuilder builder = entityManager.getCriteriaBuilder();
        CriteriaQuery<Long> query = builder.createQuery(Long.class);
        Root<ProductEntity> root = query.from(ProductEntity.class);

        // No ordering here, deliberately. The count describes the same set as the
        // page query and must pay none of its sorting cost - an ORDER BY in a
        // count query is work whose result the database throws away.
        query.select(builder.count(root))
                .where(ProductSearchSpecifications.toPredicate(root, builder, criteria, true));

        return entityManager.createQuery(query).getSingleResult();
    }

    @Override
    public List<SubcategoryCount> countMatchingBySubcategory(ProductSearchCriteria criteria) {
        CriteriaBuilder builder = entityManager.getCriteriaBuilder();
        CriteriaQuery<Tuple> query = builder.createTupleQuery();
        Root<ProductEntity> root = query.from(ProductEntity.class);

        Expression<String> subcategoryName = root.get("subcategoryName");
        Predicate withoutSubcategory =
                ProductSearchSpecifications.toPredicate(root, builder, criteria.withoutSubcategory(), false);
        Predicate namedSubcategory = builder.and(
                builder.isNotNull(subcategoryName),
                builder.notEqual(subcategoryName, ""));

        query.multiselect(subcategoryName, builder.count(root))
                .where(builder.and(withoutSubcategory, namedSubcategory))
                .groupBy(subcategoryName)
                .orderBy(builder.asc(subcategoryName));

        return entityManager.createQuery(query).getResultList().stream()
                .map(row -> new SubcategoryCount(row.get(0, String.class), row.get(1, Long.class)))
                .toList();
    }
}
