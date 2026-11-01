package com.recruitpro.common.exception;

import com.recruitpro.common.dto.ApiResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.junit.jupiter.api.Assertions.*;

class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler exceptionHandler;

    @BeforeEach
    void setUp() {
        exceptionHandler = new GlobalExceptionHandler();
    }

    @Test
    @DisplayName("Should handle ResourceNotFoundException with 404 NOT_FOUND status")
    void handleResourceNotFound() {
        ResourceNotFoundException ex = new ResourceNotFoundException("User", "id", 100L);
        ResponseEntity<ApiResponse<Void>> response = exceptionHandler.handleResourceNotFound(ex);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertNotNull(response.getBody());
        assertFalse(response.getBody().isSuccess());
        assertTrue(response.getBody().getMessage().contains("User not found with id: '100'"));
    }

    @Test
    @DisplayName("Spring MVC resource fallback maps an unmapped route to 404")
    void handleNoResourceFound() {
        NoResourceFoundException ex = new NoResourceFoundException(HttpMethod.GET, "api/v1/missing");
        ResponseEntity<ApiResponse<Void>> response = exceptionHandler.handleNoResourceFound(ex);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertNotNull(response.getBody());
        assertFalse(response.getBody().isSuccess());
        assertEquals("The requested endpoint does not exist", response.getBody().getMessage());
    }

    @Test
    @DisplayName("Should handle BadRequestException with 400 BAD_REQUEST status")
    void handleBadRequest() {
        BadRequestException ex = new BadRequestException("Invalid parameter value");
        ResponseEntity<ApiResponse<Void>> response = exceptionHandler.handleBadRequest(ex);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertFalse(response.getBody().isSuccess());
        assertEquals("Invalid parameter value", response.getBody().getMessage());
    }

    @Test
    @DisplayName("Should handle DuplicateResourceException with 409 CONFLICT status")
    void handleDuplicateResource() {
        DuplicateResourceException ex = new DuplicateResourceException("User", "email", "test@example.com");
        ResponseEntity<ApiResponse<Void>> response = exceptionHandler.handleDuplicateResource(ex);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertNotNull(response.getBody());
        assertFalse(response.getBody().isSuccess());
        assertTrue(response.getBody().getMessage().contains("User already exists with email: 'test@example.com'"));
    }

    @Test
    @DisplayName("Should handle UnauthorizedException with 401 UNAUTHORIZED status")
    void handleUnauthorized() {
        UnauthorizedException ex = new UnauthorizedException("Invalid token");
        ResponseEntity<ApiResponse<Void>> response = exceptionHandler.handleUnauthorized(ex);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertNotNull(response.getBody());
        assertFalse(response.getBody().isSuccess());
        assertEquals("Invalid token", response.getBody().getMessage());
    }

    @Test
    @DisplayName("Should handle ForbiddenException with 403 FORBIDDEN status")
    void handleForbidden() {
        ForbiddenException ex = new ForbiddenException("Access denied");
        ResponseEntity<ApiResponse<Void>> response = exceptionHandler.handleForbidden(ex);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertNotNull(response.getBody());
        assertFalse(response.getBody().isSuccess());
        assertEquals("Access denied", response.getBody().getMessage());
    }

    @Test
    @DisplayName("Should handle unexpected Exception with 500 INTERNAL_SERVER_ERROR status")
    void handleGenericException() {
        Exception ex = new RuntimeException("Null pointer crash");
        ResponseEntity<ApiResponse<Void>> response = exceptionHandler.handleGenericException(ex);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertNotNull(response.getBody());
        assertFalse(response.getBody().isSuccess());
        assertEquals("An unexpected error occurred. Please try again later.", response.getBody().getMessage());
    }
}
