package com.recruitpro.common.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ApiResponseTest {

    @Test
    @DisplayName("Should create successful response with data and message")
    void successWithDataAndMessage() {
        String dataPayload = "Test Payload";
        ApiResponse<String> response = ApiResponse.success(dataPayload, "Custom success message");

        assertTrue(response.isSuccess());
        assertEquals("Custom success message", response.getMessage());
        assertEquals(dataPayload, response.getData());
        assertNull(response.getErrors());
        assertNotNull(response.getTimestamp());
    }

    @Test
    @DisplayName("Should create successful response with default message")
    void successWithDefaultMessage() {
        Integer dataPayload = 42;
        ApiResponse<Integer> response = ApiResponse.success(dataPayload);

        assertTrue(response.isSuccess());
        assertEquals("Operation completed successfully", response.getMessage());
        assertEquals(42, response.getData());
    }

    @Test
    @DisplayName("Should create error response")
    void errorResponse() {
        ApiResponse<Void> response = ApiResponse.error("Something went wrong");

        assertFalse(response.isSuccess());
        assertEquals("Something went wrong", response.getMessage());
        assertNull(response.getData());
        assertNull(response.getErrors());
    }

    @Test
    @DisplayName("Should create validation error response with field errors")
    void validationErrorResponse() {
        ApiResponse.FieldError error1 = ApiResponse.FieldError.builder()
                .field("email")
                .message("Email must be valid")
                .build();

        ApiResponse<Void> response = ApiResponse.validationError("Validation failed", List.of(error1));

        assertFalse(response.isSuccess());
        assertEquals("Validation failed", response.getMessage());
        assertNotNull(response.getErrors());
        assertEquals(1, response.getErrors().size());
        assertEquals("email", response.getErrors().get(0).getField());
        assertEquals("Email must be valid", response.getErrors().get(0).getMessage());
    }
}
