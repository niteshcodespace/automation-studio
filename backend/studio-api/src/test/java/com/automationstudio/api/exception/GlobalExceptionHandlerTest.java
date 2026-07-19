package com.automationstudio.api.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.automationstudio.api.dto.error.ApiErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@ExtendWith(MockitoExtension.class)
class GlobalExceptionHandlerTest {

    @Mock
    private HttpServletRequest request;

    @InjectMocks
    private GlobalExceptionHandler handler;

    @Test
    void resourceConflictMapsToApiErrorResponseWithConflictStatus() {
        when(request.getRequestURI()).thenReturn("/test");

        ResponseEntity<Object> response = handler.handleResourceConflictException(
                new ResourceConflictException("conflict"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isInstanceOfSatisfying(
                ApiErrorResponse.class,
                body -> {
                    assertThat(body.status()).isEqualTo(409);
                    assertThat(body.message()).isEqualTo("conflict");
                    assertThat(body.path()).isEqualTo("/test");
                });
    }

    @Test
    void invalidRequestMapsToApiErrorResponseWithBadRequestStatus() {
        when(request.getRequestURI()).thenReturn("/test");

        ResponseEntity<Object> response = handler.handleInvalidRequestException(
                new InvalidRequestException("invalid"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isInstanceOfSatisfying(
                ApiErrorResponse.class,
                body -> {
                    assertThat(body.status()).isEqualTo(400);
                    assertThat(body.message()).isEqualTo("invalid");
                    assertThat(body.path()).isEqualTo("/test");
                });
    }
}
