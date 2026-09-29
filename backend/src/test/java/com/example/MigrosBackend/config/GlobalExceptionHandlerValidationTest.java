package com.example.MigrosBackend.config;

import com.example.MigrosBackend.dto.error.ValidationErrorDto;
import com.example.MigrosBackend.exception.shared.WrongPasswordException;
import com.example.MigrosBackend.exception.user.MailSendingFailedException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GlobalExceptionHandlerValidationTest {
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void serverErrorHandlerLogsTheExceptionAtError() {
        Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            handler.handleMailSendingFailed(new MailSendingFailedException());
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        assertEquals(1, appender.list.size());
        ILoggingEvent event = appender.list.get(0);
        assertEquals(Level.ERROR, event.getLevel());
        assertNotNull(event.getThrowableProxy(),
                "5xx handlers must log the exception so its stack trace is recorded");
    }

    @Test
    void clientAuthErrorHandlerLogsAtWarnWithoutAStackTrace() {
        Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            handler.handleWrongPassword(new WrongPasswordException());
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        assertEquals(1, appender.list.size());
        ILoggingEvent event = appender.list.get(0);
        assertEquals(Level.WARN, event.getLevel());
        assertNull(event.getThrowableProxy(), "client errors must stay one line, without a stack trace");
    }

    @Test
    void dataIntegrityViolationIsGenericConflictWithoutConstraintText() {
        ResponseEntity<ValidationErrorDto> response = handler.handleDataIntegrityViolation(
                new DataIntegrityViolationException("constraint uq_user_entity_user_mail detail"));

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        ValidationErrorDto body = response.getBody();
        assertNotNull(body);
        assertEquals("DATA_INTEGRITY_VIOLATION", body.code());
        assertFalse(body.message().contains("uq_user_entity_user_mail"));
        assertNull(body.errors());
    }

    @Test
    void malformedRequestIsTypedAndDoesNotEchoParseDetail() {
        ResponseEntity<ValidationErrorDto> response = handler.handleMessageNotReadable(
                new HttpMessageNotReadableException("raw parse detail: {\"userPassword\":\"secret\"}"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        ValidationErrorDto body = response.getBody();
        assertNotNull(body);
        assertEquals("MALFORMED_REQUEST", body.code());
        assertEquals("Malformed request", body.message());
        assertNull(body.errors());
    }

    @Test
    void missingParameterNamesTheParameter() {
        ResponseEntity<ValidationErrorDto> response = handler.handleMissingServletRequestParameter(
                new MissingServletRequestParameterException("orderId", "Long"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        ValidationErrorDto body = response.getBody();
        assertNotNull(body);
        assertEquals("MISSING_PARAMETER", body.code());
        assertTrue(body.message().contains("orderId"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void constraintViolationIsTypedBadRequest() {
        ConstraintViolation<Object> violation = mock(ConstraintViolation.class);
        Path path = mock(Path.class);
        when(path.toString()).thenReturn("uploadProduct.productPrice");
        when(violation.getPropertyPath()).thenReturn(path);
        when(violation.getMessage()).thenReturn("must be greater than or equal to 0");

        ResponseEntity<ValidationErrorDto> response = handler.handleConstraintViolation(
                new ConstraintViolationException(Set.of(violation)));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        ValidationErrorDto body = response.getBody();
        assertNotNull(body);
        assertEquals("VALIDATION_FAILED", body.code());
        assertNotNull(body.errors());
        assertEquals("uploadProduct.productPrice", body.errors().get(0).field());
        assertEquals("must be greater than or equal to 0", body.errors().get(0).message());
    }
}
