package com.example.MigrosBackend.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.MigrosBackend.controller.admin.panel.AdminPanelController;
import com.example.MigrosBackend.dto.error.ValidationErrorDto;
import com.example.MigrosBackend.dto.product.ProductEditConflictDto;
import com.example.MigrosBackend.entity.product.ProductEntity;
import com.example.MigrosBackend.exception.admin.ProductEditConflictException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.core.MethodParameter;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The product-edit conflict contract, asserted at the handler rather than only
 * through HTTP.
 *
 * <p>Two properties are load-bearing and easy to lose in a refactor:
 *
 * <ul>
 *   <li>Every path into "your edit is stale" produces the <em>same</em> body.
 *       The explicit version comparison and the persistence-context optimistic
 *       lock are different exceptions, and a client that only understands one of
 *       them would turn a stale edit into an opaque 500.</li>
 *   <li>Neither the response nor the log carries the exception text. An
 *       optimistic-lock message embeds row values, and an integrity message
 *       embeds constraint names - neither belongs in a client response or a log
 *       line.</li>
 * </ul>
 *
 * <p>{@link GlobalExceptionHandler} is deliberately not touched by the product
 * edit feature, so its own mappings are re-checked here: a change that quietly
 * repointed an existing code would be as much of a regression as losing the new
 * one.
 */
class GlobalExceptionHandlerVersionConflictTest {

    private final ProductEditConflictExceptionHandler handler = new ProductEditConflictExceptionHandler();
    private final GlobalExceptionHandler globalHandler = new GlobalExceptionHandler();

