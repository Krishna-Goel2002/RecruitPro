package com.recruitpro.assistant.service;

import com.recruitpro.assistant.tool.AssistantToolException;
import com.recruitpro.common.exception.BadRequestException;
import com.recruitpro.common.exception.DuplicateResourceException;
import com.recruitpro.common.exception.ForbiddenException;
import com.recruitpro.common.exception.ResourceNotFoundException;
import com.recruitpro.common.exception.TooManyRequestsException;
import org.springframework.web.server.ResponseStatusException;

/**
 * User-facing text of an exception a tool or service raised. Only the application's own messages (written for users,
 * without personal data) are passed on; anything unexpected becomes a generic sentence.
 */
public final class AssistantErrors {

    static final String GENERIC = "That could not be completed because of an unexpected error.";

    private AssistantErrors() {
    }

    public static String message(RuntimeException ex) {
        String message = null;
        if (ex instanceof AssistantToolException || ex instanceof BadRequestException || ex instanceof ResourceNotFoundException
                || ex instanceof ForbiddenException || ex instanceof DuplicateResourceException
                || ex instanceof TooManyRequestsException || ex instanceof IllegalArgumentException) {
            message = ex.getMessage();
        } else if (ex instanceof ResponseStatusException status) {
            message = status.getReason();
        }
        if (message == null || message.isBlank()) {
            return GENERIC;
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }

    /** Whether the exception is one of the expected refusals (not logged as an error). */
    public static boolean expected(RuntimeException ex) {
        return ex instanceof AssistantToolException || ex instanceof BadRequestException
                || ex instanceof ResourceNotFoundException || ex instanceof ForbiddenException
                || ex instanceof DuplicateResourceException || ex instanceof ResponseStatusException
                || ex instanceof IllegalArgumentException || ex instanceof TooManyRequestsException;
    }
}
