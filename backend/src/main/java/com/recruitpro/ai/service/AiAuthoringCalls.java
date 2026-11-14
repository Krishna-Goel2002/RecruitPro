package com.recruitpro.ai.service;

import com.recruitpro.common.exception.BadRequestException;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import java.util.function.Supplier;

/**
 * Error mapping for synchronous AI drafting calls made by explicit HR actions (work samples, interview kits):
 * a 4xx rejection (except 408 and 429) becomes 400; an unreachable or failing service, a missing service token
 * or an empty body becomes 503. Callers must not hold a database transaction while calling.
 */
public final class AiAuthoringCalls {

    private AiAuthoringCalls() {
    }

    public static <T> T call(Supplier<T> call, String unavailableMessage, String rejectedMessage) {
        T result;
        try {
            result = call.get();
        } catch (RestClientResponseException ex) {
            int status = ex.getStatusCode().value();
            if (status >= 400 && status < 500 && status != 408 && status != 429) {
                throw new BadRequestException(rejectedMessage);
            }
            throw unavailable(unavailableMessage);
        } catch (RestClientException | IllegalStateException ex) {
            throw unavailable(unavailableMessage);
        }
        if (result == null) {
            throw unavailable(unavailableMessage);
        }
        return result;
    }

    private static ResponseStatusException unavailable(String message) {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, message);
    }
}