    @BeforeEach
    void bindRequest() {
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(new MockHttpServletRequest("POST", "/admin/panel/updateProduct")));
    }

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void aStaleVersionIsATypedConflict() {
        ResponseEntity<ProductEditConflictDto> response =
                handler.handleEditConflict(ProductEditConflictException.staleVersion());

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        ProductEditConflictDto body = response.getBody();
        assertNotNull(body);
        assertEquals("PRODUCT_EDIT_CONFLICT", body.code());
        assertEquals(409, body.status());
        assertNotNull(body.message());
    }

    /**
     * The conflict must not hand back the product's current version.
     *
     * <p>If it did, a client could resubmit the draft it was just told to discard
     * against that version, silently overwriting whatever changed stock in the
     * meantime. The only recovery is a deliberate reload.
     */
    @Test
    void theConflictBodyDoesNotPublishTheCurrentVersion() {
        ProductEditConflictDto body = handler.handleEditConflict(ProductEditConflictException.staleVersion())
                .getBody();

        assertNotNull(body);
        String serialized = body.toString();
        assertFalse(serialized.toLowerCase().contains("version"),
                "the conflict body must not carry a version a client could resubmit: " + serialized);
    }

    @Test
    void anOptimisticLockFailureMapsToTheSameConflictAndLeaksNothing() {
        // The JPA message is exactly the kind of text that must not escape: it
        // names the entity and can embed the row values it failed to update.
        String leakyMessage = "Row with id 42 was updated or deleted by another transaction "
                + "(or unsaved-value mapping was incorrect) : [ProductEntity#42]";

        ResponseEntity<ProductEditConflictDto> response = handler.handleOptimisticLockFailure(
                new ObjectOptimisticLockingFailureException(ProductEntity.class, 42L,
                        new IllegalStateException(leakyMessage)));

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode(),
                "an optimistic-lock failure is a client error, not a server error");
        ProductEditConflictDto body = response.getBody();
        assertNotNull(body);
        assertEquals("PRODUCT_EDIT_CONFLICT", body.code());
        assertEquals(409, body.status());
        assertFalse(body.message().contains("ProductEntity"));
        assertFalse(body.message().contains("42"));
        assertFalse(body.message().contains("UPDATE"));
    }

    @Test
    void anUnconvertibleParameterIsAStructuredBadRequestThatEchoesNothing() {
        MethodParameter parameter = mock(MethodParameter.class);
        when(parameter.getParameterName()).thenReturn("expectedVersion");

        // The type-mismatch mapping is the global one: request-binding failures
        // are not specific to the product-edit controller, so they belong with
        // the other structured 400s in GlobalExceptionHandler rather than in the
        // product-scoped advice.
        ResponseEntity<ValidationErrorDto> response = globalHandler.handleMethodArgumentTypeMismatch(
                new MethodArgumentTypeMismatchException("7; DROP TABLE product_entity", Long.class,
                        "expectedVersion", parameter, null));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        ValidationErrorDto body = response.getBody();
        assertNotNull(body);
        assertEquals("VALIDATION_FAILED", body.code());
        assertEquals(400, body.status());
        assertNotNull(body.errors());
        assertEquals(1, body.errors().size());
        assertEquals("expectedVersion", body.errors().get(0).field());
        assertFalse(body.errors().get(0).message().contains("DROP TABLE"),
                "a client-supplied value must never be reflected into the response");
    }

    @Test
    void conflictLoggingIsOneWarnLineWithNoStackTraceAndNoMessage() {
        Logger logger = (Logger) LoggerFactory.getLogger(ProductEditConflictExceptionHandler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            handler.handleOptimisticLockFailure(new ObjectOptimisticLockingFailureException(
                    ProductEntity.class, 42L,
                    new IllegalStateException("UPDATE product_entity SET product_count = 41")));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        assertEquals(1, appender.list.size());
        ILoggingEvent event = appender.list.get(0);
        assertEquals(Level.WARN, event.getLevel());
        assertNull(event.getThrowableProxy(), "a client error must stay one line, without a stack trace");
        assertTrue(event.getFormattedMessage().contains("ObjectOptimisticLockingFailureException"),
                "the exception class is what identifies the failure: " + event.getFormattedMessage());
        assertTrue(event.getFormattedMessage().contains("/admin/panel/updateProduct"),
                "the request URI is what identifies where it happened: " + event.getFormattedMessage());
        assertFalse(event.getFormattedMessage().contains("UPDATE product_entity"),
                "SQL text must never reach a log line: " + event.getFormattedMessage());
        assertFalse(event.getFormattedMessage().contains("product_count"),
                "row state must never reach a log line: " + event.getFormattedMessage());
    }

    /**
     * The lead-owned handler is untouched by this feature. Its most specific
     * mapping - the generic 409 for a constraint violation - must still answer
     * with its own code, so an unrelated integrity failure is never mistaken for
     * an edit conflict by a client that branches on {@code code}.
     */
    @Test
    void theExistingDataIntegrityConflictMappingIsUndisturbed() {
        ResponseEntity<ValidationErrorDto> response = globalHandler.handleDataIntegrityViolation(
                new DataIntegrityViolationException("constraint uq_user_entity_user_mail detail"));

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        ValidationErrorDto body = response.getBody();
        assertNotNull(body);
        assertEquals("DATA_INTEGRITY_VIOLATION", body.code());
        assertNull(body.errors());
    }

    /**
     * The two conflict bodies are distinguishable only by their code, which is
     * exactly what a client branches on. Sharing a status is fine; sharing a code
     * would make an integrity failure look like a stale edit and send the
     * administrator down the reload path for the wrong reason. Asserted on the
     * rendered bodies rather than on the constants, so the check is about what a
     * client actually receives.
     */
    @Test
    void theTwoConflictCodesAreDistinct() {
        ResponseEntity<ProductEditConflictDto> editConflict =
                handler.handleEditConflict(new ProductEditConflictException("stale"));

        assertEquals(HttpStatus.CONFLICT, editConflict.getStatusCode());
        assertEquals("PRODUCT_EDIT_CONFLICT", editConflict.getBody().code());

        assertNotEquals(editConflict.getBody().code(), "DATA_INTEGRITY_VIOLATION");
    }

    /**
     * The optimistic-lock mapping is scoped to the product-edit controller and
     * must stay there. {@code CheckoutEntity} and {@code PaymentAttemptEntity}
     * also carry {@code @Version}, so a global mapping would answer an unrelated
     * payment or checkout conflict with a product-specific code and message.
     */
    @Test
    void theEditConflictAdviceIsScopedToTheProductEditController() {
        RestControllerAdvice annotation = ProductEditConflictExceptionHandler.class
                .getAnnotation(RestControllerAdvice.class);

        assertNotNull(annotation, "the advice must still be registered");
        assertArrayEquals(new Class<?>[]{AdminPanelController.class}, annotation.assignableTypes(),
                "a global optimistic-lock mapping would mislabel payment and checkout conflicts");
    }
}
