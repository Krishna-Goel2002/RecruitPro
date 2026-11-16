package com.recruitpro.assistant;

import com.recruitpro.ai.dto.AiAssistantStepRequest;
import com.recruitpro.ai.dto.AiAssistantStepResponse;
import com.recruitpro.ai.service.AiServiceClient;
import com.recruitpro.assistant.service.AssistantPlanner;
import com.recruitpro.assistant.service.PlannerUnavailableException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The planner port stops waiting when the message's time budget runs out and rejects unusable replies (AS-BE). */
class AssistantPlannerTest {

    private static final AiAssistantStepRequest REQUEST = new AiAssistantStepRequest(List.of(), Map.of("page", "other"),
            List.of(), List.of(), null, false);

    @Test
    void aSlowPlannerTimesOutWithinTheRemainingBudget() {
        AiServiceClient client = mock(AiServiceClient.class);
        when(client.assistantStep(any())).thenAnswer(invocation -> {
            Thread.sleep(5_000);
            return answer("answer");
        });
        AssistantPlanner planner = new AssistantPlanner(client);
        long started = System.nanoTime();
        PlannerUnavailableException ex = assertThrows(PlannerUnavailableException.class, () -> planner.step(REQUEST, 200));
        assertTrue(ex.isTimedOut());
        assertTrue((System.nanoTime() - started) / 1_000_000L < 3_000, "the caller is released at the deadline");
    }

    @Test
    void noBudgetLeftMeansNoCall() {
        AiServiceClient client = mock(AiServiceClient.class);
        AssistantPlanner planner = new AssistantPlanner(client);
        PlannerUnavailableException ex = assertThrows(PlannerUnavailableException.class, () -> planner.step(REQUEST, 0));
        assertTrue(ex.isTimedOut());
    }

    @Test
    void unknownStepTypesAndFailuresAreUnavailable() throws Exception {
        AiServiceClient client = mock(AiServiceClient.class);
        when(client.assistantStep(any())).thenReturn(answer("something_else"), answer("answer"))
                .thenThrow(new IllegalStateException("boom"));
        AssistantPlanner planner = new AssistantPlanner(client);
        PlannerUnavailableException unknown = assertThrows(PlannerUnavailableException.class,
                () -> planner.step(REQUEST, 5_000));
        assertFalse(unknown.isTimedOut());
        assertEquals("answer", planner.step(REQUEST, 5_000).type());
        assertThrows(PlannerUnavailableException.class, () -> planner.step(REQUEST, 5_000));
    }

    private static AiAssistantStepResponse answer(String type) {
        return new AiAssistantStepResponse(type, null, null, null, "ok", null, null, null, null, null, null,
                Map.of("planner", "offline"));
    }
}
