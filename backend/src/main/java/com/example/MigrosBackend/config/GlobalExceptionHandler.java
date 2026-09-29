package com.example.MigrosBackend.config;

import com.example.MigrosBackend.exception.admin.*;
import com.example.MigrosBackend.exception.shared.*;
import com.example.MigrosBackend.exception.user.*;
import com.example.MigrosBackend.dto.error.ValidationErrorDto;
import com.example.MigrosBackend.dto.payment.CheckoutConflictDto;
import jakarta.mail.MessagingException;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(GeneralException.class)
    public ResponseEntity<String> handleGeneralException(GeneralException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ex.getMessage());
    }

    @ExceptionHandler(SupportUserBannedException.class)
    public ResponseEntity<String> handleSupportUserBanned(SupportUserBannedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ex.getMessage());
    }

    @ExceptionHandler(AdminNotFoundException.class)
    public ResponseEntity<String> handleAdminNotFound(AdminNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ex.getMessage());
    }

    @ExceptionHandler(MailSendingFailedException.class)
    public ResponseEntity<String> handleMailSendingFailed(MailSendingFailedException ex) {
        log.error("Mail sending failed", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ex.getMessage());
    }

    @ExceptionHandler(TokenNotFoundException.class)
    public ResponseEntity<String> handleTokenNotFound(TokenNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ex.getMessage());
    }

    @ExceptionHandler(UserAlreadyExistsException.class)
    public ResponseEntity<String> handleUserAlreadyExists(UserAlreadyExistsException ex) {
        logClientError(ex);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ex.getMessage());
    }

    @ExceptionHandler(UserMailNotFoundException.class)
    public ResponseEntity<String> handleUserMailNotFound(UserMailNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ex.getMessage());
    }

    @ExceptionHandler(WeakPasswordException.class)
    public ResponseEntity<String> handleWeakPassword(WeakPasswordException ex) {
        return ResponseEntity.badRequest().body(ex.getMessage());
    }

    @ExceptionHandler(WrongPasswordException.class)
    public ResponseEntity<String> handleWrongPassword(WrongPasswordException ex) {
        logClientError(ex);
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ex.getMessage());
    }

    @ExceptionHandler(MessagingException.class)
    public ResponseEntity<String> handleMessaging(MessagingException ex) {
        log.error("Messaging failure", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Failed to send email.");
    }

    @ExceptionHandler(ProductNotFoundException.class)
    public ResponseEntity<String> handleProductNotFound(ProductNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ex.getMessage());
    }

    @ExceptionHandler(AdminHasNoProductException.class)
    public ResponseEntity<String> handleAdminHasNoProduct(AdminHasNoProductException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ex.getMessage());
    }

    @ExceptionHandler(FileUploadFailedException.class)
    public ResponseEntity<String> handleFileUploadFailed(FileUploadFailedException ex) {
        log.error("File upload failed", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ex.getMessage());
    }

    @ExceptionHandler(OrderNotFoundException.class)
    public ResponseEntity<String> handleOrderNotFound(OrderNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ex.getMessage());
    }

    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<String> handleUserNotFound(UserNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ex.getMessage());
    }

    @ExceptionHandler(CategoryNotFoundException.class)
    public ResponseEntity<String> handleCategoryNotFound(CategoryNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ex.getMessage());
    }

    @ExceptionHandler(CategoryHasNoProductException.class)
    public ResponseEntity<String> handleCategoryHasNoProduct(CategoryHasNoProductException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ex.getMessage());
    }

    @ExceptionHandler(InvalidTokenException.class)
    public ResponseEntity<String> handleInvalidToken(InvalidTokenException ex) {
        logClientError(ex);
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ex.getMessage());
    }

    @ExceptionHandler(SupportSyncConflictException.class)
    public ResponseEntity<String> handleSupportSyncConflict(SupportSyncConflictException ex) {
        logClientError(ex);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ex.getMessage());
    }

    @ExceptionHandler(FileNotFoundException.class)
    public ResponseEntity<String> handleFileNotFound(FileNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ex.getMessage());
    }

    @ExceptionHandler(PaymentAmountException.class)
    public ResponseEntity<String> handlePaymentAmountException(PaymentAmountException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ex.getMessage());
    }

    @ExceptionHandler(CheckoutNotFoundException.class)
    public ResponseEntity<String> handleCheckoutNotFound(CheckoutNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ex.getMessage());
    }

    @ExceptionHandler(CheckoutConflictException.class)
    public ResponseEntity<CheckoutConflictDto> handleCheckoutConflict(CheckoutConflictException ex) {
        logClientError(ex);
        CheckoutConflictDto body = new CheckoutConflictDto(
                ex.getCode(),
                ex.getMessage(),
                HttpStatus.CONFLICT.value(),
                ex.isPending(),
                ex.getCheckoutId() == null ? null : ex.getCheckoutId().toString());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(CheckoutStateException.class)
    public ResponseEntity<CheckoutConflictDto> handleCheckoutState(CheckoutStateException ex) {
        logClientError(ex);
        CheckoutConflictDto body = new CheckoutConflictDto(
                "CHECKOUT_CONFLICT",
                ex.getMessage(),
                HttpStatus.CONFLICT.value(),
                false,
                null);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(PaymentStateException.class)
    public ResponseEntity<String> handlePaymentState(PaymentStateException ex) {
        logClientError(ex);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ex.getMessage());
    }

    @ExceptionHandler(PaymentAttemptNotFoundException.class)
    public ResponseEntity<String> handlePaymentAttemptNotFound(PaymentAttemptNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ex.getMessage());
    }

    @ExceptionHandler(WebhookSignatureException.class)
    public ResponseEntity<String> handleWebhookSignature(WebhookSignatureException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ValidationErrorDto> handleMethodArgumentNotValid(MethodArgumentNotValidException ex) {
        List<ValidationErrorDto.FieldViolation> violations = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> new ValidationErrorDto.FieldViolation(error.getField(), error.getDefaultMessage()))
                .toList();
        ValidationErrorDto body = new ValidationErrorDto(
                "VALIDATION_FAILED",
                "Request validation failed",
                HttpStatus.BAD_REQUEST.value(),
                violations);
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ValidationErrorDto> handleMessageNotReadable(HttpMessageNotReadableException ex) {
        ValidationErrorDto body = new ValidationErrorDto(
                "MALFORMED_REQUEST",
                "Malformed request",
                HttpStatus.BAD_REQUEST.value(),
                null);
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ValidationErrorDto> handleMissingServletRequestParameter(
            MissingServletRequestParameterException ex) {
        ValidationErrorDto body = new ValidationErrorDto(
                "MISSING_PARAMETER",
                "Missing required parameter: " + ex.getParameterName(),
                HttpStatus.BAD_REQUEST.value(),
                null);
        return ResponseEntity.badRequest().body(body);
    }

    /**
     * A request parameter that could not be converted, for example a
     * non-numeric {@code page} or {@code expectedVersion}.
     *
     * <p>Spring's own resolver answers this with a 400 and an empty body. The
     * client needs the same structured contract every other request error uses,
     * and the offending value is echoed nowhere - an arbitrary client-supplied
     * string is not something to reflect back into a JSON response. Only the
     * parameter name and a fixed message are returned.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ValidationErrorDto> handleMethodArgumentTypeMismatch(
            MethodArgumentTypeMismatchException ex) {
        String parameterName = ex.getName() == null ? "" : ex.getName();
        ValidationErrorDto body = new ValidationErrorDto(
                "VALIDATION_FAILED",
                "Request validation failed",
                HttpStatus.BAD_REQUEST.value(),
                List.of(new ValidationErrorDto.FieldViolation(parameterName,
                        "must be a valid value of the expected type")));
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ValidationErrorDto> handleConstraintViolation(ConstraintViolationException ex) {
        List<ValidationErrorDto.FieldViolation> violations = ex.getConstraintViolations().stream()
                .map(violation -> new ValidationErrorDto.FieldViolation(
                        violation.getPropertyPath() == null ? "" : violation.getPropertyPath().toString(),
                        violation.getMessage()))
                .toList();
        ValidationErrorDto body = new ValidationErrorDto(
                "VALIDATION_FAILED",
                "Request validation failed",
                HttpStatus.BAD_REQUEST.value(),
                violations);
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ValidationErrorDto> handleHandlerMethodValidation(HandlerMethodValidationException ex) {
        List<ValidationErrorDto.FieldViolation> violations = ex.getAllValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> new ValidationErrorDto.FieldViolation(
                                result.getMethodParameter().getParameterName(),
                                error.getDefaultMessage())))
                .toList();
        ValidationErrorDto body = new ValidationErrorDto(
                "VALIDATION_FAILED",
                "Request validation failed",
                HttpStatus.BAD_REQUEST.value(),
                violations);
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ValidationErrorDto> handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        logClientError(ex);
        ValidationErrorDto body = new ValidationErrorDto(
                "DATA_INTEGRITY_VIOLATION",
                "Request conflicts with the current state of the resource",
                HttpStatus.CONFLICT.value(),
                null);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

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
