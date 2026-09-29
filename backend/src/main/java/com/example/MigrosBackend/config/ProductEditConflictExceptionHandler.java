package com.example.MigrosBackend.config;

import com.example.MigrosBackend.controller.admin.panel.AdminPanelController;
import com.example.MigrosBackend.dto.product.ProductEditConflictDto;
import com.example.MigrosBackend.exception.admin.ProductEditConflictException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Response mapping for rejected product edits and for the request-binding
 * failures around them.
 *
 * <p>Separate from {@link GlobalExceptionHandler} on purpose. This advice owns
 * only the three mappings the product-edit contract needs, which keeps the
 * change auditable: {@link GlobalExceptionHandler} is untouched, so its
 * existing {@code DATA_INTEGRITY_VIOLATION} and validation mappings - and the
 * tests over them - cannot drift because of this feature.
 *
 * <p>The three concerns are related for a reason, not for convenience. A stale
 * editor must receive one stable, machine-readable conflict code whether the
 * mismatch was caught by comparing versions under a row lock
 * ({@link ProductEditConflictException}) or only by the persistence context at
 * flush time ({@link OptimisticLockingFailureException}). The latter is a JPA
 * exception whose message embeds row values and sometimes SQL state, so mapping
 * it to the same fixed body is what keeps that text out of an HTTP response -
 * and out of the log, where only the exception class and the request URI are
 * recorded.
 *
 * <p>None of these mappings can collide with CSRF: a CSRF failure is an
 * {@code AccessDeniedException} raised by Spring Security's filter chain, which
 * never reaches {@code @RestControllerAdvice}. It is written by
 * {@code CsrfAccessDeniedHandler} as 403 / {@code CSRF_INVALID} and is
 * reclassified as nothing by this class.
 *
 * <p>The advice is scoped to {@link AdminPanelController} on purpose. The
 * optimistic-lock mapping in particular must not be global: {@code CheckoutEntity}
 * and {@code PaymentAttemptEntity} also carry {@code @Version}, so a global
 * handler would answer an unrelated conflict in the payment or checkout path with
 * {@code PRODUCT_EDIT_CONFLICT} and a message about a product, sending a client
 * that branches on {@code code} down a product-reload path for a payment bug, and
 * converting a would-be 500 into a plausible-looking 409. Scoping keeps the
 * mapping true to the request that can actually raise it. Generic request-binding
 * failures are not handled here at all; they belong to
 * {@link GlobalExceptionHandler}, which owns the structured 400 contract for
 * every endpoint.
 */
@RestControllerAdvice(assignableTypes = AdminPanelController.class)
public class ProductEditConflictExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ProductEditConflictExceptionHandler.class);

    private static final String CONFLICT_CODE = ProductEditConflictException.CODE;

    @ExceptionHandler(ProductEditConflictException.class)
    public ResponseEntity<ProductEditConflictDto> handleEditConflict(ProductEditConflictException ex) {
        logClientError(ex);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ProductEditConflictDto(CONFLICT_CODE, ex.getMessage(), HttpStatus.CONFLICT.value()));
    }

    /**
     * The same conflict, reached through a different door.
     *
     * <p>{@code updateProduct} compares versions under a row lock, so in the
     * single-editor case it always raises {@link ProductEditConflictException}
     * first. This handler is the backstop for the interleavings that bypass
     * that comparison - most importantly a bulk stock increment racing the edit,
     * where the entity is already managed and Hibernate detects the stale
     * version itself at flush time. Without it those requests would be HTTP 500
     * and would carry the JPA message, which contains row values.
     */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ProductEditConflictDto> handleOptimisticLockFailure(OptimisticLockingFailureException ex) {
        logClientError(ex);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ProductEditConflictDto(CONFLICT_CODE,
                        "This product was changed by someone else after you opened it. "
                                + "Reload the product, review the current values, and save again.",
                        HttpStatus.CONFLICT.value()));
    }

    /**
     * One line, exception class and request URI only.
     *
     * <p>Never the message: an optimistic-lock or data-integrity message can
     * contain row values, constraint names or SQL fragments, and a file path
     * would expose the upload layout. No stack trace either - these are client
     * errors, and a stack trace is what turns a routine rejected edit into
     * unreadable log noise.
     */
    private static void logClientError(Throwable ex) {
        log.warn("{} at {}", ex.getClass().getSimpleName(), requestPath());
    }

    private static String requestPath() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletAttributes) {
            return servletAttributes.getRequest().getRequestURI();
        }
        return "unknown";
    }
}
