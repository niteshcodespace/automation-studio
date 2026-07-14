package com.automationstudio.api.exception;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final URI VALIDATION_TYPE = URI.create("urn:automation-studio:problem:validation");
    private static final URI NOT_FOUND_TYPE = URI.create("urn:automation-studio:problem:not-found");
    private static final URI CONFLICT_TYPE = URI.create("urn:automation-studio:problem:invalid-execution-request");

    @ExceptionHandler(ResourceNotFoundException.class)
    ProblemDetail handleNotFound(ResourceNotFoundException exception) {
        return problem(HttpStatus.NOT_FOUND, NOT_FOUND_TYPE, "Resource not found", exception.getMessage());
    }

    @ExceptionHandler(InvalidExecutionRequestException.class)
    ProblemDetail handleConflict(InvalidExecutionRequestException exception) {
        return problem(HttpStatus.CONFLICT, CONFLICT_TYPE, "Invalid execution request", exception.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handleValidation(MethodArgumentNotValidException exception) {
        ProblemDetail detail = problem(
                HttpStatus.BAD_REQUEST, VALIDATION_TYPE, "Request validation failed", "One or more fields are invalid");
        Map<String, String> errors = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors()
                .forEach(error -> errors.putIfAbsent(error.getField(), error.getDefaultMessage()));
        detail.setProperty("errors", errors);
        return detail;
    }

    private ProblemDetail problem(HttpStatus status, URI type, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(type);
        problem.setTitle(title);
        return problem;
    }
}
