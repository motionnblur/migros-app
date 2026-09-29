package com.example.MigrosBackend.repository.product;

import com.example.MigrosBackend.entity.product.ProductImageEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ProductImageEntityRepository extends JpaRepository<ProductImageEntity, Long> {
    List<ProductImageEntity> findByProductEntityId(Long id);

    /**
     * Image references that could name the given canonical file identity.
     *
     * <p>Deliberately a superset, never an answer. A stored reference is a
     * legacy absolute path, a bare name, or a value using either separator, so
     * the only thing a database can decide without reimplementing the whole
     * normalization is whether the value <em>ends with</em> the identity: that
     * holds for every spelling whose final path component is the identity, and
     * for some others as well. The caller re-canonicalizes each candidate with
     * {@code FileService} and compares exactly, which is what makes the check
     * trustworthy - a suffix comparison alone would let
     * {@code other_image_x.png} masquerade as {@code image_x.png} and keep a
     * live file alive forever, and would miss nothing only because the
     * canonicalization is the same code that serves and writes the file.
     *
     * <p>Right-trimming rather than a {@code LIKE} suffix pattern, so a file
     * name containing {@code %} or {@code _} cannot smuggle in a wildcard.
     */
    @Query(value = "SELECT image_path FROM product_image_entity "
            + "WHERE btrim(image_path) = :identity "
            + "OR right(btrim(image_path), char_length(:identity)) = :identity",
            nativeQuery = true)
    List<String> findImagePathsPossiblyReferencing(@Param("identity") String identity);
}
