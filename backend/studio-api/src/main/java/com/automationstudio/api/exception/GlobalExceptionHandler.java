package com.automationstudio.api.exception;

import com.automationstudio.api.dto.error.ApiErrorResponse;
import com.automationstudio.api.service.ExecutionHeartbeatException;
import com.automationstudio.api.service.ExecutionReclaimException;
import com.automationstudio.api.service.HeartbeatFailure;
import com.automationstudio.api.service.ReclaimFailure;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Comparator;
import java.util.Objects;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<Object> handleResourceNotFoundException(
            ResourceNotFoundException exception,
            HttpServletRequest request) {
        return buildErrorResponse(
                HttpStatus.NOT_FOUND,
                exception.getMessage(),
                request.getRequestURI());
    }

    @ExceptionHandler(DuplicateResourceException.class)
    public ResponseEntity<Object> handleDuplicateResourceException(
            DuplicateResourceException exception,
            HttpServletRequest request) {
        return buildErrorResponse(
                HttpStatus.CONFLICT,
                exception.getMessage(),
                request.getRequestURI());
    }

    @ExceptionHandler(ResourceConflictException.class)
    public ResponseEntity<Object> handleResourceConflictException(
            ResourceConflictException exception,
            HttpServletRequest request) {
        return buildErrorResponse(
                HttpStatus.CONFLICT,
                exception.getMessage(),
                request.getRequestURI());
    }

    @ExceptionHandler(InvalidRequestException.class)
    public ResponseEntity<Object> handleInvalidRequestException(
            InvalidRequestException exception,
            HttpServletRequest request) {
        return buildErrorResponse(
                HttpStatus.BAD_REQUEST,
                exception.getMessage(),
                request.getRequestURI());
    }

    @ExceptionHandler(ExecutionHeartbeatException.class)
    public ResponseEntity<Object> handleExecutionHeartbeatException(
            ExecutionHeartbeatException exception,
            HttpServletRequest request) {
        HttpStatus status = exception.getFailure() == HeartbeatFailure.LEASE_NOT_FOUND
                ? HttpStatus.NOT_FOUND
                : HttpStatus.CONFLICT;
        return buildErrorResponse(status, exception.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(ExecutionReclaimException.class)
    public ResponseEntity<Object> handleExecutionReclaimException(
            ExecutionReclaimException exception,
            HttpServletRequest request) {
        HttpStatus status = switch (exception.getFailure()) {
            case LEASE_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case LEASE_STILL_ACTIVE, EXECUTION_STATE_INELIGIBLE,
                    GENERATION_OVERFLOW, CONCURRENT_RECLAIM_CONFLICT ->
                    HttpStatus.CONFLICT;
            case TOKEN_GENERATION_FAILED, PERSISTENCE_FAILED ->
                    HttpStatus.INTERNAL_SERVER_ERROR;
        };
        String message = status == HttpStatus.INTERNAL_SERVER_ERROR
                ? "An unexpected error occurred"
                : exception.getMessage();
        return buildErrorResponse(status, message, request.getRequestURI());
    }

    @ExceptionHandler(PreconditionRequiredException.class)
    public ResponseEntity<Object> handlePreconditionRequiredException(
            PreconditionRequiredException exception,
            HttpServletRequest request) {
        return buildErrorResponse(
                HttpStatus.PRECONDITION_REQUIRED,
                exception.getMessage(),
                request.getRequestURI());
    }

    @ExceptionHandler(SchedulingOperationException.class)
    public ResponseEntity<Object> handleSchedulingOperationException(
            SchedulingOperationException exception,
            HttpServletRequest request) {
        LOGGER.error("Scheduling operation failed for request {}", request.getRequestURI(), exception);
        return buildErrorResponse(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred",
                request.getRequestURI());
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .sorted(Comparator.comparing(FieldError::getField)
                        .thenComparing(error -> Objects.toString(error.getDefaultMessage(), "")))
                .map(error -> error.getField() + ": "
                        + Objects.toString(error.getDefaultMessage(), "Invalid value"))
                .collect(Collectors.joining("; "));

        return buildErrorResponse(
                HttpStatus.BAD_REQUEST,
                message,
                getRequestPath(request));
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        return buildErrorResponse(
                HttpStatus.BAD_REQUEST,
                "Malformed or unreadable request body",
                getRequestPath(request));
    }

    @Override
    protected ResponseEntity<Object> handleTypeMismatch(
            TypeMismatchException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        String message = "Invalid request parameter";
        if (exception instanceof MethodArgumentTypeMismatchException mismatchException) {
            message = "Invalid value for parameter '" + mismatchException.getName() + "'";
        }

        return buildErrorResponse(
                HttpStatus.BAD_REQUEST,
                message,
                getRequestPath(request));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpectedException(
            Exception exception,
            HttpServletRequest request) {
        LOGGER.error("Unhandled exception for request {}", request.getRequestURI(), exception);
        return buildErrorResponse(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred",
                request.getRequestURI());
    }

    private ResponseEntity<Object> buildErrorResponse(
            HttpStatus status,
            String message,
            String path) {
        ApiErrorResponse response = new ApiErrorResponse(
                Instant.now(),
                status.value(),
                status.getReasonPhrase(),
                message,
                path);
        return ResponseEntity.status(status).body(response);
    }

    private String getRequestPath(WebRequest request) {
        if (request instanceof ServletWebRequest servletWebRequest) {
            return servletWebRequest.getRequest().getRequestURI();
        }
        return "";
    }
}
